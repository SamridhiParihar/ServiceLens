package com.servicelens.retrieval;

import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HybridRetriever}.
 *
 * <p>The three collaborators ({@link CodeRetriever} and {@link KnowledgeGraphService})
 * are replaced with Mockito mocks, allowing tests to stay fast, deterministic,
 * and free of any database or embedding-model dependencies.</p>
 *
 * <p>Test scenarios:</p>
 * <ul>
 *   <li>Layer-1 semantic search is always invoked with the correct parameters.</li>
 *   <li>Layer-2 graph expansion runs only when {@code element_name} and
 *       {@code class_name} metadata are present in the semantic results.</li>
 *   <li>Layer-3 deduplication removes graph-expanded nodes with duplicate
 *       qualified names.</li>
 *   <li>Empty semantic results produce an empty graph expansion.</li>
 *   <li>Impact analysis ({@link HybridRetriever#findImpact}) delegates to
 *       {@link KnowledgeGraphService#findDependents}.</li>
 *   <li>{@link HybridRetriever.HybridResult#totalContext()} returns the correct sum.</li>
 * </ul>
 */
@DisplayName("HybridRetriever")
@ExtendWith(MockitoExtension.class)
class HybridRetrieverTest {

    @Mock
    private CodeRetriever vectorRetriever;

    @Mock
    private KnowledgeGraphService graphService;

    /** The component under test. */
    private HybridRetriever hybridRetriever;

    private static final String SERVICE = "payment-service";

    @BeforeEach
    void setUp() {
        hybridRetriever = new HybridRetriever(vectorRetriever, graphService);
    }

    // ─────────────────────────────────────────────────────────────────────
    // retrieve() — Layer 1: semantic search
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieve() — Layer 1: semantic search")
    class SemanticLayerTests {

        @Test
        @DisplayName("Delegates vector search to CodeRetriever with correct query, service, and topK")
        void delegatesVectorSearchCorrectly() {
            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());

            hybridRetriever.retrieve("how does checkout work?", SERVICE, 8);

            verify(vectorRetriever).retrieve("how does checkout work?", SERVICE, 8);
        }

        @Test
        @DisplayName("Semantic results are included in the HybridResult")
        void semanticResultsIncludedInResult() {
            Document doc = doc("public void checkout() {}", null, null);
            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(doc));

            HybridRetriever.HybridResult result =
                    hybridRetriever.retrieve("checkout flow", SERVICE, 5);

            assertThat(result.semanticMatches()).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // retrieve() — Layer 2: graph expansion
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieve() — Layer 2: graph expansion")
    class GraphExpansionTests {

        @Test
        @DisplayName("Graph expansion runs when element_name and class_name metadata are present")
        void graphExpansionRunsWhenMetadataPresent() {
            Document doc = doc("void processPayment() {}", "processPayment", "PaymentService");
            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(doc));
            when(graphService.findCallers(anyString())).thenReturn(List.of());
            when(graphService.findCallChain(anyString(), anyString())).thenReturn(List.of());

            hybridRetriever.retrieve("process payment", SERVICE, 5);

            verify(graphService).findCallers(contains("PaymentService.processPayment"));
            verify(graphService).findCallChain(contains("PaymentService.processPayment"), eq(SERVICE));
        }

        @Test
        @DisplayName("Graph expansion is skipped when element_name metadata is absent")
        void graphExpansionSkippedWhenNoElementName() {
            Document doc = doc("void doSomething() {}", null, null);
            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(doc));

            hybridRetriever.retrieve("query", SERVICE, 5);

            verify(graphService, never()).findCallers(any());
            verify(graphService, never()).findCallChain(any(), any());
        }

        @Test
        @DisplayName("Graph-expanded method nodes are included in the HybridResult")
        void graphExpansionNodesIncludedInResult() {
            Document doc = doc("void pay() {}", "pay", "PaymentService");
            MethodNode callerNode = method("checkout", "com.example.CheckoutController.checkout");

            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(doc));
            when(graphService.findCallers(anyString())).thenReturn(List.of(callerNode));
            when(graphService.findCallChain(anyString(), anyString())).thenReturn(List.of());

            HybridRetriever.HybridResult result =
                    hybridRetriever.retrieve("payment", SERVICE, 5);

            assertThat(result.graphExpansion()).hasSize(1);
            assertThat(result.graphExpansion().get(0).getSimpleName()).isEqualTo("checkout");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // retrieve() — Layer 3: deduplication
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieve() — Layer 3: deduplication")
    class DeduplicationTests {

        @Test
        @DisplayName("Duplicate qualified names are removed from graph expansion")
        void duplicateQNsAreRemoved() {
            Document doc1 = doc("void a() {}", "a", "ServiceA");
            Document doc2 = doc("void b() {}", "b", "ServiceB");

            // Both documents expand to the same method node
            MethodNode sharedNode = method("shared", "com.example.Shared.shared");

            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(doc1, doc2));
            when(graphService.findCallers(anyString())).thenReturn(List.of(sharedNode));
            when(graphService.findCallChain(anyString(), anyString())).thenReturn(List.of());

            HybridRetriever.HybridResult result =
                    hybridRetriever.retrieve("query", SERVICE, 5);

            // The shared node should appear only once
            long countShared = result.graphExpansion().stream()
                    .filter(m -> "com.example.Shared.shared".equals(m.getQualifiedName()))
                    .count();
            assertThat(countShared).isEqualTo(1);
        }

        @Test
        @DisplayName("Distinct methods from graph expansion are all preserved")
        void distinctMethodsArePreserved() {
            Document doc = doc("void entry() {}", "entry", "Controller");

            MethodNode caller = method("checkout", "com.example.Controller.checkout");
            MethodNode callChainMethod = method("validate", "com.example.Service.validate");

            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(doc));
            when(graphService.findCallers(anyString())).thenReturn(List.of(caller));
            when(graphService.findCallChain(anyString(), anyString()))
                    .thenReturn(List.of(callChainMethod));

            HybridRetriever.HybridResult result =
                    hybridRetriever.retrieve("query", SERVICE, 5);

            assertThat(result.graphExpansion()).hasSize(2);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Empty semantic results
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty semantic results")
    class EmptySemanticTests {

        @Test
        @DisplayName("Empty semantic results produce empty HybridResult with no graph calls")
        void emptySemanticProducesEmptyResult() {
            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());

            HybridRetriever.HybridResult result =
                    hybridRetriever.retrieve("irrelevant query", SERVICE, 5);

            assertThat(result.semanticMatches()).isEmpty();
            assertThat(result.graphExpansion()).isEmpty();
            verify(graphService, never()).findCallers(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // findImpact()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findImpact()")
    class FindImpactTests {

        @Test
        @DisplayName("Delegates to KnowledgeGraphService.findDependents with correct arguments")
        void delegatesToFindDependents() {
            ClassNode dep = new ClassNode();
            dep.setSimpleName("OrderService");
            when(graphService.findDependents(anyString(), anyString()))
                    .thenReturn(List.of(dep));

            List<ClassNode> result = hybridRetriever.findImpact(
                    "com.example.PaymentService", SERVICE);

            verify(graphService).findDependents("com.example.PaymentService", SERVICE);
            assertThat(result).containsExactly(dep);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HybridResult record
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HybridResult.totalContext()")
    class TotalContextTests {

        @Test
        @DisplayName("totalContext() returns sum of semantic and graph counts")
        void totalContextIsSumOfBoth() {
            Document doc1 = doc("a", null, null);
            Document doc2 = doc("b", null, null);
            MethodNode m1 = method("m1", "com.example.A.m1");
            MethodNode m2 = method("m2", "com.example.B.m2");
            MethodNode m3 = method("m3", "com.example.C.m3");

            HybridRetriever.HybridResult result =
                    new HybridRetriever.HybridResult(List.of(doc1, doc2), List.of(m1, m2, m3));

            assertThat(result.totalContext()).isEqualTo(5);
        }

        @Test
        @DisplayName("totalContext() returns zero when both lists are empty")
        void totalContextZeroWhenBothEmpty() {
            HybridRetriever.HybridResult result =
                    new HybridRetriever.HybridResult(List.of(), List.of());

            assertThat(result.totalContext()).isZero();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Build a Spring AI {@link Document} with optional {@code element_name} and
     * {@code class_name} metadata entries, which drive graph expansion in Layer 2.
     *
     * @param content     the document text content
     * @param elementName the method name metadata value, or {@code null} to omit
     * @param className   the class name metadata value, or {@code null} to omit
     * @return a populated {@link Document}
     */
    private static Document doc(String content, String elementName, String className) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunk_type", "CODE");
        if (elementName != null) meta.put("element_name", elementName);
        if (className   != null) meta.put("class_name",   className);
        return new Document(content, meta);
    }

    /**
     * Build a minimal {@link MethodNode} with simple and qualified names set.
     *
     * @param simpleName     the short method name
     * @param qualifiedName  the fully qualified method name (used for deduplication)
     * @return a populated {@link MethodNode}
     */
    private static MethodNode method(String simpleName, String qualifiedName) {
        MethodNode m = new MethodNode();
        m.setSimpleName(simpleName);
        m.setQualifiedName(qualifiedName);
        return m;
    }
}
