# Vector Store & Embeddings

## Overview

ServiceLens uses PostgreSQL with the `pgvector` extension as its vector store. Every code chunk and documentation chunk from the ingested codebase is embedded using Ollama's `nomic-embed-text` model and stored with rich metadata. This enables semantic similarity search across the codebase — finding relevant code even when the query uses different vocabulary than the source.

---

## Technology

| Component | Choice | Config Key |
|---|---|---|
| Vector DB | PostgreSQL + pgvector | `spring.datasource.*` |
| Embedding model | `nomic-embed-text` (Ollama) | `spring.ai.ollama.embedding.model` |
| Embedding dimensions | 768 | `spring.ai.vectorstore.pgvector.dimensions` |
| Index type | HNSW | `spring.ai.vectorstore.pgvector.index-type` |
| Distance metric | Cosine | `spring.ai.vectorstore.pgvector.distance-type` |
| Schema init | Automatic | `spring.ai.vectorstore.pgvector.initialize-schema` |

---

## Chunk Types

Every stored document has a `chunk_type` metadata field. This is used for filtering during retrieval.

| Chunk Type | Source Files | What's in it |
|---|---|---|
| `CODE` | `.java` (non-test) | Method body + surrounding comments |
| `TEST` | `.java` (test methods) | Test method body + assertions |
| `CONFIG` | `.yml`, `.yaml`, `.properties` | Configuration key-value blocks |
| `SCHEMA` | `.sql` | DDL statements (CREATE TABLE, indexes, etc.) |
| `API_SPEC` | OpenAPI/Swagger JSON/YAML | Endpoint definitions from spec files |
| `DOCUMENTATION` | Javadoc from `.java`, `.md` files | Human-readable documentation |
| `BUSINESS_CONTEXT` | Markdown docs, business-oriented comments | Business rules and domain context |

---

## Document Metadata Schema

Every document stored in pgvector has these metadata fields:

| Field | Type | Description |
|---|---|---|
| `file_path` | String | Absolute path to the source file |
| `element_name` | String | Method name, class name, or config key |
| `start_line` | int | Starting line in source file |
| `end_line` | int | Ending line in source file |
| `language` | String | `java`, `yaml`, `sql`, `markdown` |
| `file_type` | String | `JAVA`, `YAML`, `SQL`, `MARKDOWN` |
| `chunk_type` | String | One of the 7 chunk types above |
| `service_name` | String | Logical service name (ingestion label) |
| `is_endpoint` | boolean | True if this is an HTTP endpoint method |
| `has_throws_doc` | boolean | True if Javadoc has `@throws` tags |
| `is_transactional` | boolean | True if annotated `@Transactional` |
| `is_scheduled` | boolean | True if annotated `@Scheduled` |
| `is_event_handler` | boolean | True if annotated `@EventListener` |

---

## Similarity Thresholds

Different retrieval methods use different minimum similarity thresholds:

| Method | Threshold | Reason |
|---|---|---|
| `retrieve()` — general | 0.5 | Balanced precision/recall |
| `retrieveCode()` — code only | 0.35 | Lower because natural language queries have vocabulary mismatch with code tokens |
| `retrieveContext()` — business context | 0.4 | Business docs are closer to natural language, can use moderate threshold |

---

## Content Enrichment

When a chunk is converted to a Spring AI `Document` for embedding (`CodeChunk.toDocument()`), the content is **enriched** — metadata labels are prepended to the raw content before embedding:

```
[CODE chunk | OrderService.java | processOrder | lines 42-67]

public Order processOrder(Long id) {
    // validates and processes an order
    ...
}
```

This ensures the embedding captures context about what type of artifact this is, not just the raw code tokens. Without enrichment, two methods with identical bodies but different names would produce identical vectors.

---

## Multi-Service Isolation

All retrieval queries use `FilterExpressionBuilder` to filter by `service_name`. This means:
- Multiple services can be indexed in the same pgvector table
- Queries for `service-A` will never return results from `service-B`
- The filter is applied at the pgvector level, not in application code

Example filter: `service_name == "order-service"`

---

## Batch Embedding Strategy

During ingestion, chunks are embedded in batches (not one-by-one) to avoid overwhelming the local Ollama instance:
- Code chunks and documentation chunks are embedded in separate batches
- Ollama runs `nomic-embed-text` locally — no API cost, no rate limiting

---

## HNSW Index

The pgvector table uses an **HNSW (Hierarchical Navigable Small World)** index for approximate nearest neighbor (ANN) search:
- Much faster than exact KNN for large datasets
- Slight approximation trade-off is acceptable for code search use cases
- Spring AI creates the index automatically on first startup

---

## Storage Sizing (Rough Estimate)

| Per item | Size |
|---|---|
| 768-dim float32 vector | ~3 KB |
| Metadata per document | ~1 KB |
| Total per chunk | ~4 KB |
| 1,000 method codebase | ~4 MB |
| 10,000 method codebase | ~40 MB |

Well within PostgreSQL's comfortable operating range.
