# API Reference

## Base URL
```
http://localhost:8080
```

---

## Ingestion Endpoints

### Ingest (Smart — auto-selects strategy)
```
POST /api/ingest
```

Ingests a service using the strategy automatically selected by `IngestionStrategyResolver`:

| Condition | Strategy | What happens |
|---|---|---|
| Service not yet registered | `FRESH` | Full pipeline runs; service registered on completion |
| Service registered, `force` omitted or `false` | `INCREMENTAL` | Only changed files processed (~32× faster) |
| Service registered, `force=true` | `FORCE_FULL` | Data purged first, then full pipeline runs |

**Request body:**
```json
{
  "repoPath": "/absolute/path/to/your/java/service",
  "serviceName": "my-service",
  "force": "false"
}
```

| Field | Required | Default | Description |
|---|---|---|---|
| `repoPath` | yes | — | Absolute path to the service's root directory |
| `serviceName` | yes | — | Logical name used as the isolation key across all stores |
| `force` | no | `"false"` | Set `"true"` to force a full re-ingest even if the service is already registered |

**Response — FRESH or FORCE_FULL:**
```json
{
  "serviceName": "my-service",
  "totalCodeChunks": 512,
  "totalDocChunks": 89,
  "totalClasses": 42,
  "totalMethods": 318,
  "cfgNodes": 1204,
  "languageBreakdown": {
    "JAVA": 480,
    "CONFIG": 18,
    "DOCUMENTATION": 14
  }
}
```

**Response — INCREMENTAL:**
```json
{
  "serviceName": "my-service",
  "added": 2,
  "modified": 1,
  "deleted": 0,
  "unchanged": 487
}
```

---

## Service Management Endpoints

### List All Services
```
GET /api/services
```

Returns all services currently in the service registry.

**Response:**
```json
[
  {
    "serviceName": "payment-service",
    "repoPath": "/home/dev/payment-service",
    "status": "ACTIVE",
    "ingestedAt": "2026-01-10T08:30:00Z",
    "lastUpdatedAt": "2026-03-20T14:15:00Z",
    "fileCount": 143
  },
  {
    "serviceName": "order-service",
    "repoPath": "/home/dev/order-service",
    "status": "ACTIVE",
    "ingestedAt": "2026-01-12T09:00:00Z",
    "lastUpdatedAt": "2026-03-21T10:00:00Z",
    "fileCount": 87
  }
]
```

---

### Get Single Service
```
GET /api/services/{serviceName}
```

Returns the registry entry for a single service.

**Response — 200:**
```json
{
  "serviceName": "payment-service",
  "repoPath": "/home/dev/payment-service",
  "status": "ACTIVE",
  "ingestedAt": "2026-01-10T08:30:00Z",
  "lastUpdatedAt": "2026-03-20T14:15:00Z",
  "fileCount": 143
}
```

**Response — 404:** Service not registered.

---

### Delete Service
```
DELETE /api/services/{serviceName}
```

Removes a service completely: deletes all Neo4j nodes, pgvector embeddings, file fingerprints, and the registry entry.

**Response — 200:**
```json
{
  "serviceName": "payment-service",
  "message": "Service deleted successfully"
}
```

**Response — 404:** Service not found in the registry; nothing deleted.

---

## Query Endpoints

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

### Intent-Based Retrieval (no LLM)
```
POST /api/query
```

Classifies the query, performs intent-based retrieval from Neo4j + pgvector, and returns structured results without calling the LLM for synthesis.

**Request body:**
```json
{
  "query": "Who calls processPayment?",
  "serviceName": "my-service",
  "sessionId": null
}
```

| Field | Required | Description |
|---|---|---|
| `query` | yes | The natural-language question |
| `serviceName` | yes | The service to query (must be ingested) |
| `sessionId` | no | Session UUID from a prior `/api/ask` response — not used by `/api/query` (retrieval only), but accepted for forward compatibility |

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
  "serviceName": "my-service",
  "sessionId": "a3f9c2d1-..."
}
```

| Field | Required | Description |
|---|---|---|
| `query` | yes | The natural-language question |
| `serviceName` | yes | The service to query (must be ingested) |
| `sessionId` | no | UUID returned by a prior `/api/ask` response. When present the backend resumes the session and injects the last 2 conversation turns as context for the LLM. Omit or pass `null` to start a fresh session. |

**Response:**
```json
{
  "answer": "Starting from `OrderController.submitOrder()` (POST /api/orders), the call chain proceeds as follows:\n\n1. `OrderService.processOrder(Long id)` — validates the order...\n2. `PaymentService.processPayment(Order order)` — charges the customer...\n3. `OrderRepository.save(Order order)` — persists the entity to PostgreSQL.\n\nAll three steps run within a single `@Transactional` boundary on `OrderService.processOrder`.",
  "synthesized": true,
  "intent": "TRACE_CALL_CHAIN",
  "intentConfidence": 0.9,
  "modelUsed": "llama-3.3-70b-versatile",
  "contextChunksUsed": 14,
  "sessionId": "a3f9c2d1-4b8e-4f1a-9c3d-2e5f6a7b8c9d",
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

> **Session management:** The `sessionId` in the response must be persisted by the client (localStorage recommended) and sent back on the next request. The backend silently creates a new session if the provided ID is expired (>30 min inactive) or not found.

**When to use:** Primary endpoint for all end-user queries.

---

### Session History
```
GET /api/sessions/{sessionId}/history
```

Returns the stored conversation turns for a session. Useful for debugging or displaying prior context.

**Response:**
```json
[
  {
    "query": "Who calls processPayment?",
    "intent": "TRACE_CALLERS",
    "answerSummary": "processPayment is called by OrderService.submitOrder and CheckoutService.checkout..."
  },
  {
    "query": "Why does it fail?",
    "intent": "DEBUG_ERROR",
    "answerSummary": "The root cause is a null Order passed to processPayment when the cart is empty..."
  }
]
```

| Field | Description |
|---|---|
| `query` | The original user question for that turn |
| `intent` | Detected intent name |
| `answerSummary` | First 150 characters of the synthesized answer |

Returns an empty array `[]` if the session has no history. Returns `400` for an invalid UUID format.

---

## Service Registry DTOs

### `ServiceRecord`
| Field | Type | Description |
|---|---|---|
| `serviceName` | String | Logical service name (isolation key) |
| `repoPath` | String | Absolute path to the service repo |
| `status` | String | `INGESTING`, `ACTIVE`, `DELETING`, `ERROR` |
| `ingestedAt` | Instant | Timestamp of first successful ingestion |
| `lastUpdatedAt` | Instant | Timestamp of most recent ingestion or update |
| `fileCount` | int | Number of files processed during last ingestion |

---

## Response DTOs (Retrieval)

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

| HTTP Status | Meaning |
|---|---|
| `400 Bad Request` | Missing required fields in request body |
| `404 Not Found` | Service name not found (for service management and query endpoints) |
| `500 Internal Server Error` | Ingestion or LLM call failure |

For `/api/ask`, if the LLM call fails the response will still be `200 OK` but `synthesized` will be `false` and `answer` will contain a graceful error message.
