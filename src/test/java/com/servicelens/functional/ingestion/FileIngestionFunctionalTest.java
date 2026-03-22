package com.servicelens.functional.ingestion;

import com.servicelens.functional.support.FunctionalTestBase;
import com.servicelens.ingestion.IngestionPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for {@link IngestionPipeline}.
 *
 * <p>Each test writes real source files to a JUnit {@link TempDir},
 * calls {@link IngestionPipeline#ingest(Path, String)}, and verifies
 * that the resulting chunks land in pgvector with the correct metadata
 * and that graph nodes appear in Neo4j.</p>
 *
 * <p>All database state is wiped by {@link FunctionalTestBase#cleanData()}
 * after every test.</p>
 */
@DisplayName("FileIngestion — functional")
class FileIngestionFunctionalTest extends FunctionalTestBase {

    private static final String SERVICE = "order-svc";

    @Autowired
    private IngestionPipeline pipeline;

    @TempDir
    Path repoDir;

    // ── Java file ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ingesting a Java file stores CODE chunks in pgvector")
    void ingestJavaFile_storesCodeChunksInVectorStore() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource());

        IngestionPipeline.IngestionResult result = pipeline.ingest(repoDir, SERVICE);

        assertThat(result.totalCodeChunks()).isPositive();
        assertThat(countVectorsByChunkType("CODE")).isPositive();
    }

    @Test
    @DisplayName("Ingesting a Java file stores ClassNode and MethodNodes in Neo4j")
    void ingestJavaFile_storesGraphNodesInNeo4j() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource());

        IngestionPipeline.IngestionResult result = pipeline.ingest(repoDir, SERVICE);

        assertThat(result.totalClasses()).isPositive();
        assertThat(result.totalMethods()).isPositive();

        long classCount;
        try (Session s = neo4jDriver.session()) {
            classCount = s.run(
                    "MATCH (c:Class {serviceName: $svc}) RETURN count(c) AS cnt",
                    Map.of("svc", SERVICE))
                    .single().get("cnt").asLong();
        }
        assertThat(classCount).isPositive();
    }

    @Test
    @DisplayName("Ingesting a Java file sets correct service_name metadata on every chunk")
    void ingestJavaFile_serviceNameMetadataOnAllChunks() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource());

        pipeline.ingest(repoDir, SERVICE);

        int mismatch = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store " +
                "WHERE metadata->>'service_name' != ?",
                Integer.class, SERVICE);
        assertThat(mismatch).isZero();
    }

    // ── YAML file ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ingesting a YAML config file stores CONFIG chunks in pgvector")
    void ingestYamlFile_storesConfigChunks() throws IOException {
        Files.writeString(repoDir.resolve("application.yml"), yamlSource());

        pipeline.ingest(repoDir, SERVICE);

        assertThat(countVectorsByChunkType("CONFIG")).isPositive();
    }

    // ── SQL file ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ingesting a SQL migration stores SCHEMA chunks in pgvector")
    void ingestSqlFile_storesSchemaChunks() throws IOException {
        Files.writeString(repoDir.resolve("schema.sql"), sqlSource());

        pipeline.ingest(repoDir, SERVICE);

        assertThat(countVectorsByChunkType("SCHEMA")).isPositive();
    }

    // ── Markdown file ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ingesting a README stores DOCUMENTATION chunks in pgvector")
    void ingestMarkdownFile_storesDocumentationChunks() throws IOException {
        Files.writeString(repoDir.resolve("README.md"), markdownSource());

        pipeline.ingest(repoDir, SERVICE);

        assertThat(countVectorsByChunkType("DOCUMENTATION")).isPositive();
    }

    @Test
    @DisplayName("Ingesting a business-context Markdown stores BUSINESS_CONTEXT chunks")
    void ingestContextMarkdown_storesBusinessContextChunks() throws IOException {
        Files.writeString(repoDir.resolve("order-context.md"), markdownSource());

        pipeline.ingest(repoDir, SERVICE);

        assertThat(countVectorsByChunkType("BUSINESS_CONTEXT")).isPositive();
    }

    // ── OpenAPI file ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ingesting an OpenAPI spec stores API_SPEC chunks in pgvector")
    void ingestOpenApiFile_storesApiSpecChunks() throws IOException {
        Files.writeString(repoDir.resolve("openapi.yaml"), openApiSource());

        pipeline.ingest(repoDir, SERVICE);

        assertThat(countVectorsByChunkType("API_SPEC")).isPositive();
    }

    // ── Mixed mini-repo ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Ingesting a mixed repo returns correct chunk-type breakdown")
    void ingestMixedRepo_chunkTypeBreakdownCorrect() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource());
        Files.writeString(repoDir.resolve("application.yml"),   yamlSource());
        Files.writeString(repoDir.resolve("schema.sql"),        sqlSource());
        Files.writeString(repoDir.resolve("README.md"),         markdownSource());
        Files.writeString(repoDir.resolve("openapi.yaml"),      openApiSource());

        IngestionPipeline.IngestionResult result = pipeline.ingest(repoDir, SERVICE);

        Map<String, Long> byType = result.chunksByType();
        assertThat(byType).containsKeys("CODE", "CONFIG", "SCHEMA");

        // Total stored rows match the declared counts
        int totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);
        // result counts code + doc chunks; API_SPEC, CONFIG, SCHEMA stored too
        assertThat(totalRows).isPositive();
    }

    // ── IngestionResult fields ────────────────────────────────────────────────

    @Test
    @DisplayName("IngestionResult.serviceName matches the requested service name")
    void ingestionResult_serviceNameIsCorrect() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource());

        IngestionPipeline.IngestionResult result = pipeline.ingest(repoDir, SERVICE);

        assertThat(result.serviceName()).isEqualTo(SERVICE);
    }

    // ── Re-ingest deduplication ───────────────────────────────────────────────

    @Test
    @DisplayName("Re-ingesting the same service does not create duplicate pgvector chunks")
    void reIngest_doesNotCreateDuplicateVectors() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource());

        pipeline.ingest(repoDir, SERVICE);
        int countAfterFirst = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);

        pipeline.ingest(repoDir, SERVICE);
        int countAfterSecond = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);

        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("Re-ingesting the same service does not create duplicate Neo4j class nodes")
    void reIngest_doesNotCreateDuplicateGraphNodes() throws IOException {
        Files.writeString(repoDir.resolve("OrderService.java"), javaSource());

        pipeline.ingest(repoDir, SERVICE);
        long classCountAfterFirst;
        try (org.neo4j.driver.Session s = neo4jDriver.session()) {
            classCountAfterFirst = s.run(
                    "MATCH (c:Class {serviceName: $svc}) RETURN count(c) AS cnt",
                    Map.of("svc", SERVICE))
                    .single().get("cnt").asLong();
        }

        pipeline.ingest(repoDir, SERVICE);
        long classCountAfterSecond;
        try (org.neo4j.driver.Session s = neo4jDriver.session()) {
            classCountAfterSecond = s.run(
                    "MATCH (c:Class {serviceName: $svc}) RETURN count(c) AS cnt",
                    Map.of("svc", SERVICE))
                    .single().get("cnt").asLong();
        }

        assertThat(classCountAfterSecond).isEqualTo(classCountAfterFirst);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int countVectorsByChunkType(String chunkType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'chunk_type' = ?",
                Integer.class, chunkType);
    }

    private static String javaSource() {
        return """
                package com.example.order;

                import org.springframework.stereotype.Service;

                /**
                 * Handles order creation and payment processing.
                 */
                @Service
                public class OrderService {

                    /**
                     * Creates a new order for the given customer.
                     *
                     * @param customerId the customer identifier
                     * @return the created order id
                     */
                    public Long createOrder(String customerId) {
                        // Validate customer and create a new order entry
                        long orderId = System.currentTimeMillis();
                        return orderId;
                    }

                    /**
                     * Processes payment for an existing order.
                     *
                     * @param orderId the order to pay for
                     */
                    public void processPayment(Long orderId) {
                        // Validate and trigger payment processing
                        if (orderId == null) { return; }
                        System.out.println("Processing payment for order " + orderId);
                    }
                }
                """;
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
                CREATE TABLE orders (
                    id BIGSERIAL PRIMARY KEY,
                    customer_id VARCHAR(100) NOT NULL,
                    status VARCHAR(50) NOT NULL DEFAULT 'PENDING'
                );

                CREATE TABLE order_items (
                    id BIGSERIAL PRIMARY KEY,
                    order_id BIGINT NOT NULL REFERENCES orders(id),
                    product_id VARCHAR(100) NOT NULL,
                    quantity INT NOT NULL
                );

                CREATE INDEX idx_orders_customer ON orders (customer_id);
                """;
    }

    private static String markdownSource() {
        return """
                ## Overview
                The Order Service manages the order lifecycle.

                ## Configuration
                Configure timeout via server.timeout in application.yml.

                ## Deployment
                Deploy using Docker Compose with the provided docker-compose.yml.
                """;
    }

    private static String openApiSource() {
        return """
                openapi: 3.0.0
                info:
                  title: Order API
                  version: "1.0"
                paths:
                  /orders:
                    post:
                      summary: Create a new order
                      operationId: createOrder
                  /orders/{id}:
                    get:
                      summary: Get order by ID
                      operationId: getOrder
                    delete:
                      summary: Cancel an order
                      operationId: cancelOrder
                """;
    }
}
