# Answer Synthesis

## Overview

After retrieval, ServiceLens assembles the retrieved context, selects an intent-aware system prompt, and calls the Groq LLM to produce a natural-language answer grounded in the actual codebase. This layer is designed to avoid hallucination by short-circuiting with a fallback response when no relevant context was found.

---

## Components

### `AnswerSynthesizer`

Main synthesis orchestrator.

**Flow:**
1. Check if `RetrievalResult` has any context (semantic matches, call chain, callers, endpoints, etc.)
2. If empty → return `SynthesisResult.noContext(intent, confidence)` immediately (avoids hallucination)
3. Call `ContextAssembler.assemble(retrieval)` → formatted context string
4. Select intent-aware system prompt: `PromptTemplates.systemPrompt(intent)`
5. Build user prompt: `PromptTemplates.userPrompt(query, context)`
6. Call Groq via Spring AI `ChatClient.prompt().system(sys).user(user).call().content()`
7. Return `SynthesisResult(answer, intent, confidence, model, chunkCount, synthesized=true)`

If the LLM call throws an exception, logs at WARN and returns a graceful error message (does not propagate the exception to the HTTP layer).

**Config:** `servicelens.groq.model` (default: `llama-3.3-70b-versatile`)

---

### `ContextAssembler`

Assembles the context string passed to the LLM. Enforces a **32,000 character token budget** (≈ 3K tokens), hard-cutting with a `[truncated]` marker if exceeded.

**Context sections (in order):**

| Section | Source | Includes |
|---|---|---|
| Code chunks | `semanticMatches` | file path, element name, lines, chunk type, full content |
| Call chain | `callChain` | method QN, return type, HTTP info if endpoint, Javadoc |
| Callers | `callers` | same as call chain |
| Impacted classes | `impactedClasses` | class QN, spring stereotype, Javadoc |
| HTTP endpoints | `endpointMethods` | HTTP method, path, method QN |

Sections are only included if the corresponding `RetrievalResult` field is non-empty. The budget is consumed in order; once the budget is exhausted, remaining sections are omitted.

---

### `PromptTemplates`

Static class providing intent-aware system prompts.

**Base instruction (applied to all intents):**
- Answer based only on the provided code context
- Reference specific class/method names from the context
- Use markdown formatting (code blocks, headers, bullet points)
- Say "I don't see evidence of that in the provided context" rather than guessing

**Intent-specific guidance (selected examples):**

| Intent | Additional Instruction |
|---|---|
| `FIND_IMPLEMENTATION` | Explain how the implementation works step-by-step |
| `TRACE_CALL_CHAIN` | Describe the execution path in order, including conditional branches |
| `TRACE_CALLERS` | List all callers, describe context in which each calls the method |
| `IMPACT_ANALYSIS` | List what would be affected, explain why each dependency exists |
| `DEBUG_ERROR` | Identify the likely root cause, suggest investigation steps |
| `NULL_SAFETY` | Identify null-unsafe access patterns, suggest fixes |
| `UNDERSTAND_BUSINESS_RULE` | Extract the business rule in plain language, separate from implementation details |
| `FIND_ENDPOINTS` | List endpoints in a table (method, path, description) |

---

### `SynthesisResult`

Immutable record returned by `AnswerSynthesizer`.

| Field | Type | Description |
|---|---|---|
| `answer` | String | The LLM-generated answer (or fallback message) |
| `intent` | `QueryIntent` | Classified intent |
| `intentConfidence` | double | 0.0–1.0 classification confidence |
| `modelUsed` | String | LLM model ID (e.g., `llama-3.3-70b-versatile`) |
| `contextChunksUsed` | int | Number of chunks included in context |
| `synthesized` | boolean | `true` if LLM was called, `false` for fallback |

**Factory methods:**
- `SynthesisResult.noContext(intent, confidence)` — returns canned "no relevant code found" message with `synthesized=false`

---

## LLM Configuration

| Setting | Config Key | Default |
|---|---|---|
| Groq API key | `servicelens.groq.api-key` | `${GROQ_API_KEY}` env var |
| Model | `servicelens.groq.model` | `llama-3.3-70b-versatile` |
| Ollama base URL | `spring.ai.ollama.base-url` | `http://localhost:11434` |
| Chat model (local) | `spring.ai.ollama.chat.model` | `phi3` |
| Temperature | `spring.ai.ollama.chat.options.temperature` | `0.1` |
| Context window | `spring.ai.ollama.chat.options.num-ctx` | `4096` |

Low temperature (`0.1`) is intentional — we want precise, factual answers about code, not creative ones.

---

## Why Groq for Chat and Ollama for Embeddings?

| Concern | Choice | Reason |
|---|---|---|
| Chat/Synthesis | Groq (`llama-3.3-70b-versatile`) | Fast inference (~100 tok/s), large context window, strong code reasoning |
| Embeddings | Ollama (`nomic-embed-text`) | Runs locally, no API cost per query, 768-dim code-optimized model |
| Cross-encoder reranking | Ollama (`phi3`) | Small, fast local model sufficient for 0–10 scoring task |

---

## Synthesis Flow End-to-End

```
POST /api/ask { query: "Who calls processPayment?", serviceName: "order-service" }
                │
                ▼
    IntentClassifier → TRACE_CALLERS (confidence: 0.8)
                │
                ▼
    IntentBasedRetriever (TRACE_CALLERS handler)
      ├─ Vector search CODE: "processPayment" → 3 chunks
      └─ Graph: findCallers("...processPayment(...)") → 8 MethodNodes
                │
                ▼
    RetrievalResult(intent=TRACE_CALLERS, semanticMatches=3, callers=8)
                │
                ▼
    AnswerSynthesizer
      ├─ Context check: non-empty ✓
      ├─ ContextAssembler: assembles 3 code chunks + 8 caller summaries (within 32K budget)
      ├─ System prompt: TRACE_CALLERS template
      └─ Groq LLM call → answer
                │
                ▼
    AskResponse {
      answer: "processPayment is called from 3 places: ...",
      synthesized: true,
      intent: "TRACE_CALLERS",
      intentConfidence: 0.8,
      modelUsed: "llama-3.3-70b-versatile",
      contextChunksUsed: 11
    }
```
