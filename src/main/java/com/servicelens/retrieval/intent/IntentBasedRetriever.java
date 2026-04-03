package com.servicelens.retrieval.intent;

import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.repository.MethodNodeRepository;
import com.servicelens.reranking.RetrievalReranker;
import com.servicelens.retrieval.CodeRetriever;
import com.servicelens.retrieval.HybridRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The unified retrieval entry point.
 *
 * This is the ONLY class agents should call for retrieval.
 * It routes each query to the optimal strategy based on intent.
 *
 * ROUTING TABLE:
 * ──────────────
 *  Intent                  │ Vector  │ Graph          │ Reranker
 * ─────────────────────────┼─────────┼────────────────┼──────────────
 *  FIND_IMPLEMENTATION     │ CODE    │ DEFINES        │ metadata
 *  TRACE_CALL_CHAIN        │ CODE(3) │ CALLS ↓ depth5 │ none
 *  TRACE_CALLERS           │ CODE(3) │ CALLS ↑ rev    │ none
 *  IMPACT_ANALYSIS         │ CODE(3) │ DEPENDS_ON ↑   │ none
 *  FIND_CONFIGURATION      │ ALL(20) │ none           │ metadata+CONFIG
 *  UNDERSTAND_CONTRACT     │ CODE(3) │ IMPL+OVERRIDE  │ none
 *  DEBUG_ERROR             │ ALL(15) │ CALLS ↓+↑      │ full (LLM)
 *  NULL_SAFETY             │ CODE(5) │ none           │ metadata
 *  UNDERSTAND_BUSINESS     │ BIZ(10) │ none           │ none
 *  FIND_ENDPOINTS          │ none    │ direct query   │ none
 *  FIND_TESTS              │ TEST(8) │ none           │ metadata
 */
@Service
public class IntentBasedRetriever {

    private static final Logger log = LoggerFactory.getLogger(IntentBasedRetriever.class);

    private final IntentClassifier classifier;
    private final CodeRetriever vectorRetriever;
    private final HybridRetriever hybridRetriever;
    private final KnowledgeGraphService graphService;
    private final MethodNodeRepository methodRepo;
    private final RetrievalReranker reranker;
    private final VectorStore vectorStore;

    public IntentBasedRetriever(
            IntentClassifier classifier,
            CodeRetriever vectorRetriever,
            HybridRetriever hybridRetriever,
            KnowledgeGraphService graphService,
            MethodNodeRepository methodRepo,
            RetrievalReranker reranker,
            VectorStore vectorStore) {
        this.classifier       = classifier;
        this.vectorRetriever  = vectorRetriever;
        this.hybridRetriever  = hybridRetriever;
        this.graphService     = graphService;
        this.methodRepo       = methodRepo;
        this.reranker         = reranker;
        this.vectorStore      = vectorStore;
    }

