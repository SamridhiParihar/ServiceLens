package com.servicelens.functional.retrieval;

import com.servicelens.chunking.processors.JavaFileProcessor;
import com.servicelens.functional.support.FunctionalTestBase;
import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.domain.NodeType;
import com.servicelens.retrieval.intent.IntentBasedRetriever;
import com.servicelens.retrieval.intent.QueryIntent;
import com.servicelens.retrieval.intent.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end functional tests for {@link IntentBasedRetriever} using real
 * Ollama embeddings, real pgvector, and real Neo4j.
 *
 * <p>Each test seeds the stores, fires a natural-language query, and verifies
 * that the correct intent is detected by {@link com.servicelens.retrieval.intent.IntentClassifier}
 * and that the retrieval result contains the expected data.</p>
 */
@DisplayName("IntentBasedRetriever — functional E2E")
class IntentBasedRetrieverFunctionalTest extends FunctionalTestBase {

    private static final String SERVICE = "intent-test-svc";

    @Autowired private IntentBasedRetriever intentRetriever;
    @Autowired private VectorStore          vectorStore;
    @Autowired private KnowledgeGraphService graphService;

    @BeforeEach
    void seedData() {
        // ── pgvector ─────────────────────────────────────────────────────────
        vectorStore.add(List.of(
                doc("public Order processOrder(String customerId) { " +
                    "validateCustomer(customerId); return orderRepo.save(new Order(customerId)); }",
                        "CODE", "processOrder", "OrderService"),

                doc("server:\n  port: 8080\nspring:\n  datasource:\n    url: jdbc:postgresql://localhost/orders\n" +
                    "timeout:\n  payment: 30000\n  checkout: 5000",
                        "CONFIG", null, null),

                doc("Payment retry rule BR-PAY-042: retry failed payments up to 3 times " +
                    "with exponential backoff. Do not retry on card decline (4xx).",
                        "BUSINESS_CONTEXT", null, null),

                doc("@Test void shouldCreateOrderForValidCustomer() { " +
                    "given(customerRepo.existsById(any())).willReturn(true); " +
                    "Order order = orderService.processOrder('cust-001'); " +
                    "assertThat(order).isNotNull(); }",
                        "TEST", null, null)
        ));

        // ── Neo4j endpoint ────────────────────────────────────────────────────
        ClassNode ctrl = cls("com.example.OrderController", "OrderController");
        MethodNode post = method("createOrder", "com.example.OrderController.createOrder", ctrl);
        post.setEndpoint(true);
        post.setHttpMethod("POST");
        post.setEndpointPath("/orders");
        MethodNode get = method("getOrder", "com.example.OrderController.getOrder", ctrl);
        get.setEndpoint(true);
        get.setHttpMethod("GET");
        get.setEndpointPath("/orders/{id}");

        graphService.saveFileResult(new JavaFileProcessor.JavaFileResult(
                List.of(), List.of(), List.of(ctrl), List.of(post, get), List.of(), List.of()));
    }

    // ── FIND_IMPLEMENTATION ───────────────────────────────────────────────────

    @Test
    @DisplayName("FIND_IMPLEMENTATION — returns code chunk for implementation query")
    void findImplementation_returnsCodeChunk() {
        RetrievalResult result = intentRetriever.retrieve(
                "where is processOrder implemented", SERVICE);

        assertThat(result.intent()).isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
        assertThat(result.semanticMatches()).isNotEmpty();
        assertThat(result.semanticMatches())
                .anyMatch(d -> "CODE".equals(d.getMetadata().get("chunk_type")));
    }

    // ── FIND_CONFIGURATION ────────────────────────────────────────────────────

    @Test
    @DisplayName("FIND_CONFIGURATION — returns CONFIG chunk for configuration query")
    void findConfiguration_returnsConfigChunk() {
        RetrievalResult result = intentRetriever.retrieve(
                "how is the payment timeout configured", SERVICE);

        assertThat(result.intent()).isEqualTo(QueryIntent.FIND_CONFIGURATION);
        assertThat(result.semanticMatches())
                .anyMatch(d -> "CONFIG".equals(d.getMetadata().get("chunk_type")));
    }

    // ── FIND_ENDPOINTS ────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIND_ENDPOINTS — returns endpoint MethodNodes from Neo4j")
    void findEndpoints_returnsGraphEndpoints() {
        RetrievalResult result = intentRetriever.retrieve(
                "what REST endpoints does this service expose", SERVICE);

        assertThat(result.intent()).isEqualTo(QueryIntent.FIND_ENDPOINTS);
        assertThat(result.endpointMethods()).isNotEmpty();
        assertThat(result.endpointMethods())
                .anyMatch(m -> m.getSimpleName().equals("createOrder"));
        assertThat(result.endpointMethods())
                .anyMatch(m -> "POST".equals(m.getHttpMethod()));
    }

    // ── UNDERSTAND_BUSINESS_RULE ──────────────────────────────────────────────

    @Test
    @DisplayName("UNDERSTAND_BUSINESS_RULE — returns BUSINESS_CONTEXT chunk for rule query")
    void understandBusinessRule_returnsContextChunk() {
        RetrievalResult result = intentRetriever.retrieve(
                "why does the payment retry 3 times", SERVICE);

        assertThat(result.intent()).isEqualTo(QueryIntent.UNDERSTAND_BUSINESS_RULE);
        assertThat(result.semanticMatches())
                .anyMatch(d -> "BUSINESS_CONTEXT".equals(d.getMetadata().get("chunk_type")));
    }

    // ── FIND_TESTS ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIND_TESTS — returns TEST chunk for test search query")
    void findTests_returnsTestChunk() {
        RetrievalResult result = intentRetriever.retrieve(
                "show me tests for order creation", SERVICE);

        assertThat(result.intent()).isEqualTo(QueryIntent.FIND_TESTS);
        assertThat(result.semanticMatches())
                .anyMatch(d -> "TEST".equals(d.getMetadata().get("chunk_type")));
    }

    // ── Intent always set ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Every retrieval result carries a non-null intent")
    void result_intentAlwaysSet() {
        RetrievalResult result = intentRetriever.retrieve(
                "where is processOrder implemented", SERVICE);

        assertThat(result.intent()).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Document doc(String content, String chunkType,
                         String elementName, String className) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("service_name", SERVICE);
        meta.put("chunk_type",   chunkType);
        if (elementName != null) meta.put("element_name", elementName);
        if (className   != null) meta.put("class_name",   className);
        return new Document(content, meta);
    }

    private ClassNode cls(String qualifiedName, String simpleName) {
        ClassNode n = new ClassNode();
        n.setQualifiedName(qualifiedName);
        n.setSimpleName(simpleName);
        n.setServiceName(SERVICE);
        n.setFilePath("/repo/" + simpleName + ".java");
        n.setNodeType(NodeType.CLASS);
        n.setPackageName("com.example");
        return n;
    }

    private MethodNode method(String simple, String qualified, ClassNode owner) {
        MethodNode n = new MethodNode();
        n.setSimpleName(simple);
        n.setQualifiedName(qualified);
        n.setClassName(owner.getSimpleName());
        n.setServiceName(SERVICE);
        n.setFilePath(owner.getFilePath());
        n.setEndpoint(false);
        n.setTransactional(false);
        return n;
    }
}
