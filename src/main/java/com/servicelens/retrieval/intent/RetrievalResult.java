package com.servicelens.retrieval.intent;

import com.servicelens.dfg.MethodDataFlow;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified result from IntentBasedRetriever.
 *
 * Different intents populate different fields.
 * Agents check which fields are populated and construct
 * their prompt accordingly.
 *
 * FIELD POPULATION BY INTENT:
 * ─────────────────────────────
 * FIND_IMPLEMENTATION:   semanticMatches
 * TRACE_CALL_CHAIN:      semanticMatches + callChain
 * TRACE_CALLERS:         semanticMatches + callers
 * IMPACT_ANALYSIS:       semanticMatches + impactedClasses
 * FIND_CONFIGURATION:    semanticMatches (CONFIG chunks boosted)
 * UNDERSTAND_CONTRACT:   semanticMatches + contractNodes
 * DEBUG_ERROR:           semanticMatches + callChain + callers (full hybrid)
 * NULL_SAFETY:           semanticMatches + dataFlows
 * UNDERSTAND_BUSINESS:   semanticMatches (BUSINESS_CONTEXT only)
 * FIND_ENDPOINTS:        endpointMethods
 * FIND_TESTS:            semanticMatches (TEST chunks only)
 */
public record RetrievalResult(

        /** The detected query intent */
        QueryIntent intent,

        /** Confidence of intent classification (0.0 - 1.0) */
        float intentConfidence,

        /** Semantically relevant chunks from pgvector (reranked) */
        List<Document> semanticMatches,

        /** Call chain downstream from found method (TRACE_CALL_CHAIN) */
        List<MethodNode> callChain,

        /** Methods that call the found method (TRACE_CALLERS) */
        List<MethodNode> callers,

        /** Classes that depend on the found class (IMPACT_ANALYSIS) */
        List<ClassNode> impactedClasses,

        /** Interface/override relationship nodes (UNDERSTAND_CONTRACT) */
        List<ClassNode> contractNodes,

        /** Data flow information per method (NULL_SAFETY) */
        List<MethodDataFlow> dataFlows,

        /** Direct endpoint list (FIND_ENDPOINTS) */
        List<MethodNode> endpointMethods

) {

    /** Convenience factory — just semantic results */
    public static RetrievalResult semantic(QueryIntent intent, float confidence,
                                           List<Document> matches) {
        return new RetrievalResult(intent, confidence, matches,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Convenience factory — semantic + call chain */
    public static RetrievalResult withCallChain(QueryIntent intent, float confidence,
                                                List<Document> matches,
                                                List<MethodNode> chain) {
        return new RetrievalResult(intent, confidence, matches,
                chain, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Convenience factory — semantic + callers */
    public static RetrievalResult withCallers(QueryIntent intent, float confidence,
                                              List<Document> matches,
                                              List<MethodNode> callers) {
        return new RetrievalResult(intent, confidence, matches,
                List.of(), callers, List.of(), List.of(), List.of(), List.of());
    }

    /** Convenience factory — impact analysis */
    public static RetrievalResult withImpact(QueryIntent intent, float confidence,
                                             List<Document> matches,
                                             List<ClassNode> impacted) {
        return new RetrievalResult(intent, confidence, matches,
                List.of(), List.of(), impacted, List.of(), List.of(), List.of());
    }

    /** Convenience factory — endpoints */
    public static RetrievalResult endpoints(List<MethodNode> endpoints) {
        return new RetrievalResult(QueryIntent.FIND_ENDPOINTS, 1.0f,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), endpoints);
    }

    /** Total context size (how much the agent gets to see) */
    public int totalContextSize() {
        return semanticMatches.size() + callChain.size() + callers.size()
                + impactedClasses.size() + contractNodes.size() + endpointMethods.size();
    }

    /** Summary for logging */
    public String summary() {
        return String.format(
                "intent=%s(%.0f%%) semantic=%d chain=%d callers=%d impact=%d endpoints=%d",
                intent, intentConfidence * 100,
                semanticMatches.size(), callChain.size(), callers.size(),
                impactedClasses.size(), endpointMethods.size()
        );
    }

    // Mutable builder for incremental construction
    public static Builder builder(QueryIntent intent, float confidence) {
        return new Builder(intent, confidence);
    }

    public static class Builder {
        private final QueryIntent intent;
        private final float confidence;
        private List<Document> semanticMatches = new ArrayList<>();
        private List<MethodNode> callChain     = new ArrayList<>();
        private List<MethodNode> callers        = new ArrayList<>();
        private List<ClassNode> impactedClasses = new ArrayList<>();
        private List<ClassNode> contractNodes   = new ArrayList<>();
        private List<MethodDataFlow> dataFlows  = new ArrayList<>();
        private List<MethodNode> endpointMethods = new ArrayList<>();

        private Builder(QueryIntent intent, float confidence) {
            this.intent = intent;
            this.confidence = confidence;
        }

        public Builder semantic(List<Document> docs) { semanticMatches = docs; return this; }
        public Builder callChain(List<MethodNode> chain) { callChain = chain; return this; }
        public Builder callers(List<MethodNode> c) { callers = c; return this; }
        public Builder impacted(List<ClassNode> cls) { impactedClasses = cls; return this; }
        public Builder contract(List<ClassNode> cls) { contractNodes = cls; return this; }
        public Builder dataFlows(List<MethodDataFlow> df) { dataFlows = df; return this; }
        public Builder endpoints(List<MethodNode> e) { endpointMethods = e; return this; }

        public RetrievalResult build() {
            return new RetrievalResult(intent, confidence, semanticMatches,
                    callChain, callers, impactedClasses, contractNodes,
                    dataFlows, endpointMethods);
        }
    }
}