    /**
     * Primary entry point.
     * Classify the query, apply confidence-based routing, return rich context.
     *
     * <p>Routing tiers:
     * <ul>
     *   <li>HIGH confidence (&gt;0.75)  — route to the classified intent as normal</li>
     *   <li>MEDIUM confidence (0.5–0.75) — route normally; {@link QueryController} will
     *       append a clarification footer to the answer</li>
     *   <li>LOW confidence (&lt;0.5)    — override to {@link QueryIntent#GENERAL_UNDERSTANDING};
     *       broad vector search with a general-purpose prompt</li>
     * </ul>
     */
    public RetrievalResult retrieve(String query, String serviceName) {
        IntentClassificationResult intentResult = classifier.classifyWithConfidence(query);
        QueryIntent intent = intentResult.intent();
        float confidence   = intentResult.confidence();

        // LOW confidence: no pattern matched reliably — use broad fallback
        if (intentResult.isLow()) {
            log.info("Low confidence ({}%) for query '{}' — routing to GENERAL_UNDERSTANDING",
                    (int)(confidence * 100), truncate(query, 60));
            intent = QueryIntent.GENERAL_UNDERSTANDING;
        } else {
            log.info("Query: '{}' → intent: {} ({}%)",
                    truncate(query, 60), intent, (int)(confidence * 100));
        }

        return switch (intent) {
            case FIND_IMPLEMENTATION   -> handleFindImplementation(query, serviceName, confidence);
            case TRACE_CALL_CHAIN      -> handleTraceCallChain(query, serviceName, confidence);
            case TRACE_CALLERS         -> handleTraceCallers(query, serviceName, confidence);
            case IMPACT_ANALYSIS       -> handleImpactAnalysis(query, serviceName, confidence);
            case FIND_CONFIGURATION    -> handleFindConfiguration(query, serviceName, confidence);
            case UNDERSTAND_CONTRACT   -> handleUnderstandContract(query, serviceName, confidence);
            case DEBUG_ERROR           -> handleDebugError(query, serviceName, confidence);
            case NULL_SAFETY           -> handleNullSafety(query, serviceName, confidence);
            case UNDERSTAND_BUSINESS_RULE -> handleBusinessRule(query, serviceName, confidence);
            case FIND_ENDPOINTS        -> handleFindEndpoints(serviceName, confidence);
            case FIND_TESTS            -> handleFindTests(query, serviceName, confidence);
            case GENERAL_UNDERSTANDING -> handleGeneralUnderstanding(query, serviceName, confidence);
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTENT HANDLERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FIND_IMPLEMENTATION
     * "Where is X?" / "How does X work?"
     * Pure vector search on CODE chunks, metadata reranked.
     */
    private RetrievalResult handleFindImplementation(
            String query, String serviceName, float confidence) {

        List<Document> candidates = vectorRetriever.retrieveCode(query, serviceName, 20);
        List<Document> reranked   = reranker.metadataRerank(query, candidates, 8);

        log.debug("FIND_IMPLEMENTATION: {} candidates → {} after rerank",
                candidates.size(), reranked.size());

        return RetrievalResult.semantic(QueryIntent.FIND_IMPLEMENTATION, confidence, reranked);
    }

    /**
     * TRACE_CALL_CHAIN
     * "What does processPayment trigger?"
     * Find entry method by vector, then expand CALLS relationships outward.
     */
    private RetrievalResult handleTraceCallChain(
            String query, String serviceName, float confidence) {

        // Step 1: Find the entry method
        List<Document> entryPoints = vectorRetriever.retrieveCode(query, serviceName, 3);

        // Step 2: For each found method, traverse the call chain
        List<MethodNode> callChain = entryPoints.stream()
                .map(doc -> buildApproxQN(doc, serviceName))
                .filter(Objects::nonNull)
                .flatMap(qn -> graphService.findCallChain(qn, serviceName).stream())
                .distinct()
                .limit(15)
                .collect(Collectors.toList());

        log.debug("TRACE_CALL_CHAIN: {} entry points → {} chain nodes",
                entryPoints.size(), callChain.size());

        return RetrievalResult.withCallChain(
                QueryIntent.TRACE_CALL_CHAIN, confidence, entryPoints, callChain);
    }

    /**
     * TRACE_CALLERS
     * "Who calls validateOrder?"
     * Find target method, then traverse CALLS in reverse.
     */
    private RetrievalResult handleTraceCallers(
            String query, String serviceName, float confidence) {

        List<Document> targetDocs = vectorRetriever.retrieveCode(query, serviceName, 3);

        List<MethodNode> callers = targetDocs.stream()
                .map(doc -> buildApproxQN(doc, serviceName))
                .filter(Objects::nonNull)
                .flatMap(qn -> graphService.findCallers(qn).stream())
                .distinct()
                .limit(15)
                .collect(Collectors.toList());

        log.debug("TRACE_CALLERS: {} targets → {} callers", targetDocs.size(), callers.size());

        return RetrievalResult.withCallers(
                QueryIntent.TRACE_CALLERS, confidence, targetDocs, callers);
    }

    /**
     * IMPACT_ANALYSIS
     * "What breaks if I change UserService?"
     * Find target class, traverse DEPENDS_ON in reverse.
     */
    private RetrievalResult handleImpactAnalysis(
            String query, String serviceName, float confidence) {

        List<Document> targetDocs = vectorRetriever.retrieveCode(query, serviceName, 3);

        List<ClassNode> impacted = targetDocs.stream()
                .map(doc -> {
                    String className = getMetaString(doc, "class_name");
                    return className != null ? className : null;
                })
                .filter(Objects::nonNull)
                .flatMap(className ->
                        graphService.findDependents(className, serviceName).stream())
                .distinct()
                .limit(20)
                .collect(Collectors.toList());

        log.debug("IMPACT_ANALYSIS: {} targets → {} impacted classes",
                targetDocs.size(), impacted.size());

        return RetrievalResult.withImpact(
                QueryIntent.IMPACT_ANALYSIS, confidence, targetDocs, impacted);
    }

    /**
     * FIND_CONFIGURATION
     * "How is timeout configured?"
     * Search ALL chunk types with heavy CONFIG boost.
     */
    private RetrievalResult handleFindConfiguration(
            String query, String serviceName, float confidence) {

        // Search all types — config might be in YAML, Java @Value, or properties
        List<Document> candidates = vectorRetriever.retrieve(query, serviceName, 20);

        // Boost CONFIG/YAML chunks to the top
        List<Document> boosted = reranker.boostByType(candidates, "CONFIG", 5);

        // Fill remaining slots with other relevant chunks
        List<Document> others = candidates.stream()
                .filter(d -> !"CONFIG".equals(getMetaString(d, "chunk_type")))
                .limit(3)
                .toList();

        boosted.addAll(others);

        log.debug("FIND_CONFIGURATION: {} candidates → {} after boost",
                candidates.size(), boosted.size());

        return RetrievalResult.semantic(QueryIntent.FIND_CONFIGURATION, confidence, boosted);
    }

    /**
     * UNDERSTAND_CONTRACT
     * "What interface does PaymentService implement?"
     * Find class, traverse IMPLEMENTS and OVERRIDES.
     */
    private RetrievalResult handleUnderstandContract(
            String query, String serviceName, float confidence) {

        List<Document> targetDocs = vectorRetriever.retrieveCode(query, serviceName, 3);

        // Find interfaces and parent classes
        List<ClassNode> contracts = targetDocs.stream()
                .map(doc -> getMetaString(doc, "class_name"))
                .filter(Objects::nonNull)
                .flatMap(className -> {
                    List<ClassNode> result = new java.util.ArrayList<>();
                    // Find what this class implements
                    result.addAll(graphService.findImplementors(className));
                    // Find subclasses (things that implement THIS class)
                    result.addAll(graphService.findSubclasses(className));
                    return result.stream();
                })
                .distinct()
                .limit(10)
                .collect(Collectors.toList());

        log.debug("UNDERSTAND_CONTRACT: {} targets → {} contract nodes",
                targetDocs.size(), contracts.size());

        return RetrievalResult.builder(QueryIntent.UNDERSTAND_CONTRACT, confidence)
                .semantic(targetDocs)
                .contract(contracts)
                .build();
    }

    /**
     * DEBUG_ERROR — Full hybrid retrieval with LLM reranking.
     * "Payment is failing with NPE"
     * Most expensive path — used when accuracy is critical.
     */
    private RetrievalResult handleDebugError(
            String query, String serviceName, float confidence) {

        // Step 1: Full hybrid retrieval
        HybridRetriever.HybridResult hybrid = hybridRetriever.retrieve(query, serviceName, 15);

        // Step 2: Full reranking (metadata + LLM cross-encoder)
        List<Document> reranked = reranker.fullRerank(
                query, hybrid.semanticMatches(), 8);

        // Step 3: Get callers of found methods (who triggered the error?)
        List<MethodNode> callers = reranked.stream()
                .limit(3)  // only expand top 3 to avoid explosion
                .map(doc -> buildApproxQN(doc, serviceName))
                .filter(Objects::nonNull)
                .flatMap(qn -> graphService.findCallers(qn).stream())
                .distinct()
                .limit(10)
                .collect(Collectors.toList());

        log.debug("DEBUG_ERROR: hybrid={} reranked={} callers={}",
                hybrid.semanticMatches().size(), reranked.size(), callers.size());

        return RetrievalResult.builder(QueryIntent.DEBUG_ERROR, confidence)
                .semantic(reranked)
                .callChain(hybrid.graphExpansion())
                .callers(callers)
                .build();
    }

    /**
     * NULL_SAFETY
     * "Could order be null here?"
     * Find method, analyse DFG for null paths.
     * For now: fall back to code retrieval with null-check pattern boost.
     */
    private RetrievalResult handleNullSafety(
            String query, String serviceName, float confidence) {

        List<Document> candidates = vectorRetriever.retrieveCode(query, serviceName, 15);

        // Boost methods that have null-check patterns in their content
        List<Document> nullRelevant = candidates.stream()
                .filter(doc -> {
                    String content = doc.getContent().toLowerCase();
                    return content.contains("null") || content.contains("nonnull")
                            || content.contains("nullable") || content.contains("objects.requirenonnull")
                            || content.contains("optional");
                })
                .limit(8)
                .collect(Collectors.toList());

        // If not enough null-relevant chunks, add general code results
        if (nullRelevant.size() < 5) {
            candidates.stream()
                    .filter(d -> !nullRelevant.contains(d))
                    .limit(8 - nullRelevant.size())
                    .forEach(nullRelevant::add);
        }

        log.debug("NULL_SAFETY: {} candidates → {} null-relevant", candidates.size(), nullRelevant.size());

        return RetrievalResult.semantic(QueryIntent.NULL_SAFETY, confidence, nullRelevant);
    }

    /**
     * UNDERSTAND_BUSINESS_RULE
     * "Why does payment retry 3 times?"
     * Only searches BUSINESS_CONTEXT chunks from context.md files.
     */
    private RetrievalResult handleBusinessRule(
            String query, String serviceName, float confidence) {

        List<Document> contextDocs = vectorRetriever.retrieveContext(query, serviceName, 8);

        // If no business context docs found, fall back to DOCUMENTATION chunks
        if (contextDocs.isEmpty()) {
            log.debug("No BUSINESS_CONTEXT chunks found, falling back to DOCUMENTATION");
            contextDocs = searchByType(query, serviceName, "DOCUMENTATION", 5);
        }

        log.debug("UNDERSTAND_BUSINESS_RULE: {} context docs", contextDocs.size());

        return RetrievalResult.semantic(QueryIntent.UNDERSTAND_BUSINESS_RULE, confidence, contextDocs);
    }

    /**
     * FIND_ENDPOINTS
     * "What REST endpoints does this service expose?"
     * Direct graph query — no vector search needed.
     */
    private RetrievalResult handleFindEndpoints(String serviceName, float confidence) {
        List<MethodNode> endpoints = graphService.findEndpoints(serviceName);

        // Also get API_SPEC chunks from OpenAPI files
        List<Document> apiSpecs = searchByType(
                "REST API endpoints", serviceName, "API_SPEC", 20);

        log.debug("FIND_ENDPOINTS: {} graph endpoints + {} API_SPEC chunks",
                endpoints.size(), apiSpecs.size());

        return RetrievalResult.builder(QueryIntent.FIND_ENDPOINTS, confidence)
                .semantic(apiSpecs)
                .endpoints(endpoints)
                .build();
    }

    /**
     * FIND_TESTS
     * "Show me tests for X"
     * Vector search restricted to TEST chunks only.
     */
    private RetrievalResult handleFindTests(
            String query, String serviceName, float confidence) {

        List<Document> testDocs = searchByType(query, serviceName, "TEST", 10);

        log.debug("FIND_TESTS: {} test chunks", testDocs.size());

        return RetrievalResult.semantic(QueryIntent.FIND_TESTS, confidence, testDocs);
    }

    /**
     * GENERAL_UNDERSTANDING — LOW-confidence fallback.
     * "tell me about this service" / anything that didn't match a specific pattern
     *
     * Broad vector search across all chunk types, no graph traversal.
     * The LLM receives a general-purpose prompt with no rigid format.
     */
    private RetrievalResult handleGeneralUnderstanding(
            String query, String serviceName, float confidence) {

        // Broad search — all chunk types, higher top-K to give LLM maximum context
        List<Document> candidates = vectorRetriever.retrieve(query, serviceName, 20);
        List<Document> reranked   = reranker.metadataRerank(query, candidates, 10);

        log.debug("GENERAL_UNDERSTANDING: {} candidates → {} after rerank",
                candidates.size(), reranked.size());

        return RetrievalResult.semantic(QueryIntent.GENERAL_UNDERSTANDING, confidence, reranked);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Search for documents of a specific chunk_type.
     */
    private List<Document> searchByType(String query, String serviceName,
                                        String chunkType, int topK) {
        FilterExpressionBuilder f = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(0.35)  // lower threshold for specific type
                        .withFilterExpression(
                                f.and(
                                        f.eq("service_name", serviceName),
                                        f.eq("chunk_type", chunkType)
                                ).build()
                        )
        );
    }

    /**
     * Build an approximate qualified name from document metadata.
     * Used to look up graph nodes after vector retrieval.
     *
     * Exact QN matching is hard (parameter signatures vary).
     * We use serviceName + className + methodName as an approximation.
     */
    private String buildApproxQN(Document doc, String serviceName) {
        String elementName = getMetaString(doc, "element_name");
        String className   = getMetaString(doc, "class_name");

        if (elementName == null) return null;
        if (className == null) return serviceName + "." + elementName;
        return serviceName + "." + className + "." + elementName;
    }

    private String getMetaString(Document doc, String key) {
        Object val = doc.getMetadata().get(key);
        return val != null ? val.toString() : null;
    }

    private String truncate(String text, int maxLen) {
        return text == null ? "" :
                text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}