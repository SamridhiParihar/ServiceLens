# Retrieval & Intent System

## Overview

ServiceLens uses **intent-based retrieval** — before searching the knowledge stores, every query is classified into one of 11 intents. The intent determines exactly which combination of vector search, graph traversal, and reranking is applied. This means a question like "who calls processPayment?" triggers a completely different retrieval strategy than "what does the PaymentService do?"

---

## Intent Classification

### `IntentClassifier`

Pattern-based classifier using regex + substring matching. No LLM call required — runs in **< 1ms**.

**How it works:**
1. Query text is matched against a priority-ordered map of patterns (most specific intents first)
2. Each pattern match increments a hit count
3. Confidence = `0.5 + 0.15 × hitCount`, capped at `1.0`
4. Intent with the most pattern hits wins

**Why not LLM-based classification?**
An LLM classifier would add ~500ms latency on every query. Since intents are well-defined engineering concepts with predictable vocabulary, regex is sufficient and ~500× faster.

---

## The 11 Intents

| Intent | Example Query | Primary Signal Words |
|---|---|---|
| `FIND_IMPLEMENTATION` | "How does processOrder work?" | how does, implementation of, show me, what does X do |
| `TRACE_CALL_CHAIN` | "Walk me through the call chain from submitOrder" | call chain, trace, walk through, execution path, flow from |
| `TRACE_CALLERS` | "Who calls processPayment?" | who calls, callers of, called by, what calls |
| `IMPACT_ANALYSIS` | "What breaks if I change OrderService?" | impact, what breaks, depends on, affects, changing X |
| `FIND_CONFIGURATION` | "How is the database configured?" | configuration, config, property, settings, environment |
| `UNDERSTAND_CONTRACT` | "What does the PaymentGateway interface define?" | interface, contract, abstract, overrides, implements |
| `DEBUG_ERROR` | "Why is NullPointerException thrown in checkout?" | error, exception, bug, NPE, fails, throws, broken |
| `NULL_SAFETY` | "Is order ever null-checked before use?" | null, null check, nullable, NPE, null safety |
| `UNDERSTAND_BUSINESS_RULE` | "What is the business rule for discount eligibility?" | business rule, business logic, why, requirement, policy |
| `FIND_ENDPOINTS` | "What REST endpoints does OrderController expose?" | endpoint, API, REST, route, controller, mapping |
| `FIND_TESTS` | "Are there tests for the payment flow?" | test, spec, unit test, integration test, test coverage |

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
