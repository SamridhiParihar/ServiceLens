# Conversation Memory

## Why it exists

Before this feature every query to `/api/ask` was completely stateless. Follow-up questions like:
- *"why is it failing?"*
- *"what happens next?"*
- *"make it shorter"*
- *"remember the auth issue we discussed?"*

…returned nonsense because the LLM had no memory of what was just discussed.

Conversation memory gives ServiceLens short-term and long-distance context so it feels like a conversation, not a sequence of isolated queries.

---

## How it works — Hybrid Memory

The system uses **two complementary layers** assembled by `HybridMemoryAssembler` on every `/api/ask` request:

| Layer | What it does | What it handles |
|---|---|---|
| **Sliding window** | Always injects the last 2 turns | Pronoun resolution ("it", "that", "the third one"), immediate follow-ups |
| **RAG layer** | Embeds the current query, searches past turns by cosine similarity (top-3) | Long-distance recall ("remember the auth issue?", "compare all endpoints we discussed") |

Neither layer alone is sufficient — sliding window misses old context; RAG alone misses pronoun resolution against the most recent exchange.

```
Client                          Backend
  │                                │
  │── POST /api/ask ───────────────▶│  sessionId = null (first request)
  │   { query, serviceName }        │  → create new session in Postgres
  │                                 │  → retrieve code context
  │◀── { answer, sessionId } ───────│  → synthesize (no history yet)
  │                                 │  → store turn in JSONB history
  │                                 │  → embed Q+A → store in pgvector
  │                                 │
  │── POST /api/ask ───────────────▶│  sessionId = "abc-123" (follow-up)
  │   { query, serviceName,         │  → look up session, check TTL
  │     sessionId: "abc-123" }      │  → RAG: embed query → top-3 relevant past turns
  │                                 │  → Sliding window: last 2 turns from JSONB
  │                                 │  → Deduplicate → combine → inject into LLM
  │                                 │  → retrieve code context
  │◀── { answer, sessionId } ───────│  → synthesize with hybrid conversation history
                                    │  → store new turn in JSONB + pgvector
```

---

## Session lifecycle

| Event | Behaviour |
|---|---|
| First request (no `sessionId`) | New session created, UUID returned in response |
| Follow-up with valid `sessionId`, same service | Session resumed, hybrid history assembled and injected |
| `sessionId` found but belongs to a **different service** | New session created for the new service; old session untouched |
| `sessionId` not found in DB | New session created silently |
| Session idle > 30 minutes | Treated as expired, new session created |
| Invalid UUID format | `400 Bad Request` |

---

## Storage

### `conversation_sessions` — session state and recent history

```sql
CREATE TABLE conversation_sessions (
    session_id      UUID                     PRIMARY KEY,
    service_name    VARCHAR(255)             NOT NULL,
    history         JSONB                    NOT NULL DEFAULT '[]',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_active_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
```

The `history` column is a JSONB array of turn objects (capped at 5):
```json
[
  { "query": "...", "intent": "FIND_IMPLEMENTATION", "answerSummary": "...", "verbosity": "DETAILED" },
  { "query": "...", "intent": "TRACE_CALLERS",       "answerSummary": "...", "verbosity": "SHORT" }
]
```

### `conversation_turn_embeddings` — RAG memory index

```sql
CREATE TABLE conversation_turn_embeddings (
    session_id   UUID        NOT NULL,
    turn_index   INT         NOT NULL,
    query_text   TEXT        NOT NULL,
    answer_text  TEXT        NOT NULL,
    embedding    vector(768) NOT NULL,
    PRIMARY KEY (session_id, turn_index)
);

CREATE INDEX idx_turn_embeddings_hnsw
    ON conversation_turn_embeddings
    USING hnsw (embedding vector_cosine_ops);
```

`turn_index` is the 0-based position of the turn within the session. The composite primary key `(session_id, turn_index)` uniquely identifies one turn, enables deduplication between the RAG and sliding window layers, and makes upserts idempotent.

