# Ingestion Pipeline

## Overview

The ingestion pipeline transforms raw source code into the two knowledge stores: Neo4j (structural graph) and pgvector (semantic chunks). It supports three ingestion modes — **fresh**, **incremental**, and **force full** — selected automatically by `IngestionStrategyResolver` based on whether the service has been ingested before and whether `force` was requested.

---

## Ingestion Strategy Resolution

Before any data is written, `IngestionStrategyResolver` checks the **Service Registry** (a PostgreSQL table) and the `force` flag to pick one of three strategies:

```
IngestionStrategyResolver.resolve(serviceName, force)
│
├─ Service NOT in registry  →  FRESH
│    (first-time ingestion, pipeline runs as-is, no purge needed)
│
├─ Service IN registry + force=false  →  INCREMENTAL
│    (only changed files are processed, ~32× faster)
│
└─ Service IN registry + force=true   →  FORCE_FULL
     (ServiceDeletionService.purgeData() clears data, then full pipeline runs)
```

This means the pipeline itself is **always a pure write** — it never purges. Purging is only done by `ServiceDeletionService` before a `FORCE_FULL` run, keeping concerns cleanly separated.

---

## Strategy: FRESH — First-Time Ingestion

### Trigger
```
POST /api/ingest
{
  "repoPath": "/path/to/your/service",
  "serviceName": "my-service"
}
```
(No entry for `my-service` in the service registry yet.)

### Flow

```
IngestionController
│
├─ IngestionStrategyResolver → FRESH
│
└─ IngestionPipeline.ingest(path, serviceName)
   │
   ├─ 1. Walk file tree (skip target/, .git/, build/)
   │      For each file:
   │      │
   │      ├─ .java  → JavaFileProcessor
   │      │           └─ JavaFileResult (chunks, docChunks, classNodes, methodNodes, cfgNodes)
   │      │
   │      ├─ .yml / .yaml → YamlFileProcessor   → CONFIG chunks
   │      ├─ .sql          → SqlFileProcessor    → SCHEMA chunks
   │      ├─ .md           → MarkdownFileProcessor → DOCUMENTATION chunks
   │      └─ openapi*.json / swagger*.yaml → OpenApiFileProcessor → API_SPEC chunks
   │
   ├─ 2. Save graph (Neo4j)
   │      KnowledgeGraphService.saveFileResult(result)
   │      CfgSaver.saveCfgNodes(cfgNodes)
   │
   ├─ 3. Embed and store code chunks (pgvector)
   │      Ollama (nomic-embed-text) → 768-dim vectors → VectorStore.add()
   │
   ├─ 4. Embed and store doc chunks (pgvector)
   │      Ollama (nomic-embed-text) → 768-dim vectors → VectorStore.add()
   │
   └─ 5. Save file hashes
          FileFingerprinter.saveHashes(serviceName, allFileHashes)
          → Written to {dataPath}/{serviceName}/file-hashes.json

IngestionController
└─ ServiceRegistryService.register(serviceName, repoPath, fileCount)
   → Upserts row in service_registry (status=ACTIVE)
```

### Returns
`IngestionResult` — serviceName, totalCodeChunks, totalDocChunks, totalClasses, totalMethods, cfgNodes, languageBreakdown.

---

## Strategy: INCREMENTAL — Changed Files Only

### Trigger
```
POST /api/ingest
{
  "repoPath": "/path/to/your/service",
  "serviceName": "my-service"
}
```
(Service already in registry, `force` not set or false.)

### Flow

```
IngestionController
│
├─ IngestionStrategyResolver → INCREMENTAL
│
└─ IncrementalIngestionService.ingest(path, serviceName)
   │
   ├─ 1. Load stored file hashes
   │      FileFingerprinter.loadHashes(serviceName)
   │      → Map<relativePath, sha256hex> from {dataPath}/{serviceName}/file-hashes.json
   │
   ├─ 2. Walk current file tree, compute SHA-256 for each file
   │
   ├─ 3. Classify files
   │      ├─ ADDED    — path not in stored hashes
   │      ├─ MODIFIED — path in stored hashes but hash differs
   │      ├─ DELETED  — path in stored hashes but file no longer exists
   │      └─ UNCHANGED — hash matches (skip entirely)
   │
   ├─ 4. Process changes
   │      ADDED:
   │        ingest file → store hash
   │
   │      MODIFIED:
   │        deleteByFilePath(filePath) from Neo4j
   │        deleteChunksByFilePath(filePath) from pgvector
   │        re-ingest file → update stored hash
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
`IncrementalResult` — serviceName, added, modified, deleted, unchanged.

---

## Strategy: FORCE_FULL — Re-ingest Everything

### Trigger
```
POST /api/ingest
{
  "repoPath": "/path/to/your/service",
  "serviceName": "my-service",
  "force": "true"
}
```
(Service already in registry, `force=true`.)

### Flow

```
IngestionController
│
├─ IngestionStrategyResolver → FORCE_FULL
│
├─ ServiceDeletionService.purgeData(serviceName)
│    ├─ KnowledgeGraphService.deleteServiceGraph(serviceName)   ← Neo4j
│    ├─ VectorStore DELETE WHERE service_name = serviceName     ← pgvector
│    └─ FileFingerprinter.clearHashes(serviceName)             ← hash file
│    (registry entry is PRESERVED — purgeData does not remove it)
│
└─ IngestionPipeline.ingest(path, serviceName)
     (same as FRESH flow above — full write with no existing data)

IngestionController
└─ ServiceRegistryService.update(serviceName, repoPath, fileCount)
   → Updates lastUpdatedAt + fileCount in service_registry
```

### Returns
`IngestionResult` — same shape as FRESH.

---

## File Fingerprinter

`FileFingerprinter` manages SHA-256 hash maps stored on disk, one file per service.

| Method | Description |
|---|---|
| `computeHash(file)` | Reads file bytes, computes SHA-256, returns hex string |
| `loadHashes(serviceName)` | Reads `{dataPath}/{serviceName}/file-hashes.json`; returns empty map if file doesn't exist |
| `saveHashes(serviceName, map)` | Persists hash map back to disk (creates directory if needed) |
| `updateHash(serviceName, path, hash)` | Updates single entry and saves |
| `removeHash(serviceName, path)` | Removes entry for deleted file and saves |
| `clearHashes(serviceName)` | Deletes `{dataPath}/{serviceName}/file-hashes.json` entirely (used by FORCE_FULL) |

Hash files are stored at the path configured by `servicelens.data-path` (default: `./servicelens-data`):
```
./servicelens-data/
  payment-service/file-hashes.json
  order-service/file-hashes.json
  task-manager/file-hashes.json
```

Each service's hashes are fully independent — ingesting or force-reingesting one service never affects another service's hash file.

---

## Service Deletion

Deleting a service is handled by `ServiceDeletionService`, which offers two modes:

| Method | What it does |
|---|---|
| `delete(serviceName)` | Full removal: Neo4j + pgvector + hashes + registry entry |
| `purgeData(serviceName)` | Data only: Neo4j + pgvector + hashes; registry entry **preserved** |

`purgeData` is used internally before `FORCE_FULL` ingestion. `delete` is called when `DELETE /api/services/{name}` is invoked. See [service-registry.md](service-registry.md) for details.

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
