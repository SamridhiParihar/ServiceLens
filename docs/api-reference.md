# API Reference

## Base URL
```
http://localhost:8080
```

---

## Ingestion Endpoints

### Full Ingestion
```
POST /api/ingest
```

Ingests an entire service from scratch. Purges all existing data for the service name before re-ingesting.

**Request body:**
```json
{
  "repoPath": "/absolute/path/to/your/java/service",
  "serviceName": "my-service"
}
```

**Response:**
```json
{
  "serviceName": "my-service",
  "classCount": 42,
  "methodCount": 318,
  "endpointCount": 24,
  "chunkCount": 512,
  "docChunkCount": 89,
  "status": "SUCCESS"
}
```

**When to use:** First-time ingestion or when you want a clean re-index of the entire service.

---

### Incremental Ingestion
```
POST /api/ingest/incremental
```

Ingests only files that were added, modified, or deleted since the last ingestion. ~32× faster than full ingestion for small changesets.

**Request body:**
```json
{
  "repoPath": "/absolute/path/to/your/java/service",
  "serviceName": "my-service"
}
```

**Response:**
```json
{
  "serviceName": "my-service",
  "added": 2,
  "modified": 1,
  "deleted": 0,
  "skipped": 487,
  "status": "SUCCESS"
}
```

**When to use:** After making code changes locally; wire this into a file-watcher for real-time indexing.

---

### Semantic Search (raw)
```
GET /api/retrieve?query=...&serviceName=...&topK=10
```

Returns raw vector search results without graph expansion or LLM synthesis. Useful for debugging retrieval quality.

**Query parameters:**

| Parameter | Required | Default | Description |
|---|---|---|---|
| `query` | yes | — | Natural language search query |
| `serviceName` | yes | — | Logical service name |
| `topK` | no | 10 | Number of results to return |

**Response:**
```json
[
  {
    "content": "public Order processOrder(Long id) { ... }",
    "metadata": {
      "file_path": "/path/to/OrderService.java",
      "element_name": "processOrder",
      "chunk_type": "CODE",
      "start_line": 42,
      "end_line": 67,
      "service_name": "my-service",
      "is_endpoint": false,
      "is_transactional": true
    },
    "score": 0.87
  }
]
```

---

## Query Endpoints

### Intent-Based Retrieval (no LLM)
```
POST /api/query
```

Classifies the query, performs intent-based retrieval from Neo4j + pgvector, and returns structured results — without calling the LLM for synthesis.

**Request body:**
```json
{
  "query": "Who calls processPayment?",
  "serviceName": "my-service"
}
```

**Response:**
```json
{
  "intent": "TRACE_CALLERS",
  "intentConfidence": 0.85,
  "totalContextSize": 4821,
  "semanticMatches": [
    {
      "elementName": "processPayment",
      "filePath": "src/main/java/.../PaymentService.java",
      "chunkType": "CODE",
      "snippet": "public void processPayment(Order order) { ... }"
    }
  ],
  "callChain": [],
  "callers": [
    {
      "qualifiedName": "com.example.OrderService.submitOrder(Long)",
      "simpleName": "submitOrder",
      "httpMethod": null,
      "endpointPath": null
    }
  ],
  "impactedClasses": [],
  "endpointMethods": []
}
```

**When to use:** When you want to inspect retrieval results before synthesis, or when you want to use your own LLM.

---

### Ask — Retrieval + LLM Synthesis
```
POST /api/ask
```

Full pipeline: intent classification → retrieval → context assembly → Groq LLM synthesis. Returns a natural-language answer grounded in the codebase.

**Request body:**
```json
{
  "query": "Walk me through the call chain from submitOrder to the database",
  "serviceName": "my-service"
}
```

**Response:**
```json
{
  "answer": "Starting from `OrderController.submitOrder()` (POST /api/orders), the call chain proceeds as follows:\n\n1. `OrderService.processOrder(Long id)` — validates the order...\n2. `PaymentService.processPayment(Order order)` — charges the customer...\n3. `OrderRepository.save(Order order)` — persists the entity to PostgreSQL.\n\nAll three steps run within a single `@Transactional` boundary on `OrderService.processOrder`.",
  "synthesized": true,
  "intent": "TRACE_CALL_CHAIN",
  "intentConfidence": 0.9,
  "modelUsed": "llama-3.3-70b-versatile",
  "contextChunksUsed": 14,
  "retrieval": {
    "intent": "TRACE_CALL_CHAIN",
    "intentConfidence": 0.9,
    "totalContextSize": 9234,
    "semanticMatches": [...],
    "callChain": [...],
    "callers": [],
    "impactedClasses": [],
    "endpointMethods": []
  }
}
```

**When to use:** Primary endpoint for all end-user queries.

---

## Response DTOs

### `ChunkView`
| Field | Type |
|---|---|
| `elementName` | String |
| `filePath` | String |
| `chunkType` | String (CODE/TEST/CONFIG/etc.) |
| `snippet` | String (truncated content) |

### `MethodView`
| Field | Type |
|---|---|
| `qualifiedName` | String |
| `simpleName` | String |
| `httpMethod` | String (nullable) |
| `endpointPath` | String (nullable) |
| `isTransactional` | boolean |
| `cyclomaticComplexity` | int |

### `ClassView`
| Field | Type |
|---|---|
| `qualifiedName` | String |
| `simpleName` | String |
| `springStereotype` | String (nullable) |
| `javadocSummary` | String (nullable) |

---

## Error Responses

All endpoints return standard Spring Boot error responses for 4xx/5xx conditions.

| HTTP Status | Meaning |
|---|---|
| `400 Bad Request` | Missing required fields in request body |
| `404 Not Found` | Service name not found (for query endpoints) |
| `500 Internal Server Error` | Ingestion or LLM call failure |

For `/api/ask`, if the LLM call fails the response will still be `200 OK` but `synthesized` will be `false` and `answer` will contain a graceful error message.
