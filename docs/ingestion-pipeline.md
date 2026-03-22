# Ingestion Pipeline

## Overview

The ingestion pipeline is responsible for transforming raw source code into the two knowledge stores: Neo4j (structural graph) and pgvector (semantic chunks). It supports both full re-ingestion and incremental change-only ingestion.

---

## Full Ingestion

### Trigger
```
POST /api/ingest
{
  "repoPath": "/path/to/your/service",
  "serviceName": "my-service"
}
```

### Flow

```
IngestionPipeline.ingest(path, serviceName)
│
├─ 1. Purge old data
│      KnowledgeGraphService.deleteServiceGraph(serviceName)
│      VectorStore.delete(filter: service_name == serviceName)
│
├─ 2. Walk file tree (skip target/, .git/, build/)
│      For each file:
│      │
│      ├─ .java  → JavaFileProcessor (priority: highest)
│      │           └─ returns JavaFileResult
│      │               ├─ chunks (code + test)
│      │               ├─ docChunks (documentation)
│      │               ├─ classNodes
│      │               ├─ methodNodes
│      │               └─ cfgNodes
│      │
│      ├─ .yml / .yaml → YamlFileProcessor
│      │                 └─ CONFIG chunks
│      │
│      ├─ .sql   → SqlFileProcessor
│      │           └─ SCHEMA chunks
│      │
│      ├─ .md    → MarkdownFileProcessor
│      │           └─ DOCUMENTATION chunks
│      │
│      └─ openapi*.json / swagger*.yaml → OpenApiFileProcessor
│                                         └─ API_SPEC chunks
│
├─ 3. Save graph (Neo4j)
│      KnowledgeGraphService.saveFileResult(result)
│      CfgSaver.saveCfgNodes(cfgNodes)
│
├─ 4. Embed and store code chunks (pgvector)
│      Ollama (nomic-embed-text) → 768-dim vectors
│      Spring AI VectorStore.add(codeDocs)
│
└─ 5. Embed and store doc chunks (pgvector)
       Ollama (nomic-embed-text) → 768-dim vectors
       Spring AI VectorStore.add(docDocs)
```

### Returns
`IngestionResult` with service stats: class count, method count, endpoint count, chunk count, doc chunk count.

---

## Incremental Ingestion

### Trigger
```
POST /api/ingest/incremental
{
  "repoPath": "/path/to/your/service",
  "serviceName": "my-service"
}
```

### Flow

```
IncrementalIngestionService.ingest(path, serviceName)
│
├─ 1. Load stored file hashes
│      FileFingerprinter.loadHashes(serviceName)
│      → Map<relativePath, sha256hex>
│
├─ 2. Walk current file tree, compute SHA-256 for each file
│
├─ 3. Classify files
│      ├─ ADDED    — path not in stored hashes
│      ├─ MODIFIED — path in stored hashes but hash differs
│      ├─ DELETED  — path in stored hashes but file no longer exists
│      └─ UNCHANGED — hash matches (skip)
│
├─ 4. Process changes
│      ADDED:
│        ingest file → store hash
│
│      MODIFIED:
│        deleteByFilePath(filePath) from Neo4j
│        deleteChunksByFilePath(filePath) from pgvector
│        re-ingest file
│        update stored hash
│
│      DELETED:
│        deleteByFilePath(filePath) from Neo4j
│        deleteChunksByFilePath(filePath) from pgvector
│        remove hash from map
│
└─ 5. Persist updated hash map
       FileFingerprinter.saveHashes(serviceName, updatedMap)
```

### Performance
Approximately **32× faster** than full re-ingestion for typical workloads (e.g., 8 min full → 15 sec incremental for a 600-file service with 3 changed files).

### Returns
`IncrementalResult` with counts: added, modified, deleted, skipped.

---

## File Fingerprinter

`FileFingerprinter` manages SHA-256 hash maps stored on disk.

