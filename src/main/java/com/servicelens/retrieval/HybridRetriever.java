package com.servicelens.retrieval;

import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Three-layer hybrid retriever that combines semantic vector search with
 * structural knowledge-graph expansion.
 *
 * <p>Retrieval proceeds in three layers for each query:</p>
 * <ol>
 *   <li><strong>Layer 1 — Semantic (vector):</strong> {@link CodeRetriever} performs
 *       embedding-based similarity search against pgvector to find semantically
 *       relevant code chunks.</li>
 *   <li><strong>Layer 2 — Graph expansion:</strong> For each method found in layer 1,
 *       the Neo4j knowledge graph is traversed to find callers (upstream) and the
 *       full call chain (downstream), broadening structural context.</li>
 *   <li><strong>Layer 3 — Deduplication:</strong> Graph-expanded methods are
 *       deduplicated by qualified name before being returned, so each method
 *       appears at most once in the final result.</li>
 * </ol>
 *
 * <p>The {@link HybridResult} record bundles both the semantic documents and the
 * graph-expanded method nodes, allowing callers to construct rich prompts that
 * include both text-level and structural context.</p>
 *
 * <p>For impact analysis (which classes will break if a class changes), use
 * {@link #findImpact(String, String)} rather than {@link #retrieve(String, String, int)}.</p>
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final CodeRetriever vectorRetriever;
    private final KnowledgeGraphService graphService;

    public HybridRetriever(CodeRetriever vectorRetriever,
                           KnowledgeGraphService graphService) {
        this.vectorRetriever = vectorRetriever;
        this.graphService    = graphService;
    }

    /**
     * Execute a hybrid retrieval for the given query within the specified service.
     *
     * <p>The three-layer approach is:</p>
     * <ol>
     *   <li>Vector search finds semantically relevant method chunks.</li>
     *   <li>For each method found, graph traversal adds callers and downstream
     *       call-chain methods to widen structural context.</li>
     *   <li>Graph-expanded results are deduplicated by qualified name.</li>
     * </ol>
     *
     * @param query       the natural-language query
     * @param serviceName the logical service name to scope retrieval to
     * @param topK        the number of semantic results to fetch in layer 1
     * @return a {@link HybridResult} containing semantic documents and graph-expanded
     *         method nodes, ready for LLM context construction
     */
    public HybridResult retrieve(String query, String serviceName, int topK) {
        log.debug("Hybrid retrieve: service={} topK={}", serviceName, topK);

        List<Document> semanticResults =
                vectorRetriever.retrieve(query, serviceName, topK);

        List<MethodNode> graphExpansion = new ArrayList<>();

        semanticResults.forEach(doc -> {
            String elementName = (String) doc.getMetadata().get("element_name");
            String className   = (String) doc.getMetadata().get("class_name");

            if (elementName != null && className != null) {
                String approxQN = serviceName + "." + className + "." + elementName;

                graphExpansion.addAll(
                        graphService.findCallers(approxQN));

                graphExpansion.addAll(
                        graphService.findCallChain(approxQN, serviceName));
            }
        });

        Set<String> seen = new HashSet<>();
        List<MethodNode> uniqueExpansion = graphExpansion.stream()
                .filter(m -> seen.add(m.getQualifiedName()))
                .collect(Collectors.toList());

        log.debug("Hybrid retrieve complete: semantic={} graphExpansion={}", semanticResults.size(), uniqueExpansion.size());
        return new HybridResult(semanticResults, uniqueExpansion);
    }

    /**
     * Perform impact analysis for a given class: find all classes in the service
     * that directly depend on it and would be affected by a change.
     *
     * <p>Delegates to {@link KnowledgeGraphService#findDependents(String, String)},
     * which traverses DEPENDS_ON relationships in the reverse direction.</p>
     *
     * @param classQN     the fully qualified name of the class being changed
     * @param serviceName the logical service name to scope the search to
     * @return list of {@link ClassNode} instances that depend on {@code classQN}
     */
    public List<ClassNode> findImpact(String classQN, String serviceName) {
        return graphService.findDependents(classQN, serviceName);
    }

    /**
     * Container for the combined output of a hybrid retrieval operation.
     *
     * @param semanticMatches semantically relevant chunks from pgvector
     * @param graphExpansion  structurally related methods from Neo4j graph traversal,
     *                        deduplicated by qualified name
     */
    public record HybridResult(
            List<Document> semanticMatches,
            List<MethodNode> graphExpansion
    ) {
        /**
         * Returns the total number of context items (semantic + graph).
         *
         * @return sum of semantic match count and graph expansion count
         */
        public int totalContext() {
            return semanticMatches.size() + graphExpansion.size();
        }
    }
}
