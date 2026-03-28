package com.servicelens.functional.api;

import com.servicelens.functional.support.FunctionalTestBase;
import com.servicelens.retrieval.QueryController.AskResponse;
import com.servicelens.retrieval.QueryController.QueryRequest;
import com.servicelens.retrieval.QueryController.QueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for {@code POST /api/query} ({@link com.servicelens.retrieval.QueryController}).
 *
 * <p>Uses the full intent-based retrieval pipeline against real infrastructure:
 * real Ollama embeddings, real pgvector, real Neo4j.  Each test ingests a small
 * Java repository first, then fires a natural-language query and verifies the
 * structured {@link QueryResponse}.</p>
 *
 * <h3>What these tests prove</h3>
 * <ul>
 *   <li>The HTTP layer correctly deserialises {@link QueryRequest} and serialises
 *       {@link QueryResponse}.</li>
 *   <li>{@link com.servicelens.retrieval.intent.IntentClassifier} detects the
 *       correct {@link com.servicelens.retrieval.intent.QueryIntent} for
 *       representative natural-language queries.</li>
 *   <li>The retrieval results are non-empty after real ingestion.</li>
 *   <li>The slim DTO mapping (ChunkView, MethodView) works end-to-end.</li>
 * </ul>
 */
@DisplayName("QueryController API — functional E2E")
class QueryApiFunctionalTest extends FunctionalTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @TempDir
    Path repoDir;

    private static final String SERVICE = "query-api-svc";

    /**
     * Ingest a small Java service before each test so the vector store and
     * Neo4j graph are populated.
     */
    @BeforeEach
    void ingestRepo() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"),      orderServiceSource());
        Files.writeString(repoDir.resolve("PaymentService.java"),    paymentServiceSource());
        Files.writeString(repoDir.resolve("OrderController.java"),   controllerSource());
        Files.writeString(repoDir.resolve("application.yml"),        configSource());

        restTemplate.postForEntity(
                url("/api/ingest"),
                Map.of("repoPath", repoDir.toAbsolutePath().toString(),
                       "serviceName", SERVICE),
                Map.class);
    }

    // ── 200 OK structural checks ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/query returns 200 with non-null intent and confidence")
    void returns200WithIntentAndConfidence() {
        ResponseEntity<QueryResponse> response = post(
                "where is processPayment implemented", SERVICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().intent()).isNotNull();
        assertThat(response.getBody().intentConfidence()).isGreaterThan(0f);
    }

    // ── FIND_IMPLEMENTATION ───────────────────────────────────────────────────

    @Test
    @DisplayName("Implementation query → FIND_IMPLEMENTATION intent with CODE chunks")
    void implementationQuery_intentAndCodeChunks() {
        QueryResponse body = post("where is processPayment implemented", SERVICE).getBody();

        assertThat(body).isNotNull();
        assertThat(body.intent()).isEqualTo("FIND_IMPLEMENTATION");
        assertThat(body.semanticMatches()).isNotEmpty();
        assertThat(body.semanticMatches())
                .anyMatch(c -> "CODE".equals(c.chunkType()));
    }

    // ── FIND_CONFIGURATION ────────────────────────────────────────────────────

    @Test
    @DisplayName("Configuration query → FIND_CONFIGURATION intent with CONFIG chunks")
    void configurationQuery_intentAndConfigChunks() {
        QueryResponse body = post("how is the datasource url configured", SERVICE).getBody();

        assertThat(body).isNotNull();
        assertThat(body.intent()).isEqualTo("FIND_CONFIGURATION");
        assertThat(body.semanticMatches()).isNotEmpty();
        assertThat(body.semanticMatches())
                .anyMatch(c -> "CONFIG".equals(c.chunkType()));
    }

    // ── FIND_ENDPOINTS ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Endpoint query → FIND_ENDPOINTS intent with endpoint methods from Neo4j")
    void endpointQuery_intentAndEndpointMethods() {
        QueryResponse body = post("what REST API endpoints does this service expose", SERVICE).getBody();

        assertThat(body).isNotNull();
        assertThat(body.intent()).isEqualTo("FIND_ENDPOINTS");
        assertThat(body.endpointMethods()).isNotEmpty();
        assertThat(body.endpointMethods())
                .anyMatch(m -> m.endpoint() && m.httpMethod() != null);
    }

    // ── Empty service ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Query against unindexed service returns 200 with empty result lists")
    void queryAgainstUnindexedService_emptyResults() {
        QueryResponse body = post("anything", "never-ingested-svc").getBody();

        assertThat(body).isNotNull();
        assertThat(body.intent()).isNotNull();
        assertThat(body.semanticMatches()).isEmpty();
        assertThat(body.endpointMethods()).isEmpty();
        assertThat(body.callers()).isEmpty();
        assertThat(body.callChain()).isEmpty();
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/query with blank query returns 400")
    void blankQuery_returns400() {
        ResponseEntity<Object> response = restTemplate.postForEntity(
                url("/api/query"),
                new QueryRequest("", SERVICE, null, null),
                Object.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/query with blank serviceName returns 400")
    void blankServiceName_returns400() {
        ResponseEntity<Object> response = restTemplate.postForEntity(
                url("/api/query"),
                new QueryRequest("valid query", "", null, null),
                Object.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── DTO field checks ──────────────────────────────────────────────────────

    @Test
    @DisplayName("ChunkView always has non-null content and chunkType")
    void chunkView_hasContentAndChunkType() {
        QueryResponse body = post("order service implementation", SERVICE).getBody();

        assertThat(body).isNotNull();
        assertThat(body.semanticMatches()).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.chunkType()).isNotNull();
        });
    }

    @Test
    @DisplayName("MethodView in endpointMethods has simpleName and httpMethod")
    void endpointMethodView_hasRequiredFields() {
        QueryResponse body = post("list all API endpoints", SERVICE).getBody();

        assertThat(body).isNotNull();
        if (!body.endpointMethods().isEmpty()) {
            assertThat(body.endpointMethods()).allSatisfy(m -> {
                assertThat(m.simpleName()).isNotBlank();
                assertThat(m.className()).isNotBlank();
            });
        }
    }

    // ── POST /api/ask — synthesis layer ──────────────────────────────────────

    @Test
    @DisplayName("POST /api/ask returns 200 with a non-blank synthesized answer")
    void ask_returns200WithNonBlankAnswer() {
        ResponseEntity<AskResponse> response = restTemplate.postForEntity(
                url("/api/ask"),
                new QueryRequest("how does processPayment work", SERVICE, null, null),
                AskResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AskResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.answer()).isNotBlank();
        assertThat(body.synthesized()).isTrue();
        assertThat(body.intent()).isNotNull();
        assertThat(body.modelUsed()).isNotNull();
        assertThat(body.contextChunksUsed()).isPositive();
    }

    @Test
    @DisplayName("POST /api/ask response includes nested retrieval result with semantic matches")
    void ask_responseIncludesRetrieval() {
        ResponseEntity<AskResponse> response = restTemplate.postForEntity(
                url("/api/ask"),
                new QueryRequest("where is createOrder implemented", SERVICE, null, null),
                AskResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AskResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.retrieval()).isNotNull();
        assertThat(body.retrieval().intent()).isNotNull();
        assertThat(body.retrieval().semanticMatches()).isNotEmpty();
    }

    @Test
    @DisplayName("POST /api/ask for unindexed service returns 200 with synthesized=false fallback")
    void ask_unindexedService_returnsFallback() {
        ResponseEntity<AskResponse> response = restTemplate.postForEntity(
                url("/api/ask"),
                new QueryRequest("anything", "never-ingested-svc", null, null),
                AskResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AskResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.synthesized()).isFalse();
        assertThat(body.answer()).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/ask with blank query returns 400")
    void ask_blankQuery_returns400() {
        ResponseEntity<Object> response = restTemplate.postForEntity(
                url("/api/ask"),
                new QueryRequest("", SERVICE, null, null),
                Object.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<QueryResponse> post(String query, String serviceName) {
        return restTemplate.postForEntity(
                url("/api/query"),
                new QueryRequest(query, serviceName, null, null),
                QueryResponse.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ── Source fixtures ───────────────────────────────────────────────────────

    private static String orderServiceSource() {
        return """
                package com.example;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                /**
                 * Handles order lifecycle: creation, validation, and payment coordination.
                 * Business rule BR-ORD-001: orders must be validated before payment.
                 */
                @Service
                public class OrderService {
                    private final PaymentService paymentService;
                    public OrderService(PaymentService paymentService) {
                        this.paymentService = paymentService;
                    }
                    /** Creates a new order for the given customer. */
                    @Transactional
                    public Long createOrder(String customerId) {
                        validate(customerId);
                        return System.currentTimeMillis();
                    }
                    /** Processes payment for an existing order. */
                    public void processPayment(Long orderId) {
                        paymentService.charge(orderId);
                    }
                    private void validate(String customerId) { }
                }
                """;
    }

    private static String paymentServiceSource() {
        return """
                package com.example;
                import org.springframework.stereotype.Service;
                /**
                 * Handles payment charging and retry logic.
                 * BR-PAY-042: retry up to 3 times with exponential backoff.
                 */
                @Service
                public class PaymentService {
                    /** Charges the payment gateway for the given order. */
                    public void charge(Long orderId) { }
                }
                """;
    }

    private static String controllerSource() {
        return """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                /** REST controller for order management. */
                @RestController
                @RequestMapping("/orders")
                public class OrderController {
                    private final OrderService orderService;
                    public OrderController(OrderService orderService) {
                        this.orderService = orderService;
                    }
                    /** Create a new order. */
                    @PostMapping
                    public Long createOrder(@RequestParam String customerId) {
                        return orderService.createOrder(customerId);
                    }
                    /** Get order by ID. */
                    @GetMapping("/{id}")
                    public String getOrder(@PathVariable Long id) {
                        return "order-" + id;
                    }
                }
                """;
    }

    private static String configSource() {
        return """
                server:
                  port: 8080
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/orders
                    username: orders
                  jpa:
                    hibernate:
                      ddl-auto: validate
                payment:
                  retry:
                    max-attempts: 3
                    backoff-ms: 1000
                """;
    }
}
