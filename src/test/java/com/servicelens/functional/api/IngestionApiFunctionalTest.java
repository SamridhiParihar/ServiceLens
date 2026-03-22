package com.servicelens.functional.api;

import com.servicelens.functional.support.FunctionalTestBase;
import com.servicelens.ingestion.IngestionPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for the ServiceLens REST API ({@link com.servicelens.ingestion.IngestionController}).
 *
 * <p>Uses Spring Boot's {@link TestRestTemplate} against the randomly-assigned
 * server port.  Tests exercise the full HTTP → service → pgvector / Neo4j
 * round-trip including JSON serialisation.</p>
 */
@DisplayName("IngestionController API — functional")
class IngestionApiFunctionalTest extends FunctionalTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @TempDir
    Path repoDir;

    // ── POST /api/ingest ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/ingest returns 200 with IngestionResult for a valid repo path")
    @SuppressWarnings("unchecked")
    void postIngest_returns200WithResult() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource("OrderService"));

        Map<String, String> body = Map.of(
                "repoPath",    repoDir.toAbsolutePath().toString(),
                "serviceName", "api-test-svc"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/ingest", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("serviceName");
        assertThat(response.getBody().get("serviceName")).isEqualTo("api-test-svc");
        assertThat(response.getBody()).containsKey("totalCodeChunks");
    }

    @Test
    @DisplayName("POST /api/ingest with multiple files reports correct chunk count breakdown")
    @SuppressWarnings("unchecked")
    void postIngest_multipleFiles_chunkBreakdownInResponse() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"),  javaSource("OrderService"));
        Files.writeString(repoDir.resolve("application.yml"),    yamlSource());
        Files.writeString(repoDir.resolve("schema.sql"),         sqlSource());

        Map<String, String> body = Map.of(
                "repoPath",    repoDir.toAbsolutePath().toString(),
                "serviceName", "api-multi-svc"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/ingest", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> result = response.getBody();
        assertThat(result).isNotNull();

        // chunksByType is a nested map
        Object chunksByType = result.get("chunksByType");
        assertThat(chunksByType).isNotNull();
        assertThat(chunksByType.toString()).contains("CODE", "CONFIG", "SCHEMA");
    }

    // ── GET /api/retrieve ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/retrieve returns 200 with a list result (may be empty for cold store)")
    @SuppressWarnings("unchecked")
    void getRetrieve_returns200WithList() {
        ResponseEntity<List> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/retrieve?query=order&serviceName=empty-svc&topK=5",
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    // ── Ingest then retrieve (end-to-end) ─────────────────────────────────────

    @Test
    @DisplayName("Ingest then retrieve returns the ingested content via semantic search")
    @SuppressWarnings("unchecked")
    void ingestThenRetrieve_endToEnd() throws IOException {
        String service = "e2e-svc";
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource("OrderService"));

        // Ingest
        restTemplate.postForEntity(
                "http://localhost:" + port + "/api/ingest",
                Map.of("repoPath", repoDir.toAbsolutePath().toString(), "serviceName", service),
                Map.class);

        // Retrieve — query with exact class name to guarantee similarity > 0.5
        ResponseEntity<List> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/retrieve"
                        + "?query=OrderService+createOrder+processPayment"
                        + "&serviceName=" + service
                        + "&topK=5",
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // At least the ingested Java chunks should be returned
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    @DisplayName("Retrieve returns an empty list when nothing has been ingested for that service")
    @SuppressWarnings("unchecked")
    void retrieve_emptyForServiceWithNoData() {
        ResponseEntity<List> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/retrieve"
                        + "?query=anything&serviceName=never-ingested&topK=5",
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // ── POST /api/ingest/incremental ──────────────────────────────────────────

    @Test
    @DisplayName("POST /api/ingest/incremental returns 200 with IncrementalResult on first run")
    @SuppressWarnings("unchecked")
    void postIngestIncremental_firstRun_returns200WithResult() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource("OrderService"));
        Files.writeString(repoDir.resolve("application.yml"),   yamlSource());

        Map<String, String> body = Map.of(
                "repoPath",    repoDir.toAbsolutePath().toString(),
                "serviceName", "incr-api-svc"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/ingest/incremental", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("serviceName");
        assertThat(response.getBody().get("serviceName")).isEqualTo("incr-api-svc");
        assertThat(response.getBody()).containsKeys("added", "modified", "deleted", "unchanged", "totalChanged");
        // First run — both files must be added
        assertThat((Integer) response.getBody().get("added")).isEqualTo(2);
        assertThat((Integer) response.getBody().get("modified")).isZero();
        assertThat((Integer) response.getBody().get("deleted")).isZero();
    }

    @Test
    @DisplayName("POST /api/ingest/incremental second run with no changes reports all files unchanged")
    @SuppressWarnings("unchecked")
    void postIngestIncremental_secondRunNoChanges_allUnchanged() throws IOException {
        String service = "incr-idempotent-svc";
        Files.writeString(repoDir.resolve("PaymentService.java"), javaSource("PaymentService"));

        Map<String, String> body = Map.of(
                "repoPath",    repoDir.toAbsolutePath().toString(),
                "serviceName", service
        );
        String url = "http://localhost:" + port + "/api/ingest/incremental";

        restTemplate.postForEntity(url, body, Map.class);  // first run

        ResponseEntity<Map> second = restTemplate.postForEntity(url, body, Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) second.getBody().get("added")).isZero();
        assertThat((Integer) second.getBody().get("modified")).isZero();
        assertThat((Integer) second.getBody().get("deleted")).isZero();
        assertThat((Integer) second.getBody().get("unchanged")).isEqualTo(1);
        assertThat((Integer) second.getBody().get("totalChanged")).isZero();
    }

    @Test
    @DisplayName("POST /api/ingest/incremental detects a modified file on second run")
    @SuppressWarnings("unchecked")
    void postIngestIncremental_modifiedFile_detectedOnSecondRun() throws IOException {
        String service = "incr-modified-svc";
        Path javaFile  = repoDir.resolve("InventoryService.java");
        Files.writeString(javaFile, javaSource("InventoryService"));

        Map<String, String> body = Map.of(
                "repoPath",    repoDir.toAbsolutePath().toString(),
                "serviceName", service
        );
        String url = "http://localhost:" + port + "/api/ingest/incremental";

        restTemplate.postForEntity(url, body, Map.class);  // first run

        // Modify the file so its hash changes
        Files.writeString(javaFile, javaSource("InventoryService") + "\n// updated\n");

        ResponseEntity<Map> second = restTemplate.postForEntity(url, body, Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) second.getBody().get("modified")).isEqualTo(1);
        assertThat((Integer) second.getBody().get("added")).isZero();
        assertThat((Integer) second.getBody().get("unchanged")).isZero();
    }

    @Test
    @DisplayName("POST /api/ingest/incremental does not create duplicate vectors on re-run with no changes")
    @SuppressWarnings("unchecked")
    void postIngestIncremental_noChanges_noDuplicateVectors() throws IOException {
        String service = "incr-nodup-svc";
        Files.writeString(repoDir.resolve("CheckoutService.java"), javaSource("CheckoutService"));

        Map<String, String> body = Map.of(
                "repoPath",    repoDir.toAbsolutePath().toString(),
                "serviceName", service
        );
        String url = "http://localhost:" + port + "/api/ingest/incremental";

        restTemplate.postForEntity(url, body, Map.class);  // first run
        int countAfterFirst = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);

        restTemplate.postForEntity(url, body, Map.class);  // second run — nothing changed
        int countAfterSecond = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);

        // No new vectors should have been added
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String javaSource(String className) {
        return """
                package com.example;
                import org.springframework.stereotype.Service;
                /** Handles %s logic including createOrder and processPayment. */
                @Service
                public class %s {
                    /** Creates a new order for the given customer identifier. */
                    public Long createOrder(String customerId) {
                        // Validate customer and create a new order entry
                        long orderId = System.currentTimeMillis();
                        return orderId;
                    }
                    /** Processes payment for the given order identifier. */
                    public void processPayment(Long orderId) {
                        // Validate orderId and trigger payment processing
                        if (orderId == null) { return; }
                        System.out.println("Processing payment for order " + orderId);
                    }
                }
                """.formatted(className, className);
    }

    private static String yamlSource() {
        return """
                server:
                  port: 8080
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/orders
                """;
    }

    private static String sqlSource() {
        return """
                CREATE TABLE orders (id BIGSERIAL PRIMARY KEY, status VARCHAR(50));
                CREATE TABLE payments (id BIGSERIAL PRIMARY KEY, order_id BIGINT);
                """;
    }
}