**Embedding model:** `nomic-embed-text` via Ollama (768 dimensions, same model used for code chunks).
**Embedded text:** `"Q: {query}\nA: {answerSummary}"` — captures both intent and content for richer semantic matching.

---

## Context injection

`HybridMemoryAssembler.assemble()` combines both layers and passes the result to `ContextAssembler.assembleWithHistory()`:

```
=== CONVERSATION HISTORY ===

Q: Who calls processPayment?                   ← RAG-retrieved (turn 1, semantically relevant)
A: processPayment is called by OrderService...

Q: Why does it fail there?                     ← Sliding window (turn N-1)
A: The root cause is a null Order being...

Q: How do I fix the null check?                ← Sliding window (turn N, most recent)
A: Add a Objects.requireNonNull before...

=== RELEVANT CODE CHUNKS ===
--- Chunk 1 [PaymentService.processPayment / CODE] ---
...
```

Order: oldest-relevant first, most-recent last — matching the natural reading order of a conversation.

---

## Per-service session isolation

Sessions are scoped to a single service at two levels:

**Frontend** — sessions are keyed per service in localStorage:
```
localStorage key: sl-session-{serviceName}
```
Switching service in the UI automatically picks up a different key, so a different (or null) sessionId is sent.

**Backend** — `ConversationSessionService.getOrCreate()` validates that the found session's `service_name` matches the incoming request. If it doesn't match, the backend silently creates a new session for the correct service.

The RAG search in `ConversationTurnEmbeddingStore.findRelevant()` is always scoped by `session_id`, so embeddings from one service's session can never surface in another service's answers.

---

## Design decisions

- **JSONB for recent history** — simpler schema, no joins, the entire sliding window is one read. Works well for the small fixed window (max 5 turns).
- **Separate pgvector table for embeddings** — keeps code chunk embeddings (`spring_ai_vector_store`) cleanly separated from conversation turn embeddings. Avoids metadata filter complexity and table bloat.
- **Composite PK `(session_id, turn_index)`** — turn_index carries chronological meaning, enables deduplication, and makes upserts safe (`ON CONFLICT DO UPDATE`).
- **JdbcTemplate over JPA** — avoids transaction-manager conflicts with Neo4j's `@Primary` TX manager.
- **400-char summary cap** — keeps JSONB history compact; ~800 chars for 2 sliding window turns is negligible in a 32 000-char context budget. Full answer is always returned to the client.
- **Max 5 turns in JSONB, 2 injected as sliding window** — 5 stored gives the RAG layer enough history to embed; only 2 injected via sliding window keeps the prompt tight.
- **Fault isolation in embedding store** — all errors caught and logged as warnings. A failed embedding write degrades to sliding-window-only; a failed RAG read returns empty list. The answer pipeline always continues.

---

## Key files

| File | Role |
|---|---|
| `session/ConversationTurn.java` | Single turn: query + intent + answerSummary + verbosity |
| `session/ConversationSession.java` | Session record: sessionId + serviceName + history + timestamps |
| `session/ConversationSessionRepository.java` | JDBC-backed Postgres operations; `appendTurn()` returns turn index |
| `session/ConversationSessionService.java` | Lifecycle facade: getOrCreate, addTurn (stores JSONB + embedding), getHistory |
| `session/ConversationTurnEmbeddingStore.java` | Embeds Q+A text via Ollama, stores/queries `conversation_turn_embeddings` |
| `session/HybridMemoryAssembler.java` | RAG retrieval + sliding window + deduplication → combined history list |
| `synthesis/ContextAssembler.java` | `assembleWithHistory()` injects hybrid turns before code context |
| `retrieval/QueryController.java` | Session resolution, hybrid memory assembly, turn persistence per request |
| `config/PostgresSchemaInitializer.java` | Creates `conversation_sessions` and `conversation_turn_embeddings` on startup |
| `src/api/servicelens.js` (UI) | Reads/writes `sl-session-{serviceName}` in localStorage |
| `src/pages/AskPage.jsx` (UI) | Clears message history when service name changes |
