# ServiceLens — Architecture Overview

ServiceLens is an AI-powered code intelligence platform for Java backends. It ingests source code into a hybrid knowledge store (Neo4j graph + pgvector), then answers natural-language questions about the codebase using intent-aware retrieval and LLM synthesis.

---

## High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                          HTTP API Layer                            │
│   IngestionController  │  QueryController  │  (service mgmt)       │
└────────────┬──────────────────────────────┬───────────────────────┘
             │                              │
┌────────────▼──────────────────────┐       │
│      Ingestion Strategy Layer     │       │
│  IngestionStrategyResolver        │       │
│  ├─ FRESH → IngestionPipeline     │       │
│  ├─ INCREMENTAL → IncrementalSvc  │       │
│  └─ FORCE_FULL → purge + pipeline │       │
└────────────┬──────────────────────┘       │
             │                              │
┌────────────▼──────────────────────┐   ┌───▼─────────────────────┐
│   Service Registry (PostgreSQL)   │   │  IntentBasedRetriever    │
│   service_registry table          │   │  (11 intent handlers)    │
│   status / timestamps / fileCount │   └───┬─────────────────────┘
└───────────────────────────────────┘       │
             │                              │
┌────────────▼──────────────────────────────▼────────────────────┐
│                     Core Analysis Layer                         │
│  JavaFileProcessor  │  CfgBuilder  │  DfgBuilder               │
│  DocumentationExtractor  │  Chunking pipeline                  │
└────────────┬──────────────────────────────┬────────────────────┘
             │                              │
┌────────────▼──────────┐   ┌───────────────▼──────────────────┐
│   Neo4j Knowledge     │   │  pgvector Store                   │
│   Graph               │   │  (Ollama embeddings)              │
│  Class / Method /     │   │  CODE / TEST / CONFIG /           │
│  CfgNode nodes        │   │  DOCS / API_SPEC chunks           │
└───────────────────────┘   └──────────────────────────────────┘
             │                              │
