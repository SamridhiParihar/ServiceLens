# Retrieval & Intent System

## Overview

ServiceLens uses **intent-based retrieval** — before searching the knowledge stores, every query is classified into one of 12 intents. The intent determines exactly which combination of vector search, graph traversal, and reranking is applied. This means a question like "who calls processPayment?" triggers a completely different retrieval strategy than "what does the PaymentService do?"

---

## Intent Classification

### `IntentClassifier`

Pattern-based classifier using regex + substring matching. No LLM call required — runs in **< 1ms**.

**How it works:**
1. Query text is matched against a priority-ordered map of patterns (most specific intents first)
2. Each pattern match increments a hit count
3. Confidence formula:
   - **0 matches** → `0.30` (LOW — no signal, pure default fallback)
   - **1 match** → `0.65` (MEDIUM — one signal, probably right)
   - **2+ matches** → `0.80+` (HIGH — multiple signals, confident), capped at `1.0`
4. The first-matched intent wins (priority ordering ensures specificity)

**Why not LLM-based classification?**
An LLM classifier would add ~500ms latency on every query. Since intents are well-defined engineering concepts with predictable vocabulary, regex is sufficient and ~500× faster.

---

## The 12 Intents

| Intent | Example Query | Primary Signal Words |
|---|---|---|
| `FIND_IMPLEMENTATION` | "How does processOrder work?" | how does, show me, where is, which class, how is implemented |
| `TRACE_CALL_CHAIN` | "What is the payment flow?" | call chain, flow, execution flow, execution path, walk through, what happens, downstream |
| `TRACE_CALLERS` | "Who calls processPayment?" | who calls, callers of, what calls, where is called, where does X get called, who invokes |
| `IMPACT_ANALYSIS` | "What breaks if I change OrderService?" | impact, what breaks, depends on, who uses, if I change |
| `FIND_CONFIGURATION` | "How is the database configured?" | configuration, config, property, settings, timeout, limit |
| `UNDERSTAND_CONTRACT` | "What does the PaymentGateway interface define?" | interface, contract, abstract, overrides, implements |
| `DEBUG_ERROR` | "The payment service is not working" | error, exception, failing, broken, not working, isn't working, doesn't work, NPE, throws |
| `NULL_SAFETY` | "Is order ever null-checked before use?" | null, null check, nullable, NPE, null safety |
| `UNDERSTAND_BUSINESS_RULE` | "What is the business rule for discount eligibility?" | business rule, business logic, why, requirement, policy |
| `FIND_ENDPOINTS` | "What REST endpoints does OrderController expose?" | endpoint, apis, REST, route, controller, mapping, what urls |
| `FIND_TESTS` | "Are there tests for the payment flow?" | test, spec, unit test, integration test, test coverage |
| `GENERAL_UNDERSTANDING` | *(injected automatically — never matched by patterns)* | N/A — triggered when confidence < 0.5 |

> **Note:** `GENERAL_UNDERSTANDING` is never returned directly by `IntentClassifier`. It is injected by `IntentBasedRetriever` when the confidence score falls below `0.50` — meaning no specific intent could be determined reliably.

---

## Intent Routing — What Each Handler Does

### `FIND_IMPLEMENTATION`
1. Vector search: CODE chunks, topK=20, threshold=0.35
2. Metadata rerank: boost CODE chunks
3. Return: `semanticMatches` (top 8)

### `TRACE_CALL_CHAIN`
1. Vector search: CODE chunks, topK=3 to find entry method
2. For each result: `KnowledgeGraphService.findCallChain(methodQN, serviceName)` — BFS forward on `CALLS` edges, depth 5
3. Return: `semanticMatches` + `callChain`

### `TRACE_CALLERS`
1. Vector search: CODE chunks, topK=3 to find target method
2. For each result: `KnowledgeGraphService.findCallers(methodQN)` — reverse traversal on `CALLS` edges
3. Return: `semanticMatches` + `callers`

### `IMPACT_ANALYSIS`
1. Vector search: CODE chunks, topK=3 to find the class/method in question
2. For each class found: `KnowledgeGraphService.findDependents(classQN, serviceName)` — reverse `DEPENDS_ON` traversal
3. Return: `semanticMatches` + `impactedClasses`

### `FIND_CONFIGURATION`
1. Vector search: ALL chunk types, topK=20
2. Metadata rerank: boost CONFIG chunks to top
3. Return: `semanticMatches` (CONFIG first)

### `UNDERSTAND_CONTRACT`
1. Vector search: CODE chunks, topK=10
2. Graph: `findImplementors(interfaceQN)` + `findSubclasses(classQN)`
3. Return: `semanticMatches` + `contractNodes` (interface/abstract class details)

### `DEBUG_ERROR`
1. Vector search: CODE chunks, topK=15
2. Graph: callers + call chain (bidirectional)
3. Full rerank: metadata stage + LLM cross-encoder stage (~100ms/chunk)
4. Return: `semanticMatches` + `callChain` + `callers` (fully reranked)

