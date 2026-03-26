package com.servicelens.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.retrieval.intent.IntentBasedRetriever;
import com.servicelens.retrieval.intent.RetrievalResult;
import com.servicelens.session.ConversationSession;
import com.servicelens.session.ConversationSessionService;
import com.servicelens.session.ConversationTurn;
import com.servicelens.synthesis.AnswerSynthesizer;
import com.servicelens.synthesis.SynthesisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller that exposes the full intent-based retrieval pipeline over HTTP.
 *
 * <h3>Endpoint</h3>
 * <pre>
 *   POST /api/query
 *   Content-Type: application/json
 *
 *   {
 *     "query":       "what calls processPayment?",
 *     "serviceName": "order-service"
 *   }
 * </pre>
 *
 * <p>Unlike {@code GET /api/retrieve} (which performs a flat cosine-similarity
 * search via {@link CodeRetriever}), this endpoint routes through
 * {@link IntentBasedRetriever}, which:</p>
 * <ol>
 *   <li>Classifies the query's intent (e.g. {@code TRACE_CALLERS},
 *       {@code FIND_CONFIGURATION}, {@code FIND_ENDPOINTS}).</li>
 *   <li>Dispatches to the appropriate combination of vector search, Neo4j graph
 *       traversal, and LLM reranking.</li>
 *   <li>Returns a structured {@link QueryResponse} where graph results
 *       ({@code callers}, {@code callChain}, {@code endpoints}) are kept separate
 *       from semantic results so the consumer can render them differently.</li>
 * </ol>
 *
 * <h3>Response shape</h3>
 * <pre>
 * {
 *   "intent":           "TRACE_CALLERS",
 *   "intentConfidence": 0.95,
 *   "totalContextSize": 4,
 *   "semanticMatches":  [{ "content": "...", "chunkType": "CODE", ... }],
 *   "callers":          [{ "simpleName": "checkout", "className": "CheckoutService", ... }],
 *   "callChain":        [],
 *   "impactedClasses":  [],
 *   "endpointMethods":  []
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final IntentBasedRetriever      retriever;
    private final AnswerSynthesizer         synthesizer;
    private final ConversationSessionService sessionService;

    public QueryController(IntentBasedRetriever retriever,
                           AnswerSynthesizer synthesizer,
                           ConversationSessionService sessionService) {
        this.retriever      = retriever;
        this.synthesizer    = synthesizer;
        this.sessionService = sessionService;
    }

    /**
     * Execute an intent-based query against an indexed service.
     *
     * @param request JSON body containing {@code query} and {@code serviceName}
     * @return {@link QueryResponse} with intent, confidence, and structured results
     * @throws ResponseStatusException 400 if {@code query} or {@code serviceName} is blank
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        validate(request);
        log.debug("Query request: service='{}' query='{}'", request.serviceName(), request.query());

        RetrievalResult result = retriever.retrieve(request.query(), request.serviceName());

        log.debug("Query result: {}", result.summary());
        return ResponseEntity.ok(QueryResponse.from(result));
    }

    /**
     * Execute a query and synthesize a natural-language answer from the retrieved context.
     *
     * <p>This endpoint extends {@code POST /api/query} by passing the retrieval result
     * through {@link AnswerSynthesizer}, which calls the configured Ollama model to
     * produce a grounded, intent-aware answer.  The raw retrieval result is included
     * in the response under {@code retrieval} so callers can display both the answer
     * and the supporting evidence.
     *
     * @param request JSON body containing {@code query} and {@code serviceName}
     * @return {@link AskResponse} with synthesized answer and full retrieval context
     * @throws ResponseStatusException 400 if {@code query} or {@code serviceName} is blank
     */
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@RequestBody QueryRequest request) {
        validate(request);
        log.debug("Ask request: service='{}' query='{}'", request.serviceName(), request.query());

        // ── Session resolution ────────────────────────────────────────────────
        ConversationSession session = sessionService.getOrCreate(
                request.sessionId(), request.serviceName());

        // Last 2 turns injected as conversation history
        List<ConversationTurn> history = session.history().size() > 2
                ? session.history().subList(session.history().size() - 2, session.history().size())
                : session.history();

        // ── Retrieval + synthesis ─────────────────────────────────────────────
        RetrievalResult retrieval = retriever.retrieve(request.query(), request.serviceName());
        SynthesisResult synthesis = synthesizer.synthesize(request.query(), retrieval, history);

        // ── Persist the completed turn ────────────────────────────────────────
        sessionService.addTurn(
                session.sessionId(),
                request.query(),
                synthesis.intent().name(),
                synthesis.answer());

        log.debug("Ask complete: synthesized={} intent={} sessionId={}",
                synthesis.synthesized(), synthesis.intent(), session.sessionId());

        return ResponseEntity.ok(AskResponse.from(synthesis, retrieval, session.sessionId()));
    }

    /**
     * Retrieve the conversation history for a session.
     *
     * @param sessionId the session UUID
     * @return list of prior turns, or 404 if the session does not exist
     */
    @GetMapping("/sessions/{sessionId}/history")
    public ResponseEntity<List<ConversationTurn>> sessionHistory(@PathVariable String sessionId) {
        try {
            UUID id = UUID.fromString(sessionId);
            List<ConversationTurn> history = sessionService.getHistory(id);
            return ResponseEntity.ok(history);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sessionId format");
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static void validate(QueryRequest req) {
        if (req == null || req.query() == null || req.query().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'query' must not be blank");
        if (req.serviceName() == null || req.serviceName().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'serviceName' must not be blank");
    }

    // ── Request / Response DTOs ───────────────────────────────────────────────

    /**
     * JSON request body for {@code POST /api/query} and {@code POST /api/ask}.
     *
     * @param query       the natural-language question
     * @param serviceName the logical service to search (must have been ingested first)
     * @param sessionId   optional UUID from a prior {@code /api/ask} response;
     *                    when provided the backend resumes the existing session and
     *                    injects prior conversation turns as context.  Omit or pass
     *                    {@code null} to start a fresh session.
     */
    public record QueryRequest(String query, String serviceName, String sessionId) {}

    /**
     * Structured response from the intent-based retrieval pipeline.
     *
     * <p>Different intents populate different fields — consumers should check
     * {@code intent} and render the populated collections accordingly.</p>
     *
     * @param intent            detected {@link com.servicelens.retrieval.intent.QueryIntent} name
     * @param intentConfidence  classifier confidence in [0.0, 1.0]
     * @param totalContextSize  sum of all result list sizes (useful for prompt-size budgeting)
     * @param semanticMatches   top-K semantic chunks from pgvector (reranked when applicable)
     * @param callChain         downstream methods reachable from the identified entry point
     * @param callers           methods that call the identified target method
     * @param impactedClasses   classes that depend on the identified target class
     * @param endpointMethods   HTTP endpoint methods for {@code FIND_ENDPOINTS} queries
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryResponse(
            String           intent,
            float            intentConfidence,
            int              totalContextSize,
            List<ChunkView>  semanticMatches,
            List<MethodView> callChain,
            List<MethodView> callers,
            List<ClassView>  impactedClasses,
            List<MethodView> endpointMethods
    ) {
        static QueryResponse from(RetrievalResult r) {
            return new QueryResponse(
                    r.intent().name(),
                    r.intentConfidence(),
                    r.totalContextSize(),
                    r.semanticMatches().stream().map(ChunkView::from).toList(),
                    r.callChain().stream().map(MethodView::from).toList(),
                    r.callers().stream().map(MethodView::from).toList(),
                    r.impactedClasses().stream().map(ClassView::from).toList(),
                    r.endpointMethods().stream().map(MethodView::from).toList()
            );
        }
    }

    /**
     * Slim view of a pgvector {@link Document} chunk for HTTP serialisation.
     * Avoids exposing Spring AI internal types to the API.
     */
    public record ChunkView(
            String content,
            String chunkType,
            String elementName,
            String className,
            String serviceName
    ) {
        static ChunkView from(Document doc) {
            Map<String, Object> meta = doc.getMetadata();
            return new ChunkView(
                    doc.getContent(),
                    (String) meta.get("chunk_type"),
                    (String) meta.get("element_name"),
                    (String) meta.get("class_name"),
                    (String) meta.get("service_name")
            );
        }
    }

    /**
     * Slim view of a {@link MethodNode} for HTTP serialisation.
     * Excludes heavy fields (source content, DFG, CFG metrics).
     */
    public record MethodView(
            String  simpleName,
            String  qualifiedName,
            String  className,
            boolean endpoint,
            String  httpMethod,
            String  endpointPath,
            boolean transactional
    ) {
        static MethodView from(MethodNode m) {
            return new MethodView(
                    m.getSimpleName(),
                    m.getQualifiedName(),
                    m.getClassName(),
                    m.isEndpoint(),
                    m.getHttpMethod(),
                    m.getEndpointPath(),
                    m.isTransactional()
            );
        }
    }

    /**
     * Response from {@code POST /api/ask} — synthesized answer plus full retrieval context.
     *
     * @param answer            LLM-generated natural-language answer
     * @param synthesized       {@code true} if the answer was generated from real context;
     *                          {@code false} for the no-context fallback
     * @param intent            detected intent name
     * @param intentConfidence  classifier confidence in {@code [0.0, 1.0]}
     * @param modelUsed         Ollama model name that produced the answer
     * @param contextChunksUsed number of retrieved items sent to the LLM
     * @param retrieval         the underlying retrieval result for citation / debugging
     */
    /**
     * Response from {@code POST /api/ask} — synthesized answer plus full retrieval context.
     *
     * @param answer            LLM-generated natural-language answer
     * @param synthesized       {@code true} if the answer was generated from real context
     * @param intent            detected intent name
     * @param intentConfidence  classifier confidence in {@code [0.0, 1.0]}
     * @param modelUsed         model name that produced the answer
     * @param contextChunksUsed number of retrieved items sent to the LLM
     * @param retrieval         the underlying retrieval result for citation / debugging
     * @param sessionId         the active session UUID — client should persist this and
     *                          send it back on the next request to maintain conversation context
     */
    public record AskResponse(
            String        answer,
            boolean       synthesized,
            String        intent,
            float         intentConfidence,
            String        modelUsed,
            int           contextChunksUsed,
            QueryResponse retrieval,
            String        sessionId
    ) {
        static AskResponse from(SynthesisResult synthesis, RetrievalResult retrieval, UUID sessionId) {
            return new AskResponse(
                    synthesis.answer(),
                    synthesis.synthesized(),
                    synthesis.intent().name(),
                    synthesis.intentConfidence(),
                    synthesis.modelUsed(),
                    synthesis.contextChunksUsed(),
                    QueryResponse.from(retrieval),
                    sessionId.toString()
            );
        }
    }

    /**
     * Slim view of a {@link ClassNode} for HTTP serialisation.
     */
    public record ClassView(
            String simpleName,
            String qualifiedName,
            String packageName,
            String nodeType
    ) {
        static ClassView from(ClassNode c) {
            return new ClassView(
                    c.getSimpleName(),
                    c.getQualifiedName(),
                    c.getPackageName(),
                    c.getNodeType() != null ? c.getNodeType().name() : null
            );
        }
    }
}