┌────────────▼──────────────────────────────▼────────────────────┐
│             Answer Synthesis (Groq LLM)                         │
│  ContextAssembler  │  PromptTemplates  │  AnswerSynthesizer     │
└────────────────────────────────────────────────────────────────┘
```

---

## Layer Breakdown

### 1. HTTP API Layer

| Controller | Endpoints | Responsibility |
|---|---|---|
| `IngestionController` | `POST /api/ingest`, `DELETE /api/services/{name}`, `GET /api/services`, `GET /api/services/{name}`, `GET /api/retrieve` | Smart ingestion with strategy routing, service lifecycle management, raw semantic search |
| `QueryController` | `POST /api/query`, `POST /api/ask` | Intent-based retrieval and retrieval + LLM synthesis |

### 2. Ingestion Strategy Layer

`IngestionStrategyResolver` checks the **Service Registry** before any data is written and picks one of three strategies:

| Strategy | Condition | Action |
|---|---|---|
| `FRESH` | Service not in registry | Full pipeline; register service on completion |
| `INCREMENTAL` | Service registered, `force=false` | Only changed files; ~32× faster |
| `FORCE_FULL` | Service registered, `force=true` | `ServiceDeletionService.purgeData()` then full pipeline |

`IngestionPipeline` itself is **always a pure write** — it never purges on its own. This keeps ingestion and deletion concerns cleanly separated.

### 3. Service Registry

A PostgreSQL table (`service_registry`) tracks every ingested service:

| Column | Description |
|---|---|
| `service_name` | Primary key / isolation key |
| `repo_path` | Last known repo path |
| `status` | `INGESTING` → `ACTIVE` → `DELETING` |
| `ingested_at` | First successful ingestion timestamp (never overwritten) |
| `last_updated_at` | Most recent ingestion or update |
| `file_count` | Number of files in last ingestion |

`PostgresSchemaInitializer` (an `ApplicationRunner`) creates the table with `CREATE TABLE IF NOT EXISTS` on every startup — idempotent and safe.

See [service-registry.md](service-registry.md) for the full design.

### 4. Ingestion Pipeline
`IngestionPipeline` orchestrates the full ingestion flow:
1. Walk the file tree (skips `target/`, `.git/`, `build/`)
2. Dispatch each file to the appropriate `FileProcessor`
3. For Java files: parse AST → extract class/method nodes → build CFG + DFG → create code chunks
4. For other files: YAML → CONFIG chunks, SQL → SCHEMA chunks, Markdown → DOCUMENTATION chunks, OpenAPI → API_SPEC chunks
5. Persist graph nodes to Neo4j via `KnowledgeGraphService`
6. Embed and persist code/doc chunks to pgvector
7. Save file hashes via `FileFingerprinter`

`IncrementalIngestionService` wraps this with SHA-256 fingerprinting — only added/modified/deleted files are processed (~32× speedup over full re-ingestion).

### 5. Service Deletion

`ServiceDeletionService` handles two deletion modes:

| Method | What it removes |
|---|---|
| `purgeData(name)` | Neo4j nodes + pgvector chunks + hash file (registry entry **preserved**) |
| `delete(name)` | All of the above **plus** the registry entry |

`purgeData` is called internally before `FORCE_FULL` re-ingestion. `delete` is called by `DELETE /api/services/{name}`.

### 6. Core Analysis Layer

| Component | Description |
|---|---|
| `JavaFileProcessor` | Highest-priority processor. Drives AST parsing via JavaParser, builds all nodes. |
| `CfgBuilder` | Single AST pass → control flow graph per method (11 statement types) |
| `DfgBuilder` | Two-pass variable def/use analysis per method |
| `DocumentationExtractor` | Extracts Javadoc + inline comments + TODO/FIXME markers |
| `RetrievalReranker` | Post-retrieval scoring: metadata heuristics + optional LLM cross-encoder |

### 7. Knowledge Stores

| Store | Technology | What is stored |
|---|---|---|
| Graph | Neo4j (SDN + Neo4jClient) | Class, Method, CfgNode nodes + 7 relationship types |
| Vector | PostgreSQL + pgvector | Embedded code chunks with metadata (7 chunk types) |
| Registry | PostgreSQL (JdbcTemplate) | Service metadata, status, timestamps |

### 8. Retrieval Layer
`IntentBasedRetriever` classifies the user's query into one of 11 intents via regex pattern matching (< 1ms), then routes to the matching handler. Each handler combines vector search, graph traversal, and optional reranking in a way tuned to that intent. See [retrieval-and-intent.md](retrieval-and-intent.md).

### 9. Answer Synthesis
`AnswerSynthesizer` assembles the retrieval context (token-budget-aware, max ~32K chars), selects an intent-aware system prompt from `PromptTemplates`, and calls Groq (`llama-3.3-70b-versatile`) via Spring AI `ChatClient`. Returns a `SynthesisResult` with the answer, intent, model used, and context chunk count.

---

## Technology Stack

| Concern | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| AST Parsing | JavaParser 3.25 |
| Graph DB | Neo4j (Spring Data Neo4j + Neo4jClient) |
| Vector DB | PostgreSQL + pgvector |
| Service Registry | PostgreSQL (JdbcTemplate — no JPA conflict) |
| Embeddings | Ollama (`nomic-embed-text`, 768 dims) |
| LLM Chat | Groq (`llama-3.3-70b-versatile`) via Spring AI |
| Local LLM | Ollama (`phi3`) for cross-encoder reranking |
| Spec Parsing | Swagger Parser 2.1 |
| Build | Maven |

---

## Data Flow Summary

```
Source Code
    │
    ▼
IngestionStrategyResolver
    ├─ FRESH / FORCE_FULL ──────────────────────────┐
    │                                               │
    └─ INCREMENTAL (diff only) ─────────────────────┤
                                                    │
                                                    ▼
                                          JavaParser (AST)
                                              ├─► ClassNode ──────────────► Neo4j
                                              ├─► MethodNode ─────────────► Neo4j
                                              ├─► CfgNode / CfgEdge ──────► Neo4j
                                              ├─► CodeChunk ─► Ollama ────► pgvector
                                              └─► DocChunk  ─► Ollama ────► pgvector
                                                                │
                                              FileFingerprinter saves hashes
                                              ServiceRegistry records service

Query
    │
    ▼
IntentClassifier (< 1ms, regex)
    │
    ▼
IntentBasedRetriever
    ├─► pgvector similarity search
    └─► Neo4j graph traversal
    │
    ▼
ContextAssembler (32K char budget)
    │
    ▼
Groq LLM (llama-3.3-70b-versatile)
    │
    ▼
SynthesisResult → HTTP Response
```
