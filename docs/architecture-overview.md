# ServiceLens — Architecture Overview

ServiceLens is an AI-powered code intelligence platform for Java backends. It ingests source code into a hybrid knowledge store (Neo4j graph + pgvector), then answers natural-language questions about the codebase using intent-aware retrieval and LLM synthesis.

---

## High-Level Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                        HTTP API Layer                          │
│         IngestionController  │  QueryController                │
└────────────────┬─────────────────────────┬─────────────────────┘
                 │                         │
    ┌────────────▼──────────┐   ┌──────────▼────────────────┐
    │   Ingestion Pipeline  │   │  IntentBasedRetriever      │
    │  (full + incremental) │   │  (11 intent handlers)      │
    └────────────┬──────────┘   └──────────┬────────────────┘
                 │                         │
    ┌────────────▼──────────────────────────▼────────────────┐
    │               Core Analysis Layer                       │
    │  JavaFileProcessor  │  CfgBuilder  │  DfgBuilder        │
    │  DocumentationExtractor  │  Chunking pipeline           │
    └────────────┬──────────────────────────┬────────────────┘
                 │                          │
    ┌────────────▼──────────┐   ┌───────────▼───────────────┐
    │   Neo4j Knowledge     │   │  pgvector Store            │
    │   Graph               │   │  (Ollama embeddings)       │
    │  Class / Method /     │   │  CODE / TEST / CONFIG /    │
    │  CfgNode nodes        │   │  DOCS / API_SPEC chunks    │
    └───────────────────────┘   └───────────────────────────┘
                 │                          │
    ┌────────────▼──────────────────────────▼────────────────┐
    │           Answer Synthesis (Groq LLM)                   │
    │  ContextAssembler  │  PromptTemplates  │ AnswerSynthesizer│
    └────────────────────────────────────────────────────────┘
```

---

## Layer Breakdown

### 1. HTTP API Layer
Two controllers expose the public surface:

| Controller | Endpoints | Responsibility |
|---|---|---|
| `IngestionController` | `POST /api/ingest`, `POST /api/ingest/incremental`, `GET /api/retrieve` | Triggers ingestion, exposes raw semantic search |
| `QueryController` | `POST /api/query`, `POST /api/ask` | Intent-based retrieval only, or retrieval + LLM synthesis |

### 2. Ingestion Pipeline
`IngestionPipeline` orchestrates the full ingestion flow:
1. Walk the file tree (skips `target/`, `.git/`, `build/`)
2. Dispatch each file to the appropriate `FileProcessor`
3. For Java files: parse AST → extract class/method nodes → build CFG + DFG → create code chunks
4. For other files: YAML → CONFIG chunks, SQL → SCHEMA chunks, Markdown → DOCUMENTATION chunks, OpenAPI → API_SPEC chunks
5. Persist graph nodes to Neo4j via `KnowledgeGraphService`
6. Embed and persist code/doc chunks to pgvector

`IncrementalIngestionService` wraps this with SHA-256 fingerprinting — only added/modified/deleted files are processed (~32× speedup over full re-ingestion).

### 3. Core Analysis Layer

| Component | Description |
|---|---|
| `JavaFileProcessor` | Highest-priority processor. Drives AST parsing via JavaParser, builds all nodes. |
| `CfgBuilder` | Single AST pass → control flow graph per method (11 statement types) |
| `DfgBuilder` | Two-pass variable def/use analysis per method |
| `DocumentationExtractor` | Extracts Javadoc + inline comments + TODO/FIXME markers |
| `RetrievalReranker` | Post-retrieval scoring: metadata heuristics + optional LLM cross-encoder |

### 4. Knowledge Stores

| Store | Technology | What is stored |
|---|---|---|
| Graph | Neo4j (SDN) | Class, Method, CfgNode nodes + 7 relationship types |
| Vector | PostgreSQL + pgvector | Embedded code chunks with metadata (7 chunk types) |

### 5. Retrieval Layer
`IntentBasedRetriever` classifies the user's query into one of 11 intents via regex pattern matching (< 1ms), then routes to the matching handler. Each handler combines vector search, graph traversal, and optional reranking in a way tuned to that intent. See [retrieval-and-intent.md](retrieval-and-intent.md).

### 6. Answer Synthesis
`AnswerSynthesizer` assembles the retrieval context (token-budget-aware, max ~32K chars), selects an intent-aware system prompt from `PromptTemplates`, and calls Groq (`llama-3.3-70b-versatile`) via Spring AI `ChatClient`. Returns a `SynthesisResult` with the answer, intent, model used, and context chunk count.

---

## Technology Stack

| Concern | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| AST Parsing | JavaParser 3.25 |
| Graph DB | Neo4j (Spring Data Neo4j) |
| Vector DB | PostgreSQL + pgvector |
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
JavaParser (AST)
    ├─► ClassNode ──────────────────────────────────► Neo4j
    ├─► MethodNode (+ endpoint/transactional flags) ► Neo4j
    ├─► CfgNode / CfgEdge ─────────────────────────► Neo4j
    ├─► DataFlowNode / DataFlowUse (in-memory only)
    ├─► CodeChunk (method body) ────► Ollama embed ─► pgvector
    └─► DocChunk (Javadoc)      ────► Ollama embed ─► pgvector

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
