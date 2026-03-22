package com.servicelens.retrieval.intent;

import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.domain.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetrievalResult}.
 *
 * <p>Verifies the factory methods, builder, {@code totalContextSize()},
 * and {@code summary()} formatting.  All tests are pure data-structure tests —
 * no Spring context or mocks are required.</p>
 */
@DisplayName("RetrievalResult")
class RetrievalResultTest {

    // ── helpers ────────────────────────────────────────────────────────────

    private static Document doc(String content) {
        return new Document(content, Map.of("chunk_type", "CODE"));
    }

    private static MethodNode method(String simpleName) {
        MethodNode m = new MethodNode();
        m.setSimpleName(simpleName);
        m.setQualifiedName("com.example." + simpleName);
        return m;
    }

    private static ClassNode clazz(String simpleName) {
        ClassNode c = new ClassNode();
        c.setSimpleName(simpleName);
        c.setQualifiedName("com.example." + simpleName);
        c.setNodeType(NodeType.CLASS);
        return c;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Factory method: semantic()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("semantic() factory method")
    class SemanticFactoryTests {

        @Test
        @DisplayName("Should populate only semanticMatches; all other lists empty")
        void onlySemanticMatchesPopulated() {
            List<Document> docs = List.of(doc("public void process() {}"));
            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.9f, docs);

            assertThat(result.semanticMatches()).hasSize(1);
            assertThat(result.callChain()).isEmpty();
            assertThat(result.callers()).isEmpty();
            assertThat(result.impactedClasses()).isEmpty();
            assertThat(result.contractNodes()).isEmpty();
            assertThat(result.dataFlows()).isEmpty();
            assertThat(result.endpointMethods()).isEmpty();
        }

        @Test
        @DisplayName("Should preserve intent and confidence")
        void preservesIntentAndConfidence() {
            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.DEBUG_ERROR, 0.75f, List.of());
            assertThat(result.intent()).isEqualTo(QueryIntent.DEBUG_ERROR);
            assertThat(result.intentConfidence()).isEqualTo(0.75f);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Factory method: withCallChain()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withCallChain() factory method")
    class WithCallChainFactoryTests {

        @Test
        @DisplayName("Should populate semanticMatches and callChain; rest empty")
        void populatesSemanticAndCallChain() {
            List<MethodNode> chain = List.of(method("validateOrder"), method("chargeCard"));
            RetrievalResult result = RetrievalResult.withCallChain(
                    QueryIntent.TRACE_CALL_CHAIN, 0.85f, List.of(doc("code")), chain);

            assertThat(result.semanticMatches()).hasSize(1);
            assertThat(result.callChain()).containsExactlyElementsOf(chain);
            assertThat(result.callers()).isEmpty();
            assertThat(result.impactedClasses()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Factory method: withCallers()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withCallers() factory method")
    class WithCallersFactoryTests {

        @Test
        @DisplayName("Should populate callers list; callChain and impact empty")
        void populatesCallers() {
            List<MethodNode> callers = List.of(method("checkout"), method("submitOrder"));
            RetrievalResult result = RetrievalResult.withCallers(
                    QueryIntent.TRACE_CALLERS, 0.80f, List.of(), callers);

            assertThat(result.callers()).containsExactlyElementsOf(callers);
            assertThat(result.callChain()).isEmpty();
            assertThat(result.impactedClasses()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Factory method: withImpact()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withImpact() factory method")
    class WithImpactFactoryTests {

        @Test
        @DisplayName("Should populate impactedClasses; call-chain lists empty")
        void populatesImpactedClasses() {
            List<ClassNode> impacted = List.of(clazz("OrderService"), clazz("InvoiceService"));
            RetrievalResult result = RetrievalResult.withImpact(
                    QueryIntent.IMPACT_ANALYSIS, 0.95f, List.of(), impacted);

            assertThat(result.impactedClasses()).containsExactlyElementsOf(impacted);
            assertThat(result.callChain()).isEmpty();
            assertThat(result.callers()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Factory method: endpoints()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("endpoints() factory method")
    class EndpointsFactoryTests {

        @Test
        @DisplayName("Should populate endpointMethods only")
        void populatesEndpointMethods() {
            List<MethodNode> endpoints = List.of(method("getOrders"), method("postOrder"));
            RetrievalResult result = RetrievalResult.endpoints(endpoints);

            assertThat(result.endpointMethods()).containsExactlyElementsOf(endpoints);
            assertThat(result.semanticMatches()).isEmpty();
            assertThat(result.callChain()).isEmpty();
            assertThat(result.callers()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // totalContextSize()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("totalContextSize()")
    class TotalContextSizeTests {

        @Test
        @DisplayName("Returns zero for fully empty result")
        void zeroWhenAllListsEmpty() {
            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.5f, List.of());
            assertThat(result.totalContextSize()).isZero();
        }

        @Test
        @DisplayName("Sums all non-empty lists correctly")
        void sumsAllLists() {
            RetrievalResult result = RetrievalResult.builder(QueryIntent.DEBUG_ERROR, 0.9f)
                    .semantic(List.of(doc("a"), doc("b")))              // 2
                    .callChain(List.of(method("m1"), method("m2")))     // 2
                    .callers(List.of(method("c1")))                     // 1
                    .impacted(List.of(clazz("ClassA")))                   // 1
                    .build();

            assertThat(result.totalContextSize()).isEqualTo(6);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Builder
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Builder produces fully populated result")
        void builderPopulatesAllFields() {
            List<Document>   docs      = List.of(doc("code"));
            List<MethodNode> chain     = List.of(method("a"));
            List<MethodNode> callers   = List.of(method("b"));
            List<ClassNode>  impacted  = List.of(clazz("C"));

            RetrievalResult result = RetrievalResult.builder(QueryIntent.TRACE_CALL_CHAIN, 0.88f)
                    .semantic(docs)
                    .callChain(chain)
                    .callers(callers)
                    .impacted(impacted)
                    .build();

            assertThat(result.intent()).isEqualTo(QueryIntent.TRACE_CALL_CHAIN);
            assertThat(result.intentConfidence()).isEqualTo(0.88f);
            assertThat(result.semanticMatches()).isEqualTo(docs);
            assertThat(result.callChain()).isEqualTo(chain);
            assertThat(result.callers()).isEqualTo(callers);
            assertThat(result.impactedClasses()).isEqualTo(impacted);
        }

        @Test
        @DisplayName("Builder with no optional fields produces empty lists")
        void builderDefaults() {
            RetrievalResult result = RetrievalResult.builder(QueryIntent.FIND_ENDPOINTS, 0.7f).build();

            assertThat(result.semanticMatches()).isEmpty();
            assertThat(result.callChain()).isEmpty();
            assertThat(result.callers()).isEmpty();
            assertThat(result.impactedClasses()).isEmpty();
            assertThat(result.endpointMethods()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // summary()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("summary()")
    class SummaryTests {

        @Test
        @DisplayName("Summary string should be non-blank")
        void summaryIsNonBlank() {
            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.9f,
                    List.of(doc("void method(){}"), doc("String field")));
            assertThat(result.summary()).isNotBlank();
        }

        @Test
        @DisplayName("Summary should include semantic count")
        void summaryIncludesSemanticCount() {
            RetrievalResult result = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.9f,
                    List.of(doc("a"), doc("b"), doc("c")));
            assertThat(result.summary()).contains("3");
        }
    }
}
