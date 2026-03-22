# ServiceLens

> AI-powered code intelligence for Java backends. Ask questions about your codebase in plain English — get answers grounded in real code, not hallucinations.

React frontend for [ServiceLens](https://github.com/sha0urya/ServiceLens-ui)

---

## What It Does

ServiceLens ingests your Java service into a hybrid knowledge store — a **Neo4j call graph** and a **pgvector semantic index** — then answers natural-language questions using intent-aware retrieval and LLM synthesis.

| You ask | ServiceLens does |
|---|---|
| "Who calls `processPayment`?" | Reverse-traverses the call graph |
| "Walk me through the flow from `submitOrder`" | BFS forward on `CALLS` edges, depth 5 |
| "What breaks if I change `OrderService`?" | Traverses `DEPENDS_ON` relationships |
| "Is `order` ever null-checked before use?" | DFG analysis + null-pattern filtering |
| "What REST endpoints does this service expose?" | Direct Neo4j query on endpoint metadata |
| "Why does checkout throw a NullPointerException?" | Full hybrid retrieval + LLM cross-encoder reranking |

**No Python. No cloud dependency for inference. Data stays on your machine.**

---

## Architecture

```
Source Code
    │
    ▼
JavaParser (AST) + CfgBuilder + DfgBuilder
    ├─► ClassNode / MethodNode ─────────────────────► Neo4j
    ├─► CfgNode / CFG_EDGE ─────────────────────────► Neo4j
    └─► CodeChunk / DocChunk ──► Ollama embed ───────► pgvector

Query
    │
    ▼
IntentClassifier (< 1ms, regex-based, 11 intents)
    │
    ▼
IntentBasedRetriever
    ├─► pgvector similarity search
    └─► Neo4j graph traversal (CALLS / DEPENDS_ON / IMPLEMENTS)
    │
    ▼
ContextAssembler (32K char budget)
    │
    ▼
Groq LLM (llama-3.3-70b-versatile)
    │
    ▼
Structured Answer (intent + confidence + model + context chunk count)
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 + Spring AI 1.0 |
| AST Parsing | JavaParser 3.25 (Java 18 level) |
| Graph DB | Neo4j (Spring Data Neo4j) |
| Vector DB | PostgreSQL + pgvector (HNSW, cosine) |
| Embeddings | Ollama — `nomic-embed-text` (768 dims, local) |
| LLM Chat | Groq — `llama-3.3-70b-versatile` |
| Local LLM | Ollama — `phi3` (cross-encoder reranking) |
| Spec Parsing | Swagger Parser 2.1 |

---

## Quick Start

**Prerequisites:** Java 21, Maven, PostgreSQL with pgvector, Ollama, Groq API key.

```bash
# 1. Pull embedding and chat models
ollama pull nomic-embed-text
ollama pull phi3

# 2. Set your Groq API key
export GROQ_API_KEY=your_key_here

# 3. Start the application
./mvnw spring-boot:run

# 4. Ingest your Java service
curl -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -d '{"repoPath": "/path/to/your/service", "serviceName": "my-service"}'

# 5. Ask a question
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"query": "Who calls processPayment?", "serviceName": "my-service"}'
```

For subsequent runs after code changes, use incremental ingestion (~32x faster):
```bash
curl -X POST http://localhost:8080/api/ingest/incremental \
  -H "Content-Type: application/json" \
  -d '{"repoPath": "/path/to/your/service", "serviceName": "my-service"}'
```

---

## Configuration

Key settings in `src/main/resources/application.yml`:

```yaml
servicelens:
  data-path: ./servicelens-data     # where file fingerprints are stored
  groq:
    api-key: ${GROQ_API_KEY}         # set via environment variable
    model: llama-3.3-70b-versatile

spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: servicelens
  datasource:
    url: jdbc:postgresql://localhost:5432/servicelens
  ai:
    ollama:
      base-url: http://localhost:11434
      embedding:
        model: nomic-embed-text
```

---

## The 11 Query Intents

ServiceLens classifies every query before retrieval — no LLM call required for classification (regex, < 1ms):

| Intent | Example |
|---|---|
| `FIND_IMPLEMENTATION` | "How does `processOrder` work?" |
| `TRACE_CALL_CHAIN` | "Walk me through the flow from `submitOrder`" |
| `TRACE_CALLERS` | "Who calls `processPayment`?" |
| `IMPACT_ANALYSIS` | "What breaks if I change `OrderService`?" |
| `FIND_CONFIGURATION` | "How is the database configured?" |
| `UNDERSTAND_CONTRACT` | "What does the `PaymentGateway` interface define?" |
| `DEBUG_ERROR` | "Why is NPE thrown in checkout?" |
| `NULL_SAFETY` | "Is `order` ever null-checked before use?" |
| `UNDERSTAND_BUSINESS_RULE` | "What is the business rule for discount eligibility?" |
| `FIND_ENDPOINTS` | "What REST endpoints does this service expose?" |
| `FIND_TESTS` | "Are there tests for the payment flow?" |

---

## What Gets Indexed

For Java files: **AST-parsed class nodes, method nodes, CFG (control flow graph), DFG (data flow analysis), Javadoc** — all persisted to Neo4j + pgvector.

For other file types:

| File | Chunk Type |
|---|---|
| `.yml` / `.yaml` | CONFIG |
| `.sql` | SCHEMA |
| `.md` | DOCUMENTATION |
| OpenAPI/Swagger JSON | API_SPEC |

---

## Documentation

| Doc | Description |
|---|---|
| [Architecture Overview](docs/architecture-overview.md) | Full system design, layer breakdown, data flow |
| [CFG — Control Flow Graph](docs/cfg.md) | How CFG is built, persisted, and queried |
| [DFG — Data Flow Graph](docs/dfg.md) | Variable def/use analysis, null safety, taint tracing |
| [Knowledge Graph](docs/knowledge-graph.md) | Neo4j schema: nodes, relationships, queries |
| [Ingestion Pipeline](docs/ingestion-pipeline.md) | Full + incremental ingestion, file processors |
| [Retrieval & Intent](docs/retrieval-and-intent.md) | Intent classification, 11 retrieval handlers, reranking |
| [Answer Synthesis](docs/answer-synthesis.md) | Context assembly, prompt templates, Groq integration |
| [API Reference](docs/api-reference.md) | All REST endpoints with request/response shapes |
| [Vector Store](docs/vector-store.md) | pgvector setup, chunk types, metadata schema, thresholds |

---

## Key Design Decisions

**Why Neo4j + pgvector instead of just vector search?**
Vector search alone cannot answer structural questions like "what calls this method?" or "what depends on this class?". Graph traversal handles those exactly; vector search handles semantic similarity. The combination covers the full range of engineering questions.

**Why regex intent classification instead of LLM?**
LLM classification adds ~500ms latency on every query. Engineering query intents are well-defined with predictable vocabulary — regex is ~500x faster and just as accurate for this domain.

**Why Groq for chat but Ollama for embeddings?**
Groq provides fast remote inference for the synthesis step (quality matters, one call per query). Ollama provides free, private, local embeddings (called thousands of times during ingestion — cost and privacy both matter).

**Why flat Cypher for CFG persistence?**
Spring Data Neo4j serializes entity graphs recursively, causing `StackOverflowError` on large CFG subgraphs. `CfgSaver` uses `Neo4jClient` directly with a two-pass Cypher strategy to avoid this.

---

*Runs on a single machine. One `./mvnw spring-boot:run`. No cloud required.*