| Method | Description |
|---|---|
| `computeHash(file)` | Reads file bytes, computes SHA-256, returns hex string |
| `loadHashes(serviceName)` | Reads `{dataPath}/{serviceName}/file-hashes.json` |
| `saveHashes(serviceName, map)` | Persists hash map back to disk |
| `updateHash(serviceName, path, hash)` | Updates single entry |
| `removeHash(serviceName, path)` | Removes entry for deleted file |

Hash files are stored at the path configured by `servicelens.data-path` (default: `./servicelens-data/{serviceName}/file-hashes.json`).

---

## Java File Processing (Deep Dive)

`JavaFileProcessor` is the most complex processor. For each `.java` file:

### 1. Parse AST
Uses a JavaParser instance configured for Java 18 language level. Produces a `CompilationUnit`.

### 2. Extract Types
For each `TypeDeclaration` in the compilation unit:
- Build `ClassNode` with qualifiedName, simpleName, package, annotations, and Spring stereotype
- Detect `nodeType`: CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE
- Extract superclass → `INHERITS` relationship
- Extract interfaces → `IMPLEMENTS` relationships
- Detect field injection types → `DEPENDS_ON` relationships

Spring stereotypes recognized: `@Service`, `@Controller`, `@RestController`, `@Repository`, `@Configuration`, `@Component`

### 3. Extract Methods
For each `MethodDeclaration` or `ConstructorDeclaration`:
- Build `MethodNode` with all metadata
- Detect HTTP mapping annotations → set `isEndpoint`, `httpMethod`, `endpointPath`
- Detect `@Transactional`, `@Scheduled`, `@EventListener`, `@Test`
- Resolve method calls → `CALLS` relationships
- Detect `@Override` → `OVERRIDES` relationship

HTTP annotations recognized: `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@RequestMapping`

### 4. Build CFG
`CfgBuilder.build(methodDecl)` — single-pass AST traversal producing `CfgNode` + `CfgEdge` objects.

### 5. Build DFG
`DfgBuilder.build(methodDecl)` — two-pass analysis producing `MethodDataFlow`.

### 6. Extract Documentation
`DocumentationExtractor` extracts Javadoc + inline comments → creates separate DOCUMENTATION chunks for pgvector.

### 7. Create Code Chunks
For each method: creates a `CodeChunk` with:
- `content`: method body + Javadoc + inline comments concatenated
- `chunkType`: CODE (or TEST if `isTestMethod`)
- All metadata fields (filePath, serviceName, isEndpoint, isTransactional, etc.)

---

## FileProcessor Interface

To add support for a new file type, implement:

```java
public interface FileProcessor {
    boolean supports(Path file);
    List<CodeChunk> process(Path file, String serviceName);
    int priority(); // higher = processed first
}
```

Register as a Spring `@Component` — the pipeline auto-discovers all `FileProcessor` beans sorted by priority.

---

## Chunk Types and Their Embedding Strategy

| ChunkType | Source | Embedding Model | Use in Retrieval |
|---|---|---|---|
| `CODE` | Java method bodies | nomic-embed-text | Primary source for most queries |
| `TEST` | Java test methods | nomic-embed-text | FIND_TESTS intent |
| `CONFIG` | YAML/properties files | nomic-embed-text | FIND_CONFIGURATION intent |
| `SCHEMA` | SQL DDL files | nomic-embed-text | Schema-related queries |
| `API_SPEC` | OpenAPI / Swagger | nomic-embed-text | Endpoint contract queries |
| `DOCUMENTATION` | Javadoc, Markdown | nomic-embed-text | UNDERSTAND_BUSINESS_RULE, "why" questions |
| `BUSINESS_CONTEXT` | Markdown docs, comments | nomic-embed-text | Business logic understanding |

All chunks use the same embedding model (`nomic-embed-text`, 768 dimensions). Chunk type is stored as metadata in pgvector and used during retrieval filtering and reranking.
