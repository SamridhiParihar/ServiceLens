package com.servicelens.retrieval.intent;

import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.repository.MethodNodeRepository;
import com.servicelens.reranking.RetrievalReranker;
import com.servicelens.retrieval.CodeRetriever;
import com.servicelens.retrieval.HybridRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IntentBasedRetriever}.
 *
 * <p>All seven collaborators are mocked. Each test stubs
 * {@link IntentClassifier#classifyWithConfidence(String)} to return a specific
 * {@link QueryIntent} and then verifies that the correct downstream services
 * are invoked — and that services irrelevant to that intent are never called.</p>
 */
@DisplayName("IntentBasedRetriever")
@ExtendWith(MockitoExtension.class)
class IntentBasedRetrieverTest {

    @Mock private IntentClassifier classifier;
    @Mock private CodeRetriever vectorRetriever;
    @Mock private HybridRetriever hybridRetriever;
    @Mock private KnowledgeGraphService graphService;
    @Mock private MethodNodeRepository methodRepo;
    @Mock private RetrievalReranker reranker;
    @Mock private VectorStore vectorStore;

    private IntentBasedRetriever retriever;

    private static final String SERVICE = "order-service";
    private static final float CONFIDENCE = 0.8f;

    @BeforeEach
    void setUp() {
        retriever = new IntentBasedRetriever(
                classifier, vectorRetriever, hybridRetriever,
                graphService, methodRepo, reranker, vectorStore);
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_IMPLEMENTATION handler")
    class FindImplementationTests {

        @Test
        @DisplayName("Uses CODE vector search then metadata rerank")
        void usesCodeVectorSearchAndMetadataRerank() {
            Document doc = codeDoc("void process() {}");
            stubIntent(QueryIntent.FIND_IMPLEMENTATION);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(doc));
            when(reranker.metadataRerank(anyString(), anyList(), anyInt()))
                    .thenReturn(List.of(doc));

            RetrievalResult result = retriever.retrieve("where is process implemented", SERVICE);

            verify(vectorRetriever).retrieveCode(anyString(), eq(SERVICE), anyInt());
            verify(reranker).metadataRerank(anyString(), anyList(), anyInt());
            assertThat(result.intent()).isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
        }

        @Test
        @DisplayName("Does not call graph service")
        void doesNotCallGraphService() {
            stubIntent(QueryIntent.FIND_IMPLEMENTATION);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());
            when(reranker.metadataRerank(anyString(), anyList(), anyInt()))
                    .thenReturn(List.of());

            retriever.retrieve("where is process", SERVICE);

            verifyNoInteractions(graphService);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TRACE_CALL_CHAIN
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TRACE_CALL_CHAIN handler")
    class TraceCallChainTests {

        @Test
        @DisplayName("Finds entry point via vector then expands call chain via graph")
        void expandsCallChainViaGraph() {
            Document entryDoc = codeDoc("void placeOrder() {}", "placeOrder", "OrderService");
            MethodNode downstream = method("notify", "com.example.NotifyService.notify");

            stubIntent(QueryIntent.TRACE_CALL_CHAIN);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(entryDoc));
            when(graphService.findCallChain(anyString(), anyString()))
                    .thenReturn(List.of(downstream));

            RetrievalResult result = retriever.retrieve("what does placeOrder trigger", SERVICE);

            verify(graphService).findCallChain(contains("OrderService.placeOrder"), eq(SERVICE));
            assertThat(result.intent()).isEqualTo(QueryIntent.TRACE_CALL_CHAIN);
            assertThat(result.callChain()).contains(downstream);
        }

        @Test
        @DisplayName("Returns empty call chain when no entry point found")
        void emptyChainWhenNoEntryPoint() {
            stubIntent(QueryIntent.TRACE_CALL_CHAIN);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());

            RetrievalResult result = retriever.retrieve("call chain query", SERVICE);

            verify(graphService, never()).findCallChain(anyString(), anyString());
            assertThat(result.callChain()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TRACE_CALLERS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TRACE_CALLERS handler")
    class TraceCallersTests {

        @Test
        @DisplayName("Finds target via vector then looks up callers via graph")
        void findsCallersViaGraph() {
            Document target = codeDoc("void validateOrder() {}", "validateOrder", "OrderService");
            MethodNode caller = method("checkout", "com.example.CheckoutService.checkout");

            stubIntent(QueryIntent.TRACE_CALLERS);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(target));
            when(graphService.findCallers(anyString())).thenReturn(List.of(caller));

            RetrievalResult result = retriever.retrieve("who calls validateOrder", SERVICE);

            verify(graphService).findCallers(contains("OrderService.validateOrder"));
            assertThat(result.callers()).contains(caller);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // IMPACT_ANALYSIS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IMPACT_ANALYSIS handler")
    class ImpactAnalysisTests {

        @Test
        @DisplayName("Finds target via vector then traverses DEPENDS_ON in reverse")
        void traversesDependsOnReverse() {
            Document target = codeDoc("class PaymentService {}", null, "PaymentService");
            ClassNode dependent = new ClassNode();
            dependent.setSimpleName("OrderService");

            stubIntent(QueryIntent.IMPACT_ANALYSIS);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(target));
            when(graphService.findDependents(anyString(), anyString()))
                    .thenReturn(List.of(dependent));

            RetrievalResult result = retriever.retrieve("what breaks if I change PaymentService", SERVICE);

            verify(graphService).findDependents(eq("PaymentService"), eq(SERVICE));
            assertThat(result.impactedClasses()).contains(dependent);
        }

        @Test
        @DisplayName("Does not call findDependents when class_name metadata is absent")
        void skipsGraphWhenNoClassName() {
            Document target = codeDoc("class Something {}", null, null);

            stubIntent(QueryIntent.IMPACT_ANALYSIS);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(target));

            retriever.retrieve("impact analysis query", SERVICE);

            verify(graphService, never()).findDependents(anyString(), anyString());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_CONFIGURATION
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_CONFIGURATION handler")
    class FindConfigurationTests {

        @Test
        @DisplayName("Searches all chunk types and applies CONFIG boost")
        void searchesAllTypesWithConfigBoost() {
            Document configDoc = new Document("timeout: 30000",
                    Map.of("chunk_type", "CONFIG", "service_name", SERVICE));

            stubIntent(QueryIntent.FIND_CONFIGURATION);
            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(configDoc));
            when(reranker.boostByType(anyList(), eq("CONFIG"), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(configDoc)));

            retriever.retrieve("how is timeout configured", SERVICE);

            verify(vectorRetriever).retrieve(anyString(), eq(SERVICE), anyInt());
            verify(reranker).boostByType(anyList(), eq("CONFIG"), anyInt());
        }

        @Test
        @DisplayName("Does not call graph service")
        void doesNotCallGraph() {
            stubIntent(QueryIntent.FIND_CONFIGURATION);
            when(vectorRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());
            when(reranker.boostByType(anyList(), anyString(), anyInt()))
                    .thenReturn(new ArrayList<>());

            retriever.retrieve("config query", SERVICE);

            verifyNoInteractions(graphService);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEBUG_ERROR
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DEBUG_ERROR handler")
    class DebugErrorTests {

        @Test
        @DisplayName("Uses hybrid retrieval followed by full LLM rerank")
        void usesHybridThenFullRerank() {
            Document doc = codeDoc("void handleException(Exception e) {}",
                    "handleException", "PaymentService");
            HybridRetriever.HybridResult hybrid =
                    new HybridRetriever.HybridResult(List.of(doc), List.of());

            stubIntent(QueryIntent.DEBUG_ERROR);
            when(hybridRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(hybrid);
            when(reranker.fullRerank(anyString(), anyList(), anyInt()))
                    .thenReturn(List.of(doc));
            when(graphService.findCallers(anyString())).thenReturn(List.of());

            retriever.retrieve("payment is failing with NPE", SERVICE);

            verify(hybridRetriever).retrieve(anyString(), eq(SERVICE), anyInt());
            verify(reranker).fullRerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("Also fetches callers of top reranked chunks")
        void fetchesCallersOfTopChunks() {
            Document doc = codeDoc("void pay() {}", "pay", "PaymentService");
            HybridRetriever.HybridResult hybrid =
                    new HybridRetriever.HybridResult(List.of(doc), List.of());
            MethodNode caller = method("checkout", "com.example.CheckoutService.checkout");

            stubIntent(QueryIntent.DEBUG_ERROR);
            when(hybridRetriever.retrieve(anyString(), anyString(), anyInt()))
                    .thenReturn(hybrid);
            when(reranker.fullRerank(anyString(), anyList(), anyInt()))
                    .thenReturn(List.of(doc));
            when(graphService.findCallers(anyString())).thenReturn(List.of(caller));

            RetrievalResult result = retriever.retrieve("payment 500 error", SERVICE);

            verify(graphService, atLeastOnce()).findCallers(anyString());
            assertThat(result.callers()).contains(caller);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_ENDPOINTS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_ENDPOINTS handler")
    class FindEndpointsTests {

        @Test
        @DisplayName("Queries graph for endpoints directly without vector search on code")
        void queriesGraphDirectly() {
            MethodNode endpoint = method("createOrder", "com.example.OrderController.createOrder");
            endpoint.setEndpoint(true);
            endpoint.setHttpMethod("POST");

            stubIntent(QueryIntent.FIND_ENDPOINTS);
            when(graphService.findEndpoints(anyString())).thenReturn(List.of(endpoint));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            RetrievalResult result = retriever.retrieve("what REST endpoints exist", SERVICE);

            verify(graphService).findEndpoints(SERVICE);
            assertThat(result.endpointMethods()).contains(endpoint);
        }

        @Test
        @DisplayName("Does not call CodeRetriever.retrieveCode")
        void doesNotCallCodeRetriever() {
            stubIntent(QueryIntent.FIND_ENDPOINTS);
            when(graphService.findEndpoints(anyString())).thenReturn(List.of());
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            retriever.retrieve("what routes does this service expose", SERVICE);

            verify(vectorRetriever, never()).retrieveCode(anyString(), anyString(), anyInt());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_TESTS handler")
    class FindTestsTests {

        @Test
        @DisplayName("Searches vector store filtered to TEST chunk type")
        void searchesTestChunksOnly() {
            Document testDoc = new Document("@Test void shouldProcess() {}",
                    Map.of("chunk_type", "TEST", "service_name", SERVICE));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(testDoc));
            stubIntent(QueryIntent.FIND_TESTS);

            RetrievalResult result = retriever.retrieve("show me tests for payment", SERVICE);

            verify(vectorStore).similaritySearch(any(SearchRequest.class));
            assertThat(result.semanticMatches()).contains(testDoc);
        }

        @Test
        @DisplayName("Does not call graph service or CodeRetriever")
        void doesNotCallGraphOrCodeRetriever() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            stubIntent(QueryIntent.FIND_TESTS);

            retriever.retrieve("test coverage query", SERVICE);

            verifyNoInteractions(graphService);
            verify(vectorRetriever, never()).retrieveCode(anyString(), anyString(), anyInt());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNDERSTAND_BUSINESS_RULE
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UNDERSTAND_BUSINESS_RULE handler")
    class BusinessRuleTests {

        @Test
        @DisplayName("Searches BUSINESS_CONTEXT chunks via retrieveContext")
        void searchesBusinessContextChunks() {
            Document bizDoc = new Document("Payments retry 3 times per BR-PAY-042.",
                    Map.of("chunk_type", "BUSINESS_CONTEXT"));
            stubIntent(QueryIntent.UNDERSTAND_BUSINESS_RULE);
            when(vectorRetriever.retrieveContext(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(bizDoc));

            RetrievalResult result = retriever.retrieve("why does payment retry 3 times", SERVICE);

            verify(vectorRetriever).retrieveContext(anyString(), eq(SERVICE), anyInt());
            assertThat(result.semanticMatches()).contains(bizDoc);
        }

        @Test
        @DisplayName("Falls back to DOCUMENTATION chunks when no BUSINESS_CONTEXT found")
        void fallsBackToDocumentationWhenEmpty() {
            stubIntent(QueryIntent.UNDERSTAND_BUSINESS_RULE);
            when(vectorRetriever.retrieveContext(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            retriever.retrieve("why is this the rule", SERVICE);

            // retrieveContext called first, then falls back to vectorStore for DOCUMENTATION
            verify(vectorRetriever).retrieveContext(anyString(), eq(SERVICE), anyInt());
            verify(vectorStore).similaritySearch(any(SearchRequest.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Intent is always recorded on the result
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Intent is propagated to RetrievalResult for all intents")
    class IntentPropagationTests {

        @Test
        @DisplayName("FIND_IMPLEMENTATION intent is present in result")
        void findImplementationIntentPropagated() {
            stubIntent(QueryIntent.FIND_IMPLEMENTATION);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());
            when(reranker.metadataRerank(anyString(), anyList(), anyInt()))
                    .thenReturn(List.of());

            assertThat(retriever.retrieve("q", SERVICE).intent())
                    .isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
        }

        @Test
        @DisplayName("NULL_SAFETY intent is present in result")
        void nullSafetyIntentPropagated() {
            stubIntent(QueryIntent.NULL_SAFETY);
            when(vectorRetriever.retrieveCode(anyString(), anyString(), anyInt()))
                    .thenReturn(List.of());

            assertThat(retriever.retrieve("could order be null here", SERVICE).intent())
                    .isEqualTo(QueryIntent.NULL_SAFETY);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubIntent(QueryIntent intent) {
        when(classifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentClassifier.IntentResult(intent, CONFIDENCE));
    }

    private static Document codeDoc(String content) {
        return codeDoc(content, null, null);
    }

    private static Document codeDoc(String content, String elementName, String className) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunk_type", "CODE");
        if (elementName != null) meta.put("element_name", elementName);
        if (className   != null) meta.put("class_name",   className);
        return new Document(content, meta);
    }

    private static MethodNode method(String simpleName, String qualifiedName) {
        MethodNode m = new MethodNode();
        m.setSimpleName(simpleName);
        m.setQualifiedName(qualifiedName);
        return m;
    }
}
