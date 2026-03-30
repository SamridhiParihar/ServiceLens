package com.servicelens.retrieval.intent;

/**
 * All supported query intents.
 *
 * Each intent maps to a different retrieval strategy in IntentBasedRetriever.
 * The strategy determines: which stores to query, which filters to apply,
 * whether to use graph traversal, and how to rerank results.
 */
public enum QueryIntent {

    /**
     * "Where is X implemented?"
     * "Which class handles X?"
     * "How does X work?"
     *
     * Strategy: Vector search on CODE chunks, metadata rerank
     * Graph: DEFINES traversal to get class context
     */
    FIND_IMPLEMENTATION,

    /**
     * "What does processPayment trigger?"
     * "What happens when X is called?"
     * "What is the call chain for X?"
     *
     * Strategy: Vector finds entry point, graph traverses CALLS outward
     * Depth: 3-5 hops
     */
    TRACE_CALL_CHAIN,

    /**
     * "Who calls processPayment?"
     * "Where is validateOrder called from?"
     * "What are the callers of X?"
     *
     * Strategy: Vector finds target method, graph traverses CALLS in reverse
     * This is the upstream direction (as opposed to TRACE_CALL_CHAIN which is downstream)
     */
    TRACE_CALLERS,

    /**
     * "What breaks if I change UserService?"
     * "What depends on PaymentGatewayClient?"
     * "Impact of changing X?"
     *
     * Strategy: Vector finds the target class, graph traverses DEPENDS_ON in reverse
     * Returns: all classes that inject/use the target
     */
    IMPACT_ANALYSIS,

    /**
     * "How is timeout configured?"
     * "What is the retry limit?"
     * "Where is database URL set?"
     *
     * Strategy: Vector search on ALL chunks, CONFIG chunks boosted heavily
     * Reranker: metadata boost for CONFIG/YAML/PROPERTIES types
     */
    FIND_CONFIGURATION,

    /**
     * "What interface does PaymentService implement?"
     * "What method does processPayment override?"
     * "What is the contract for X?"
     *
     * Strategy: Graph traverses IMPLEMENTS and OVERRIDES relationships
     * Vector: finds the class/method, then graph expands
     */
    UNDERSTAND_CONTRACT,

    /**
     * "Payment is throwing NullPointerException"
     * "Why is X failing with Y error?"
     * "Connection refused to gateway"
     *
     * Strategy: FULL HYBRID — vector + graph + full reranking
     * This is the most comprehensive retrieval mode.
     * Cross-encoder reranking enabled.
     */
    DEBUG_ERROR,

    /**
     * "Could order be null here?"
     * "Is there a null check before X?"
     * "Where is null checked for Y?"
     *
     * Strategy: DFG analysis — find variable DEF/USE chain
     * Vector: find relevant method
     * DFG: trace variable from definition through all uses
     */
    NULL_SAFETY,

    /**
     * "Why does payment retry 3 times?"
     * "What is the SLA for payment processing?"
     * "Why is this limit set to 5?"
     *
     * Strategy: BUSINESS_CONTEXT chunks only (from context.md)
     * Vector: restricted to chunk_type = BUSINESS_CONTEXT
     * No graph traversal needed
     */
    UNDERSTAND_BUSINESS_RULE,

    /**
     * "What REST endpoints does this service expose?"
     * "Show me all GET endpoints"
     * "What APIs are available?"
     *
     * Strategy: Direct graph query for endpoint metadata
     * Uses: MethodNodeRepository.findEndpoints()
     * Also: API_SPEC chunks from OpenAPI processor
     */
    FIND_ENDPOINTS,

    /**
     * "Show me tests for processPayment"
     * "What unit tests exist for X?"
     *
     * Strategy: Vector search restricted to chunk_type = TEST
     */
    FIND_TESTS,

    /**
     * Fallback intent used when classifier confidence is LOW (&lt;0.5).
     *
     * <p>No patterns matched the query with sufficient certainty, so the classifier
     * cannot commit to a specific retrieval strategy. This intent triggers a broad
     * vector search across all chunk types, giving the LLM maximum context and
     * a general-purpose explanation prompt.
     *
     * <p>This intent is never returned directly by {@link IntentClassifier} — it is
     * injected by {@link IntentBasedRetriever} when the confidence score is too low.
     */
    GENERAL_UNDERSTANDING
}