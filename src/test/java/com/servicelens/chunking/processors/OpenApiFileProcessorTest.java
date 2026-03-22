package com.servicelens.chunking.processors;

import com.servicelens.chunking.CodeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenApiFileProcessor}.
 *
 * <p>A minimal but valid OpenAPI 3.0 YAML is written to a {@link TempDir} so the
 * processor exercises the full parse-and-chunk path through
 * {@code OpenAPIV3Parser}. Tests verify that each HTTP operation becomes one
 * chunk and that all metadata fields are correctly populated.</p>
 */
@DisplayName("OpenApiFileProcessor")
class OpenApiFileProcessorTest {

    private OpenApiFileProcessor processor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        processor = new OpenApiFileProcessor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // supports()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("supports()")
    class SupportsTests {

        @Test
        @DisplayName("Returns true for .yaml file containing openapi: marker")
        void trueForOpenApiYaml() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, minimalOpenApiYaml());
            assertThat(processor.supports(f)).isTrue();
        }

        @Test
        @DisplayName("Returns true for .yml file containing openapi: marker")
        void trueForOpenApiYml() throws IOException {
            Path f = tempDir.resolve("api.yml");
            Files.writeString(f, minimalOpenApiYaml());
            assertThat(processor.supports(f)).isTrue();
        }

        @Test
        @DisplayName("Returns true for .json file containing openapi marker")
        void trueForOpenApiJson() throws IOException {
            Path f = tempDir.resolve("api.json");
            Files.writeString(f, "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"}}");
            assertThat(processor.supports(f)).isTrue();
        }

        @Test
        @DisplayName("Returns false for regular YAML without openapi: marker")
        void falseForRegularYaml() throws IOException {
            Path f = tempDir.resolve("application.yml");
            Files.writeString(f, "server:\n  port: 8080\n");
            assertThat(processor.supports(f)).isFalse();
        }

        @Test
        @DisplayName("Returns false for .java files")
        void falseForJava() {
            assertThat(processor.supports(Path.of("Controller.java"))).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // priority()
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority() returns 10 — higher than YamlFileProcessor")
    void priorityIsTen() {
        assertThat(processor.priority()).isEqualTo(10);
        assertThat(processor.priority())
                .isGreaterThan(new YamlFileProcessor().priority());
    }

    // ─────────────────────────────────────────────────────────────────────
    // process()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("process()")
    class ProcessTests {

        @Test
        @DisplayName("Produces one chunk per HTTP operation")
        void oneChunkPerOperation() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, openApiWithTwoPaths());

            List<CodeChunk> chunks = processor.process(f, "order-svc");

            // POST /orders + GET /orders/{id} = 2 operations
            assertThat(chunks).hasSize(2);
        }

        @Test
        @DisplayName("Every chunk has ChunkType.API_SPEC")
        void allChunksAreApiSpecType() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, openApiWithTwoPaths());

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).extracting(CodeChunk::chunkType)
                    .containsOnly(CodeChunk.ChunkType.API_SPEC);
        }

        @Test
        @DisplayName("Chunk content includes HTTP method and path")
        void chunkContentIncludesMethodAndPath() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, openApiWithTwoPaths());

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).anyMatch(c -> c.content().contains("POST")
                    && c.content().contains("/orders"));
            assertThat(chunks).anyMatch(c -> c.content().contains("GET")
                    && c.content().contains("/orders/{id}"));
        }

        @Test
        @DisplayName("Chunk content includes the operation summary")
        void chunkContentIncludesSummary() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, openApiWithTwoPaths());

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).anyMatch(c -> c.content().contains("Create a new order"));
        }

        @Test
        @DisplayName("http_method metadata is set correctly")
        void httpMethodMetadataSet() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, openApiWithTwoPaths());

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).anyMatch(c ->
                    "POST".equals(c.extraMetadata().get("http_method")));
            assertThat(chunks).anyMatch(c ->
                    "GET".equals(c.extraMetadata().get("http_method")));
        }

        @Test
        @DisplayName("api_path metadata is set correctly")
        void apiPathMetadataSet() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, openApiWithTwoPaths());

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).anyMatch(c ->
                    "/orders".equals(c.extraMetadata().get("api_path")));
            assertThat(chunks).anyMatch(c ->
                    "/orders/{id}".equals(c.extraMetadata().get("api_path")));
        }

        @Test
        @DisplayName("Service name is set on every chunk")
        void serviceNameSetOnEveryChunk() throws IOException {
            Path f = tempDir.resolve("api.yaml");
            Files.writeString(f, openApiWithTwoPaths());

            List<CodeChunk> chunks = processor.process(f, "gateway-svc");

            assertThat(chunks).extracting(CodeChunk::serviceName)
                    .containsOnly("gateway-svc");
        }

        @Test
        @DisplayName("Returns empty list for file that cannot be parsed as OpenAPI")
        void returnsEmptyForUnparsableFile() throws IOException {
            Path f = tempDir.resolve("broken.yaml");
            Files.writeString(f, "openapi: 3.0.0\n: invalid: yaml: content:::\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).isEmpty();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String minimalOpenApiYaml() {
        return """
                openapi: 3.0.0
                info:
                  title: Test API
                  version: "1.0"
                paths: {}
                """;
    }

    private static String openApiWithTwoPaths() {
        return """
                openapi: 3.0.0
                info:
                  title: Order API
                  version: "1.0"
                paths:
                  /orders:
                    post:
                      summary: Create a new order
                      description: Creates an order and returns the order ID
                      operationId: createOrder
                  /orders/{id}:
                    get:
                      summary: Get order by ID
                      operationId: getOrder
                """;
    }
}
