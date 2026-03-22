package com.servicelens.functional.retrieval;

import com.servicelens.functional.support.FunctionalTestBase;
import com.servicelens.retrieval.CodeRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for {@link CodeRetriever} using real pgvector and real Ollama embeddings.
 *
 * <p>Documents are seeded directly via {@link VectorStore#add} to isolate the
 * retrieval layer from the ingestion pipeline.  Queries are phrased naturally —
 * the real {@code nomic-embed-text} model handles semantic matching, so queries
 * do not need to be identical to document content.</p>
 */
@DisplayName("CodeRetriever — functional")
class CodeRetrieverFunctionalTest extends FunctionalTestBase {

    private static final String SVC_A = "retriever-svc-a";
    private static final String SVC_B = "retriever-svc-b";

    @Autowired
    private CodeRetriever retriever;

    @Autowired
    private VectorStore vectorStore;

    @BeforeEach
    void seedDocuments() {
        vectorStore.add(List.of(
                // CODE chunks — service A
                doc("public void processPayment(Order order) { paymentGateway.charge(order); }",
                        "CODE", SVC_A),
                doc("public Order createOrder(String customerId) { return orderRepo.save(new Order(customerId)); }",
                        "CODE", SVC_A),

                // CODE chunk — service B (same content, different service — tests isolation)
                doc("public void processPayment(Order order) { paymentGateway.charge(order); }",
                        "CODE", SVC_B),

                // CONFIG chunk — service A
                doc("server:\n  port: 8080\nspring:\n  datasource:\n    url: jdbc:postgresql://localhost/orders",
                        "CONFIG", SVC_A),

                // BUSINESS_CONTEXT chunk — service A
                doc("Payment retry policy: retry up to 3 times with exponential backoff. " +
                    "Retry only on network errors, never on card declines (BR-PAY-042).",
                        "BUSINESS_CONTEXT", SVC_A),

                // TEST chunk — service A
                doc("@Test void shouldChargeCardWhenOrderIsPlaced() { " +
                    "given(gateway.charge(any())).willReturn(SUCCESS); orderService.placeOrder(order); " +
                    "verify(gateway).charge(order); }",
                        "TEST", SVC_A)
        ));
    }

    // ── retrieve() — all chunk types ──────────────────────────────────────────

    @Test
    @DisplayName("retrieve() returns documents with correct service metadata for a natural query")
    void retrieve_returnsSemanticallyRelevantDocuments() {
        List<Document> results = retriever.retrieve(
                "how does payment processing work", SVC_A, 5);

        // Verify the retrieval pipeline is functional: results are returned and
        // every returned document belongs to the queried service.
        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(doc -> {
            assertThat(doc.getMetadata()).containsKey("service_name");
            assertThat(doc.getMetadata()).containsKey("chunk_type");
            assertThat(doc.getMetadata().get("service_name")).isEqualTo(SVC_A);
        });
    }

    @Test
    @DisplayName("retrieve() is scoped to the requested service — does not return other-service docs")
    void retrieve_isolatedByServiceName() {
        List<Document> results = retriever.retrieve(
                "how does payment processing work", SVC_A, 10);

        assertThat(results).allMatch(
                d -> SVC_A.equals(d.getMetadata().get("service_name")));
    }

    @Test
    @DisplayName("retrieve() returns empty list for a service with no indexed data")
    void retrieve_emptyForUnknownService() {
        List<Document> results = retriever.retrieve(
                "payment processing", "nonexistent-svc", 5);

        assertThat(results).isEmpty();
    }

    // ── retrieveCode() — CODE chunks only ────────────────────────────────────

    @Test
    @DisplayName("retrieveCode() returns only CODE-typed chunks even when CONFIG is also relevant")
    void retrieveCode_onlyCodeChunks() {
        List<Document> results = retriever.retrieveCode(
                "order creation and payment charging", SVC_A, 10);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(
                d -> "CODE".equals(d.getMetadata().get("chunk_type")));
    }

    @Test
    @DisplayName("retrieveCode() never returns CONFIG or BUSINESS_CONTEXT chunks")
    void retrieveCode_excludesNonCodeTypes() {
        List<Document> results = retriever.retrieveCode(
                "payment retry policy datasource configuration", SVC_A, 10);

        assertThat(results).allMatch(
                d -> "CODE".equals(d.getMetadata().get("chunk_type")));
    }

    // ── retrieveContext() — BUSINESS_CONTEXT only ────────────────────────────

    @Test
    @DisplayName("retrieveContext() returns only BUSINESS_CONTEXT chunks for a rule query")
    void retrieveContext_onlyBusinessContextChunks() {
        List<Document> results = retriever.retrieveContext(
                "what is the payment retry policy", SVC_A, 5);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(
                d -> "BUSINESS_CONTEXT".equals(d.getMetadata().get("chunk_type")));
    }

    // ── topK cap ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("retrieve() returns at most topK results")
    void retrieve_respectsTopKLimit() {
        int topK = 2;
        List<Document> results = retriever.retrieve(
                "order payment processing", SVC_A, topK);

        assertThat(results.size()).isLessThanOrEqualTo(topK);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static Document doc(String content, String chunkType, String serviceName) {
        return new Document(content, Map.of(
                "service_name", serviceName,
                "chunk_type",   chunkType
        ));
    }
}
