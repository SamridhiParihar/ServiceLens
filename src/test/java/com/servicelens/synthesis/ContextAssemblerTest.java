package com.servicelens.synthesis;

import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.domain.NodeType;
import com.servicelens.retrieval.intent.QueryIntent;
import com.servicelens.retrieval.intent.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ContextAssembler}.
 *
 * <p>Pure unit tests — no Spring context, no mocks.
 * Verifies that the context string is assembled correctly for every
 * result section type and that the character budget is respected.</p>
 */
@DisplayName("ContextAssembler — unit")
class ContextAssemblerTest {

    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ContextAssembler();
    }

    // ── Empty result ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty RetrievalResult produces an empty string")
    void emptyResult_producesEmptyString() {
        RetrievalResult result = RetrievalResult.semantic(
                QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of());

        assertThat(assembler.assemble(result)).isEmpty();
    }

    // ── Semantic matches ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Semantic matches")
    class SemanticMatchesTests {

        @Test
        @DisplayName("Semantic match content appears in context")
        void semanticMatchContent_appearsInContext() {
            Document doc = new Document("public void processPayment() {}",
                    Map.of("chunk_type", "CODE",
                           "element_name", "processPayment",
                           "class_name", "PaymentService",
                           "service_name", "order-svc"));

            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of(doc));

            String context = assembler.assemble(result);

            assertThat(context).contains("RELEVANT CODE CHUNKS");
            assertThat(context).contains("public void processPayment() {}");
            assertThat(context).contains("PaymentService.processPayment");
        }

        @Test
        @DisplayName("Multiple semantic matches each get their own chunk header")
        void multipleSemanticMatches_eachHaveChunkHeader() {
            Document d1 = new Document("void methodA() {}",
                    Map.of("chunk_type", "CODE", "element_name", "methodA", "class_name", "Svc"));
            Document d2 = new Document("void methodB() {}",
                    Map.of("chunk_type", "CODE", "element_name", "methodB", "class_name", "Svc"));

            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of(d1, d2));

            String context = assembler.assemble(result);

            assertThat(context).contains("Chunk 1");
            assertThat(context).contains("Chunk 2");
            assertThat(context).contains("methodA");
            assertThat(context).contains("methodB");
        }

        @Test
        @DisplayName("Chunk header falls back gracefully when metadata fields are absent")
        void chunkHeader_gracefulFallbackWhenMetadataMissing() {
            Document doc = new Document("some content", Map.of());

            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of(doc));

            // Should not throw; content must still appear
            String context = assembler.assemble(result);
            assertThat(context).contains("some content");
        }
    }

    // ── Call chain ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Call chain")
    class CallChainTests {

        @Test
        @DisplayName("Call chain section header and method names appear in context")
        void callChain_appearsWithSectionHeader() {
            MethodNode m1 = methodNode("checkout", "CheckoutService", false, null, null);
            MethodNode m2 = methodNode("processPayment", "PaymentService", false, null, null);

            RetrievalResult result = RetrievalResult.withCallChain(
                    QueryIntent.TRACE_CALL_CHAIN, 0.88f, List.of(), List.of(m1, m2));

            String context = assembler.assemble(result);

            assertThat(context).contains("CALL CHAIN");
            assertThat(context).contains("CheckoutService.checkout");
            assertThat(context).contains("PaymentService.processPayment");
        }

        @Test
        @DisplayName("Endpoint method in call chain shows HTTP method and path")
        void callChain_endpointMethodShowsHttpDetails() {
            MethodNode endpoint = methodNode("createOrder", "OrderController", true, "POST", "/orders");

            RetrievalResult result = RetrievalResult.withCallChain(
                    QueryIntent.TRACE_CALL_CHAIN, 0.9f, List.of(), List.of(endpoint));

            String context = assembler.assemble(result);

            assertThat(context).contains("POST");
            assertThat(context).contains("/orders");
        }
    }

    // ── Callers ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Callers section header and caller names appear in context")
    void callers_appearsWithSectionHeader() {
        MethodNode caller = methodNode("checkout", "CheckoutService", false, null, null);

        RetrievalResult result = RetrievalResult.withCallers(
                QueryIntent.TRACE_CALLERS, 0.85f, List.of(), List.of(caller));

        String context = assembler.assemble(result);

        assertThat(context).contains("CALLERS");
        assertThat(context).contains("CheckoutService.checkout");
    }

    // ── Impacted classes ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Impacted classes section shows class name and node type")
    void impactedClasses_showsNameAndType() {
        ClassNode cls = classNode("com.example.OrderService", "OrderService", NodeType.CLASS);

        RetrievalResult result = RetrievalResult.withImpact(
                QueryIntent.IMPACT_ANALYSIS, 0.80f, List.of(), List.of(cls));

        String context = assembler.assemble(result);

        assertThat(context).contains("IMPACTED CLASSES");
        assertThat(context).contains("OrderService");
        assertThat(context).contains("CLASS");
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Endpoints section shows HTTP method, path and handler class")
    void endpoints_showsHttpDetails() {
        MethodNode ep = methodNode("getOrder", "OrderController", true, "GET", "/orders/{id}");

        RetrievalResult result = RetrievalResult.endpoints(List.of(ep));

        String context = assembler.assemble(result);

        assertThat(context).contains("HTTP ENDPOINTS");
        assertThat(context).contains("GET");
        assertThat(context).contains("/orders/{id}");
        assertThat(context).contains("OrderController.getOrder");
    }

    // ── Budget enforcement ────────────────────────────────────────────────────

    @Test
    @DisplayName("Context exceeding MAX_CHARS is truncated with a marker")
    void oversizedContext_isTruncatedWithMarker() {
        // Build a chunk whose content alone exceeds the budget
        String hugeContent = "x".repeat(ContextAssembler.MAX_CHARS + 1000);
        Document doc = new Document(hugeContent,
                Map.of("chunk_type", "CODE", "element_name", "big", "class_name", "Svc"));

        RetrievalResult result = RetrievalResult.semantic(
                QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of(doc));

        String context = assembler.assemble(result);

        assertThat(context.length()).isLessThanOrEqualTo(ContextAssembler.MAX_CHARS + 20);
        assertThat(context).endsWith("...[truncated]");
    }

    @Test
    @DisplayName("Context within MAX_CHARS is not truncated")
    void smallContext_isNotTruncated() {
        Document doc = new Document("short content",
                Map.of("chunk_type", "CODE", "element_name", "m", "class_name", "C"));

        RetrievalResult result = RetrievalResult.semantic(
                QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of(doc));

        String context = assembler.assemble(result);

        assertThat(context).doesNotContain("...[truncated]");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static MethodNode methodNode(String simpleName, String className,
                                         boolean endpoint, String httpMethod,
                                         String endpointPath) {
        MethodNode m = new MethodNode();
        m.setSimpleName(simpleName);
        m.setQualifiedName(className + "." + simpleName);
        m.setClassName(className);
        m.setEndpoint(endpoint);
        m.setHttpMethod(httpMethod);
        m.setEndpointPath(endpointPath);
        return m;
    }

    private static ClassNode classNode(String qualifiedName, String simpleName, NodeType type) {
        ClassNode c = new ClassNode();
        c.setQualifiedName(qualifiedName);
        c.setSimpleName(simpleName);
        c.setNodeType(type);
        return c;
    }
}