### `NULL_SAFETY`
1. Vector search: CODE chunks only, topK=10
2. Post-filter: only chunks containing null-related patterns (`== null`, `!= null`, `Optional`, `@NonNull`, etc.)
3. Return: `semanticMatches` (null-pattern-filtered)

### `UNDERSTAND_BUSINESS_RULE`
1. Vector search: BUSINESS_CONTEXT chunks only, topK=15, threshold=0.4
2. No graph expansion
3. Return: `semanticMatches`

### `FIND_ENDPOINTS`
1. Direct graph query: `KnowledgeGraphService.findEndpoints(serviceName)` — no vector search
2. Return: `endpointMethods` only

### `FIND_TESTS`
1. Vector search: TEST chunks only, topK=15
2. Return: `semanticMatches`

### `GENERAL_UNDERSTANDING` (low-confidence fallback)
1. Broad vector search: ALL chunk types, topK=20, threshold=0.35
2. Metadata rerank: top 10 returned
3. No graph traversal — maximise context breadth
4. Return: `semanticMatches`

---

## Confidence-aware Routing

`IntentBasedRetriever` applies three different behaviours based on the confidence tier:

| Tier | Range | Behaviour |
|---|---|---|
| **HIGH** | > 0.75 | Route to classified intent; answer returned as-is |
| **MEDIUM** | 0.50 – 0.75 | Route to classified intent; a clarification footer is appended to the answer: *"Detected intent: X — if this missed the mark, try rephrasing…"* |
| **LOW** | < 0.50 | Override intent to `GENERAL_UNDERSTANDING`; broad retrieval, general-purpose prompt |

The confidence footer for MEDIUM tier is added in `QueryController` (not the retriever), so it appears in the final `/api/ask` response but is stored in session history.

### `IntentClassificationResult`

Standalone record wrapping the classification output:

```java
record IntentClassificationResult(QueryIntent intent, float confidence) {
    boolean isHigh()   // > 0.75
    boolean isMedium() // 0.50 – 0.75
    boolean isLow()    // < 0.50
}
```

---

## `RetrievalResult`

Immutable record returned by `IntentBasedRetriever`. Fields:

| Field | Type | Description |
|---|---|---|
| `intent` | `QueryIntent` | Classified intent |
| `intentConfidence` | double | 0.0–1.0 classification confidence |
| `semanticMatches` | `List<Document>` | Ranked vector search results |
| `callChain` | `List<MethodNode>` | Forward call traversal |
| `callers` | `List<MethodNode>` | Reverse call traversal |
| `impactedClasses` | `List<ClassNode>` | Classes depending on changed class |
| `contractNodes` | `List<ClassNode>` | Interface implementors / subclasses |
| `dataFlows` | `List<MethodDataFlow>` | DFG results (for null safety queries) |
| `endpointMethods` | `List<MethodNode>` | HTTP endpoint methods |

Convenience factory methods: `semantic()`, `withCallChain()`, `withCallers()`, `withImpact()`, `endpoints()`. Builder pattern for incremental construction. `totalContextSize()` returns the aggregate character count of all context.

---

## Hybrid Retrieval

`HybridRetriever` is a lower-level component used internally by some intent handlers. It adds **graph expansion** on top of vector results:

1. **Vector search** — finds semantically relevant code chunks
2. **Graph expansion** — for each method found in vector results, fetches its callers + call chain
3. **Deduplication** — removes duplicate methods by qualified name

This ensures that even if a key method is not directly in the top vector results (e.g., low similarity score), it is still included if it is connected to a highly relevant method in the graph.

---

## Semantic Search Details (`CodeRetriever`)

| Method | Chunk Types | Threshold | Notes |
|---|---|---|---|
| `retrieve(query, serviceName, topK)` | All | 0.5 | Balanced |
| `retrieveCode(query, serviceName, topK)` | CODE only | 0.35 | Lower threshold because code-English vocabulary gap means even relevant results score lower |
| `retrieveContext(query, serviceName, topK)` | BUSINESS_CONTEXT only | 0.4 | For "why" questions |

All methods use `FilterExpressionBuilder` to filter by `service_name` metadata field, ensuring multi-service deployments don't cross-contaminate results.

---

## Reranking (`RetrievalReranker`)

Two-stage pipeline applied after initial retrieval:

### Stage 1 — Metadata Reranking (< 1ms)
Adjusts vector similarity scores based on chunk type vs. query intent.

Examples:
- CONFIG query + CONFIG chunk → score boosted
- FIND_TESTS query + TEST chunk → score boosted
- DEBUG_ERROR query + CODE chunk with `has_throws_doc=true` → score boosted

### Stage 2 — Cross-Encoder Reranking (~100ms/chunk)
Only applied for `DEBUG_ERROR` intent (high-stakes queries where precision matters most).
1. For each candidate chunk, calls Ollama (`phi3`) with prompt: "Score relevance of this code to query on 0–10 scale"
2. Parse score from response
3. Blend: `finalScore = 0.6 × llmScore + 0.4 × vectorScore`
4. Re-sort by final score, return top-K

Available methods: `metadataRerank()`, `crossEncoderRerank()`, `fullRerank()`, `boostByType()`
