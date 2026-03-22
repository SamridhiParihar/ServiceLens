package com.servicelens.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Semantic vector-search layer over the pgvector {@link VectorStore}.
 *
 * <p>This service wraps the Spring AI {@link VectorStore} and exposes service-scoped,
 * filtered similarity-search methods. Every search is constrained to a single logical
 * service by including a {@code service_name} metadata filter, so chunks from different
 * services never pollute each other's results.</p>
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>General-purpose retrieval across all chunk types for a service</li>
 *   <li>Code-only retrieval (chunk_type = CODE) for implementation queries</li>
 *   <li>Business-context retrieval (chunk_type = BUSINESS_CONTEXT) for rule queries</li>
 * </ul>
 *
 * <p>This class is used directly by {@link HybridRetriever} as the first retrieval
 * layer (semantic) before graph expansion is applied.</p>
 */
@Service
public class CodeRetriever {

    private static final Logger log = LoggerFactory.getLogger(CodeRetriever.class);

    private final VectorStore vectorStore;

    public CodeRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Retrieve the top {@code topK} most semantically relevant chunks for a query,
     * scoped to the given service. All chunk types are searched.
     *
     * @param query       the natural-language query to embed and search
     * @param serviceName the logical service name to filter results to
     * @param topK        the maximum number of results to return
     * @return list of matching {@link Document} instances ordered by descending similarity,
     *         excluding any documents whose similarity score falls below 0.5
     */
    public List<Document> retrieve(String query, String serviceName, int topK) {
        log.debug("Semantic search: service={} topK={} query='{}'", serviceName, topK, query);
        FilterExpressionBuilder filter = new FilterExpressionBuilder();

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(0.5)
                        .withFilterExpression(
                                filter.eq("service_name", serviceName).build()
                        )
        );
        log.debug("Semantic search returned {} results for service={}", results.size(), serviceName);
        return results;
    }

    /**
     * Retrieve only code chunks (chunk_type = CODE) for a query, scoped to the given service.
     *
     * <p>This method excludes configuration, schema, API spec, and documentation chunks,
     * making it suitable for implementation-focused queries where source code context
     * is required.</p>
     *
     * <p>The similarity threshold is set to {@code 0.35} — lower than the general
     * {@link #retrieve} threshold of {@code 0.5} — because natural-language queries
     * ("where is X implemented?") have limited vocabulary overlap with Java source code.
     * Cosine distances between English queries and code embeddings are systematically
     * higher than distances between two English texts, so a strict threshold silently
     * suppresses correct results.  The {@code chunk_type = CODE} filter already prevents
     * config, schema, and documentation noise from surfacing.</p>
     *
     * @param query       the natural-language query to embed and search
     * @param serviceName the logical service name to filter results to
     * @param topK        the maximum number of results to return
     * @return list of code-type {@link Document} instances ordered by descending similarity,
     *         excluding any documents whose similarity score falls below 0.35
     */
    public List<Document> retrieveCode(String query, String serviceName, int topK) {
        FilterExpressionBuilder filter = new FilterExpressionBuilder();

        return vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(0.35)
                        .withFilterExpression(
                                filter.and(
                                        filter.eq("service_name", serviceName),
                                        filter.eq("chunk_type", "CODE")
                                ).build()
                        )
        );
    }

    /**
     * Retrieve only business-context chunks (chunk_type = BUSINESS_CONTEXT) for a query,
     * scoped to the given service.
     *
     * <p>Business-context chunks originate from Markdown documents that describe
     * domain rules, SLAs, and policies. Using a lower similarity threshold (0.4) here
     * improves recall for loosely worded "why does X..." queries.</p>
     *
     * @param query       the natural-language query to embed and search
     * @param serviceName the logical service name to filter results to
     * @param topK        the maximum number of results to return
     * @return list of business-context {@link Document} instances ordered by descending similarity,
     *         excluding any documents whose similarity score falls below 0.4
     */
    public List<Document> retrieveContext(String query, String serviceName, int topK) {
        FilterExpressionBuilder filter = new FilterExpressionBuilder();

        return vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(0.4)
                        .withFilterExpression(
                                filter.and(
                                        filter.eq("service_name", serviceName),
                                        filter.eq("chunk_type", "BUSINESS_CONTEXT")
                                ).build()
                        )
        );
    }
}
