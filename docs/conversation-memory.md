# Conversation Memory

## Why it exists

Before this feature every query to `/api/ask` was completely stateless. Follow-up questions like:
- *"why is it failing?"*
- *"what happens next?"*
- *"make it shorter"*

…returned nonsense because the LLM had no memory of what was just discussed.

Conversation memory gives ServiceLens short-term context so it feels like a conversation, not a sequence of isolated queries.

---

## How it works

```
Client                          Backend
  │                                │
  │── POST /api/ask ───────────────▶│  sessionId = null (first request)
  │   { query, serviceName }        │  → create new session in Postgres
  │                                 │  → retrieve code context
  │◀── { answer, sessionId } ───────│  → synthesize (no history yet)
  │                                 │  → store turn (query + intent + 150-char summary)
  │                                 │
  │── POST /api/ask ───────────────▶│  sessionId = "abc-123" (follow-up)
  │   { query, serviceName,         │  → look up session, check TTL
  │     sessionId: "abc-123" }      │  → inject last 2 turns into LLM context
  │                                 │  → retrieve code context
  │◀── { answer, sessionId } ───────│  → synthesize with conversation history
                                    │  → store new turn
```

---

## Session lifecycle

| Event | Behaviour |
|---|---|
| First request (no `sessionId`) | New session created, UUID returned in response |
| Follow-up with valid `sessionId`, same service | Session resumed, last 2 turns injected as history |
| `sessionId` found but belongs to a **different service** | New session created for the new service; old session untouched |
| `sessionId` not found in DB | New session created silently |
| Session idle > 30 minutes | Treated as expired, new session created |
| Invalid UUID format | `400 Bad Request` |

---

## Storage

Sessions are stored in the `conversation_sessions` Postgres table (same container as `vector_store` and `service_registry`):

```sql
CREATE TABLE conversation_sessions (
    session_id      UUID                     PRIMARY KEY,
    service_name    VARCHAR(255)             NOT NULL,
    history         JSONB                    NOT NULL DEFAULT '[]',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_active_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
```

The `history` column is a JSONB array of turn objects:
```json
[
  { "query": "...", "intent": "FIND_IMPLEMENTATION", "answerSummary": "...", "verbosity": "DETAILED" },
  { "query": "...", "intent": "TRACE_CALLERS",       "answerSummary": "...", "verbosity": "SHORT" }
]
```

**Design decisions:**
- **JSONB over a separate turns table** — simpler schema, no joins, the entire history is one read
- **JdbcTemplate over JPA** — avoids transaction-manager conflicts with Neo4j's `@Primary` TX manager
- **150-char summary, not full answer** — keeps history compact; ~300 chars total for 2 turns is negligible in a 32 000-char context budget
- **Max 5 turns per session** — old turns are dropped from the tail as new ones are appended; only the last 2 are injected into the LLM context

---

## Context injection

The last 2 turns are prepended to the code context by `ContextAssembler.assembleWithHistory()`:

```
=== CONVERSATION HISTORY ===

Q: Who calls processPayment?
A: processPayment is called by OrderService.submitOrder and...

Q: Why does it fail there?
A: The root cause is a null Order being passed when the cart...

=== RELEVANT CODE CHUNKS ===
--- Chunk 1 [PaymentService.processPayment / CODE] ---
public void processPayment(Order order) { ...
```

The LLM sees the prior exchange before it sees the new code context, so references like "it" and "there" resolve correctly.

---

## Per-service session isolation

Sessions are scoped to a single service at two levels:

**Frontend** — sessions are keyed per service in localStorage:
```
localStorage key: sl-session-{serviceName}
```
Switching service in the UI automatically picks up a different key, so a different (or null) sessionId is sent.

**Backend** — `ConversationSessionService.getOrCreate()` validates that the found session's `service_name` matches the incoming request. If it doesn't match (e.g. a direct API call sends a sessionId from a different service), the backend silently creates a new session for the correct service. The mismatched session is left untouched in Postgres.

This means there is no way for conversation history from one service to bleed into queries about another service, regardless of how the client sends the sessionId.

---

## Key files

| File | Role |
|---|---|
| `session/ConversationTurn.java` | Single turn: query + intent + answerSummary + verbosity |
| `session/ConversationSession.java` | Session record: sessionId + serviceName + history + timestamps |
| `session/ConversationSessionRepository.java` | JDBC-backed Postgres operations |
| `session/ConversationSessionService.java` | Lifecycle facade: getOrCreate, addTurn, getHistory |
| `synthesis/ContextAssembler.java` | `assembleWithHistory()` injects turns before code context |
| `synthesis/AnswerSynthesizer.java` | Passes history to ContextAssembler on every `/api/ask` call |
| `retrieval/QueryController.java` | Session resolution + turn persistence per request |
| `config/PostgresSchemaInitializer.java` | Creates `conversation_sessions` table on startup |
| `src/api/servicelens.js` (UI) | Reads/writes `sl-session-{serviceName}` in localStorage |
| `src/pages/AskPage.jsx` (UI) | Clears message history when service name changes |
