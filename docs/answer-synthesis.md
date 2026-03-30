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

**Base instruction (applied to all intents — universal rules only):**
- Answer based only on the provided code context; never invent classes or methods not shown
- Reference class/method names using inline code formatting (backticks)
- Use **bold** for key terms and class names on first mention
- No filler phrases; technical language for senior developers
- Honour user-requested tone or style (ELI5, story, analogy, etc.)

The base instruction has **no global Response Format section** — format is fully owned by each intent's guidance block.

**Intent-specific format guidance:**

| Intent | Format |
|---|---|
| `FIND_IMPLEMENTATION` | Numbered breakdown: Overview → Step-by-step → Key design decisions → Data flow |
| `TRACE_CALL_CHAIN` | Numbered execution trace with `→` notation; entry point to terminal operation |
| `TRACE_CALLERS` | Flat list — one entry per caller: class/method, when invoked, args passed, return usage |
| `IMPACT_ANALYSIS` | Numbered: direct dependents → transitive impacts → layer breakdown → risk assessment |
| `FIND_CONFIGURATION` | Bullet per property: name, purpose, default, runtime effect |
| `UNDERSTAND_CONTRACT` | Numbered: inputs → outputs → preconditions → postconditions → error cases → side effects |
| `DEBUG_ERROR` | Starts immediately with Root cause — no preamble; numbered: root cause → why → evidence → fix → prevention |
| `NULL_SAFETY` | Numbered: null sources → unchecked paths → risk severity → fixes |
| `UNDERSTAND_BUSINESS_RULE` | Plain English paragraphs only — no headings, no numbered lists; written for a product manager |
| `FIND_ENDPOINTS` | Markdown table always: Method / Path / Auth / Request / Response; grouped by controller |
| `FIND_TESTS` | Numbered: covered scenarios → edge cases → gaps → quality assessment → recommendations |
| `GENERAL_UNDERSTANDING` | Flexible — prose, list, or numbered; no rigid structure; used when intent is uncertain |

**Verbosity levels** override the intent guidance for length:
- `SHORT` — uses a completely separate minimal prompt; no `BASE_SYSTEM`, no intent guidance; 3-5 sentences, no structure
- `DETAILED` — `BASE_SYSTEM` + intent guidance (default)
- `DEEP_DIVE` — `BASE_SYSTEM` + intent guidance + extension block covering edge cases, performance, architecture observations

**Confidence-aware answer modification:**
- **MEDIUM confidence (0.50–0.75):** `QueryController` appends a clarification footer after synthesis:
  *"Detected intent: X — if this missed the mark, try rephrasing your question more specifically."*
- **LOW confidence (< 0.50):** Routing overrides to `GENERAL_UNDERSTANDING` before synthesis; no footer added.
- **HIGH confidence (> 0.75):** Answer returned unchanged.

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
