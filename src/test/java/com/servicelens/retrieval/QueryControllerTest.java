package com.servicelens.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.domain.NodeType;
import com.servicelens.retrieval.intent.IntentBasedRetriever;
import com.servicelens.retrieval.intent.QueryIntent;
import com.servicelens.retrieval.intent.RetrievalResult;
import com.servicelens.session.ConversationSession;
import com.servicelens.session.ConversationSessionService;
import com.servicelens.session.ConversationTurn;
import com.servicelens.session.HybridMemoryAssembler;
import com.servicelens.synthesis.AnswerSynthesizer;
import com.servicelens.synthesis.SynthesisResult;
import com.servicelens.synthesis.VerbosityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link QueryController}.
 *
 * <p>Uses {@code @WebMvcTest} — loads only the web layer (Jackson, MockMvc,
 * exception handling).  {@link IntentBasedRetriever} is replaced with a Mockito
 * mock so no Spring AI, pgvector, or Neo4j infrastructure is needed.</p>
 */
@WebMvcTest(QueryController.class)
@DisplayName("QueryController — unit")
class QueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IntentBasedRetriever retriever;

    @MockitoBean
    private AnswerSynthesizer synthesizer;

    @MockitoBean
    private ConversationSessionService sessionService;

    @MockitoBean
    private HybridMemoryAssembler hybridMemory;

    private static final String URL      = "/api/query";
    private static final String ASK_URL  = "/api/ask";
    private static final UUID   TEST_SID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void stubSession() {
        ConversationSession session = new ConversationSession(
                TEST_SID, "order-svc", List.of(), Instant.now(), Instant.now());
        lenient().when(sessionService.getOrCreate(any(), any())).thenReturn(session);
        lenient().doNothing().when(sessionService).addTurn(any(), any(), any(), any(), any());
        lenient().when(hybridMemory.assemble(any(), any(), any())).thenReturn(List.of());
    }

    // ── Happy-path — semantic result ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/query — success cases")
    class SuccessCases {

        @Test
        @DisplayName("Returns 200 with intent name and intentConfidence")
        void returns200WithIntentFields() throws Exception {
            when(retriever.retrieve("where is processPayment implemented", "order-svc"))
                    .thenReturn(RetrievalResult.semantic(
                            QueryIntent.FIND_IMPLEMENTATION, 0.95f, List.of()));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("where is processPayment implemented", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.intent").value("FIND_IMPLEMENTATION"))
               .andExpect(jsonPath("$.intentConfidence").value(0.95));
        }

        @Test
        @DisplayName("semanticMatches mapped to ChunkView with chunkType and content")
        void semanticMatchesMappedToChunkView() throws Exception {
            Document doc = new Document("public void processPayment() {}",
                    Map.of("chunk_type", "CODE",
                           "element_name", "processPayment",
                           "class_name", "PaymentService",
                           "service_name", "order-svc"));

            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(
                            QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of(doc)));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("show me processPayment", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.semanticMatches").isArray())
               .andExpect(jsonPath("$.semanticMatches[0].content").value("public void processPayment() {}"))
               .andExpect(jsonPath("$.semanticMatches[0].chunkType").value("CODE"))
               .andExpect(jsonPath("$.semanticMatches[0].elementName").value("processPayment"))
               .andExpect(jsonPath("$.semanticMatches[0].className").value("PaymentService"));
        }

        @Test
        @DisplayName("callChain mapped to MethodView list")
        void callChainMappedToMethodView() throws Exception {
            MethodNode callee = methodNode("processPayment", "com.example.PaymentService.processPayment",
                    "PaymentService", false, null, null);

            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.withCallChain(
                            QueryIntent.TRACE_CALL_CHAIN, 0.88f, List.of(), List.of(callee)));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("what does createOrder call", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.intent").value("TRACE_CALL_CHAIN"))
               .andExpect(jsonPath("$.callChain").isArray())
               .andExpect(jsonPath("$.callChain[0].simpleName").value("processPayment"))
               .andExpect(jsonPath("$.callChain[0].className").value("PaymentService"));
        }

        @Test
        @DisplayName("callers mapped to MethodView list")
        void callersMappedToMethodView() throws Exception {
            MethodNode caller = methodNode("checkout", "com.example.CheckoutService.checkout",
                    "CheckoutService", false, null, null);

            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.withCallers(
                            QueryIntent.TRACE_CALLERS, 0.90f, List.of(), List.of(caller)));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("who calls processPayment", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.intent").value("TRACE_CALLERS"))
               .andExpect(jsonPath("$.callers[0].simpleName").value("checkout"));
        }

        @Test
        @DisplayName("impactedClasses mapped to ClassView list")
        void impactedClassesMappedToClassView() throws Exception {
            ClassNode dependent = classNode("com.example.OrderService", "OrderService",
                    "com.example", NodeType.CLASS);

            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.withImpact(
                            QueryIntent.IMPACT_ANALYSIS, 0.85f, List.of(), List.of(dependent)));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("what depends on PaymentService", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.intent").value("IMPACT_ANALYSIS"))
               .andExpect(jsonPath("$.impactedClasses[0].simpleName").value("OrderService"))
               .andExpect(jsonPath("$.impactedClasses[0].nodeType").value("CLASS"));
        }

        @Test
        @DisplayName("endpointMethods include httpMethod and endpointPath")
        void endpointMethodsIncludeHttpDetails() throws Exception {
            MethodNode endpoint = methodNode("createOrder",
                    "com.example.OrderController.createOrder",
                    "OrderController", true, "POST", "/orders");

            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.endpoints(List.of(endpoint)));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("what REST endpoints does this service expose", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.intent").value("FIND_ENDPOINTS"))
               .andExpect(jsonPath("$.endpointMethods[0].httpMethod").value("POST"))
               .andExpect(jsonPath("$.endpointMethods[0].endpointPath").value("/orders"))
               .andExpect(jsonPath("$.endpointMethods[0].endpoint").value(true));
        }

        @Test
        @DisplayName("totalContextSize equals sum of all result list sizes")
        void totalContextSizeIsCorrect() throws Exception {
            MethodNode m1 = methodNode("m1", "a.b.C.m1", "C", false, null, null);
            MethodNode m2 = methodNode("m2", "a.b.C.m2", "C", false, null, null);
            Document   d1 = new Document("code", Map.of());

            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.builder(QueryIntent.TRACE_CALL_CHAIN, 0.9f)
                            .semantic(List.of(d1))
                            .callChain(List.of(m1, m2))
                            .build());

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("call chain query", "svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalContextSize").value(3)); // 1 semantic + 2 chain
        }

        @Test
        @DisplayName("Empty result lists are serialised as empty arrays, not null")
        void emptyListsSerializeAsArraysNotNull() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(QueryIntent.FIND_IMPLEMENTATION, 0.5f, List.of()));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("general question", "svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.semanticMatches").isArray())
               .andExpect(jsonPath("$.callers").isArray())
               .andExpect(jsonPath("$.callChain").isArray())
               .andExpect(jsonPath("$.impactedClasses").isArray())
               .andExpect(jsonPath("$.endpointMethods").isArray());
        }

        @Test
        @DisplayName("Retriever is called with the exact query and serviceName from the request")
        void retrieverCalledWithCorrectArguments() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(QueryIntent.FIND_IMPLEMENTATION, 0.5f, List.of()));

            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("my specific query", "my-service")))
               .andExpect(status().isOk());

            verify(retriever).retrieve("my specific query", "my-service");
        }
    }

    // ── Validation — 400 cases ────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/query — validation errors")
    class ValidationCases {

        @Test
        @DisplayName("Blank query returns 400")
        void blankQuery_returns400() throws Exception {
            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("", "order-svc")))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(retriever);
        }

        @Test
        @DisplayName("Whitespace-only query returns 400")
        void whitespaceQuery_returns400() throws Exception {
            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("   ", "order-svc")))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(retriever);
        }

        @Test
        @DisplayName("Blank serviceName returns 400")
        void blankServiceName_returns400() throws Exception {
            mvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("valid query", "")))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(retriever);
        }

        @Test
        @DisplayName("Missing Content-Type returns 415")
        void missingContentType_returns415() throws Exception {
            mvc.perform(post(URL)
                            .content(body("valid query", "order-svc")))
               .andExpect(status().isUnsupportedMediaType());
        }
    }

    // ── POST /api/ask ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/ask")
    class AskEndpointTests {

        @Test
        @DisplayName("Returns 200 with answer, synthesized flag, and sessionId")
        void returns200WithAnswerSynthesizedFlagAndSessionId() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(
                            QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of()));
            when(synthesizer.synthesize(anyString(), any(), any(), any()))
                    .thenReturn(new SynthesisResult(
                            "The executionLoop runs up to 3 times.",
                            QueryIntent.FIND_IMPLEMENTATION, 0.9f, "phi3", 2, true));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("how does the loop work?", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.answer").value("The executionLoop runs up to 3 times."))
               .andExpect(jsonPath("$.synthesized").value(true))
               .andExpect(jsonPath("$.intent").value("FIND_IMPLEMENTATION"))
               .andExpect(jsonPath("$.modelUsed").value("phi3"))
               .andExpect(jsonPath("$.contextChunksUsed").value(2))
               .andExpect(jsonPath("$.sessionId").value(TEST_SID.toString()));
        }

        @Test
        @DisplayName("Response includes nested retrieval result")
        void responseIncludesNestedRetrievalResult() throws Exception {
            Document doc = new Document("void loop() {}",
                    Map.of("chunk_type", "CODE", "element_name", "loop",
                           "class_name", "Orchestrator", "service_name", "svc"));

            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(
                            QueryIntent.FIND_IMPLEMENTATION, 0.85f, List.of(doc)));
            when(synthesizer.synthesize(anyString(), any(), any(), any()))
                    .thenReturn(new SynthesisResult(
                            "Answer here.", QueryIntent.FIND_IMPLEMENTATION, 0.85f, "phi3", 1, true));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("explain loop", "svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.retrieval.intent").value("FIND_IMPLEMENTATION"))
               .andExpect(jsonPath("$.retrieval.semanticMatches[0].elementName").value("loop"));
        }

        @Test
        @DisplayName("No-context fallback: synthesized=false, answer contains guidance message")
        void noContextFallback_synthesizedFalse() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(
                            QueryIntent.FIND_IMPLEMENTATION, 0.5f, List.of()));
            when(synthesizer.synthesize(anyString(), any(), any(), any()))
                    .thenReturn(SynthesisResult.noContext(QueryIntent.FIND_IMPLEMENTATION, 0.5f));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("unknown query", "empty-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.synthesized").value(false))
               .andExpect(jsonPath("$.answer").isString());
        }

        @Test
        @DisplayName("SHORT verbosity is passed to synthesizer and stored in turn")
        void shortVerbosity_passedToSynthesizerAndStoredInTurn() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of()));
            when(synthesizer.synthesize(anyString(), any(), any(), eq(VerbosityLevel.SHORT)))
                    .thenReturn(new SynthesisResult(
                            "Short answer.", QueryIntent.FIND_IMPLEMENTATION, 0.9f, "phi3", 0, true));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWithVerbosity("quick question", "svc", VerbosityLevel.SHORT)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.answer").value("Short answer."));

            verify(synthesizer).synthesize(anyString(), any(), any(), eq(VerbosityLevel.SHORT));
            verify(sessionService).addTurn(any(), any(), any(), any(), eq("SHORT"));
        }

        @Test
        @DisplayName("DEEP_DIVE verbosity is passed to synthesizer and stored in turn")
        void deepDiveVerbosity_passedToSynthesizerAndStoredInTurn() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of()));
            when(synthesizer.synthesize(anyString(), any(), any(), eq(VerbosityLevel.DEEP_DIVE)))
                    .thenReturn(new SynthesisResult(
                            "Deep answer.", QueryIntent.FIND_IMPLEMENTATION, 0.9f, "phi3", 0, true));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWithVerbosity("deep question", "svc", VerbosityLevel.DEEP_DIVE)))
               .andExpect(status().isOk());

            verify(synthesizer).synthesize(anyString(), any(), any(), eq(VerbosityLevel.DEEP_DIVE));
            verify(sessionService).addTurn(any(), any(), any(), any(), eq("DEEP_DIVE"));
        }

        @Test
        @DisplayName("Null verbosity defaults to DETAILED")
        void nullVerbosity_defaultsToDetailed() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of()));
            when(synthesizer.synthesize(anyString(), any(), any(), eq(VerbosityLevel.DETAILED)))
                    .thenReturn(new SynthesisResult(
                            "Detailed answer.", QueryIntent.FIND_IMPLEMENTATION, 0.9f, "phi3", 0, true));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("question", "svc")))
               .andExpect(status().isOk());

            verify(synthesizer).synthesize(anyString(), any(), any(), eq(VerbosityLevel.DETAILED));
            verify(sessionService).addTurn(any(), any(), any(), any(), eq("DETAILED"));
        }

        @Test
        @DisplayName("Blank query returns 400 — same validation as /api/query")
        void blankQuery_returns400() throws Exception {
            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("", "order-svc")))
               .andExpect(status().isBadRequest());

            verifyNoInteractions(retriever, synthesizer);
        }

        @Test
        @DisplayName("Retriever and Synthesizer both called with correct arguments")
        void retrieverAndSynthesizerCalledWithCorrectArguments() throws Exception {
            RetrievalResult retrieval = RetrievalResult.semantic(
                    QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of());

            when(retriever.retrieve("my query", "my-svc")).thenReturn(retrieval);
            when(synthesizer.synthesize(eq("my query"), eq(retrieval), any(), any()))
                    .thenReturn(new SynthesisResult(
                            "answer", QueryIntent.FIND_IMPLEMENTATION, 0.9f, "phi3", 0, true));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("my query", "my-svc")))
               .andExpect(status().isOk());

            verify(retriever).retrieve("my query", "my-svc");
            verify(synthesizer).synthesize(eq("my query"), eq(retrieval), any(), any());
        }

        @Test
        @DisplayName("Session turn is recorded after successful synthesis")
        void sessionTurnRecordedAfterSynthesis() throws Exception {
            when(retriever.retrieve(anyString(), anyString()))
                    .thenReturn(RetrievalResult.semantic(
                            QueryIntent.FIND_IMPLEMENTATION, 0.9f, List.of()));
            when(synthesizer.synthesize(anyString(), any(), any(), any()))
                    .thenReturn(new SynthesisResult(
                            "The answer.", QueryIntent.FIND_IMPLEMENTATION, 0.9f, "phi3", 1, true));

            mvc.perform(post(ASK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("my query", "order-svc")))
               .andExpect(status().isOk());

            verify(sessionService).addTurn(
                    eq(TEST_SID), eq("my query"), eq("FIND_IMPLEMENTATION"), eq("The answer."), eq("DETAILED"));
        }
    }

    // ── GET /api/sessions/{id}/history ───────────────────────────────────────

    @Nested
    @DisplayName("GET /api/sessions/{id}/history")
    class SessionHistoryEndpoint {

        @Test
        @DisplayName("Returns 200 with history list for valid sessionId")
        void validSessionId_returns200WithHistory() throws Exception {
            List<ConversationTurn> turns = List.of(
                    new ConversationTurn("what calls X?", "TRACE_CALLERS", "It is called by Y.", "DETAILED"),
                    new ConversationTurn("why does it fail?", "DEBUG_ERROR", "The root cause is Z.", "SHORT")
            );
            when(sessionService.getHistory(TEST_SID)).thenReturn(turns);

            mvc.perform(get("/api/sessions/" + TEST_SID + "/history"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$.length()").value(2))
               .andExpect(jsonPath("$[0].query").value("what calls X?"))
               .andExpect(jsonPath("$[1].intent").value("DEBUG_ERROR"));
        }

        @Test
        @DisplayName("Returns empty array when session has no history")
        void emptyHistory_returnsEmptyArray() throws Exception {
            when(sessionService.getHistory(TEST_SID)).thenReturn(List.of());

            mvc.perform(get("/api/sessions/" + TEST_SID + "/history"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Returns 400 for invalid UUID format")
        void invalidUuid_returns400() throws Exception {
            mvc.perform(get("/api/sessions/not-a-uuid/history"))
               .andExpect(status().isBadRequest());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String body(String query, String serviceName) throws Exception {
        return objectMapper.writeValueAsString(
                new QueryController.QueryRequest(query, serviceName, null, null));
    }

    private String bodyWithVerbosity(String query, String serviceName, VerbosityLevel verbosity) throws Exception {
        return objectMapper.writeValueAsString(
                new QueryController.QueryRequest(query, serviceName, null, verbosity));
    }

    private static MethodNode methodNode(String simpleName, String qualifiedName,
                                         String className, boolean endpoint,
                                         String httpMethod, String endpointPath) {
        MethodNode n = new MethodNode();
        n.setSimpleName(simpleName);
        n.setQualifiedName(qualifiedName);
        n.setClassName(className);
        n.setEndpoint(endpoint);
        n.setHttpMethod(httpMethod);
        n.setEndpointPath(endpointPath);
        n.setTransactional(false);
        return n;
    }

    private static ClassNode classNode(String qualifiedName, String simpleName,
                                       String packageName, NodeType nodeType) {
        ClassNode n = new ClassNode();
        n.setQualifiedName(qualifiedName);
        n.setSimpleName(simpleName);
        n.setPackageName(packageName);
        n.setNodeType(nodeType);
        return n;
    }
}
