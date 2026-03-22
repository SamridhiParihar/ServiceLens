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
 * Unit tests for {@link YamlFileProcessor}.
 *
 * <p>Real YAML files are written to a {@link TempDir} so the processor exercises
 * its file-reading and parsing paths without touching the project tree.</p>
 */
@DisplayName("YamlFileProcessor")
class YamlFileProcessorTest {

    private YamlFileProcessor processor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        processor = new YamlFileProcessor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // supports()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("supports()")
    class SupportsTests {

        @Test
        @DisplayName("Returns true for .yml files")
        void trueForYml() throws IOException {
            Path f = tempDir.resolve("application.yml");
            Files.writeString(f, "server:\n  port: 8080\n");
            assertThat(processor.supports(f)).isTrue();
        }

        @Test
        @DisplayName("Returns true for .yaml files")
        void trueForYaml() throws IOException {
            Path f = tempDir.resolve("config.yaml");
            Files.writeString(f, "app:\n  name: test\n");
            assertThat(processor.supports(f)).isTrue();
        }

        @Test
        @DisplayName("Returns false for .java files")
        void falseForJava() {
            assertThat(processor.supports(Path.of("Service.java"))).isFalse();
        }

        @Test
        @DisplayName("Returns false for YAML files that contain openapi: marker")
        void falseForOpenApiYaml() throws IOException {
            Path f = tempDir.resolve("openapi.yml");
            Files.writeString(f, "openapi: 3.0.0\ninfo:\n  title: Test\n");
            assertThat(processor.supports(f)).isFalse();
        }

        @Test
        @DisplayName("Returns false for YAML files that contain swagger: marker")
        void falseForSwaggerYaml() throws IOException {
            Path f = tempDir.resolve("swagger.yml");
            Files.writeString(f, "swagger: '2.0'\ninfo:\n  title: Test\n");
            assertThat(processor.supports(f)).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // priority()
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority() returns 5 — lower than OpenApiFileProcessor")
    void priorityIsFive() {
        assertThat(processor.priority()).isEqualTo(5);
    }

    // ─────────────────────────────────────────────────────────────────────
    // process()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("process()")
    class ProcessTests {

        @Test
        @DisplayName("Produces one chunk per top-level YAML key")
        void oneChunkPerTopLevelKey() throws IOException {
            Path f = tempDir.resolve("application.yml");
            Files.writeString(f, """
                    server:
                      port: 8080
                    spring:
                      datasource:
                        url: jdbc:postgresql://localhost/db
                    management:
                      endpoints:
                        web:
                          exposure:
                            include: health
                    """);

            List<CodeChunk> chunks = processor.process(f, "my-service");

            assertThat(chunks).hasSize(3);
            assertThat(chunks).extracting(CodeChunk::elementName)
                    .containsExactlyInAnyOrder("server", "spring", "management");
        }

        @Test
        @DisplayName("Every chunk has ChunkType.CONFIG")
        void allChunksAreConfigType() throws IOException {
            Path f = tempDir.resolve("application.yml");
            Files.writeString(f, "server:\n  port: 8080\napp:\n  name: test\n");

            List<CodeChunk> chunks = processor.process(f, "my-service");

            assertThat(chunks).extracting(CodeChunk::chunkType)
                    .containsOnly(CodeChunk.ChunkType.CONFIG);
        }

        @Test
        @DisplayName("Each chunk carries config_key metadata matching element name")
        void configKeyMetadataPresent() throws IOException {
            Path f = tempDir.resolve("application.yml");
            Files.writeString(f, "server:\n  port: 8080\n");

            List<CodeChunk> chunks = processor.process(f, "my-service");

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).extraMetadata()).containsKey("config_key");
            assertThat(chunks.get(0).extraMetadata().get("config_key")).isEqualTo("server");
        }

        @Test
        @DisplayName("Each chunk carries config_file metadata with the file name")
        void configFileMetadataPresent() throws IOException {
            Path f = tempDir.resolve("app-config.yml");
            Files.writeString(f, "feature:\n  enabled: true\n");

            List<CodeChunk> chunks = processor.process(f, "my-service");

            assertThat(chunks.get(0).extraMetadata().get("config_file"))
                    .isEqualTo("app-config.yml");
        }

        @Test
        @DisplayName("Chunk content includes the top-level key")
        void chunkContentIncludesKey() throws IOException {
            Path f = tempDir.resolve("application.yml");
            Files.writeString(f, "server:\n  port: 8080\n");

            List<CodeChunk> chunks = processor.process(f, "my-service");

            assertThat(chunks.get(0).content()).startsWith("server:");
        }

        @Test
        @DisplayName("Service name is set on every chunk")
        void serviceNameSetOnEveryChunk() throws IOException {
            Path f = tempDir.resolve("application.yml");
            Files.writeString(f, "app:\n  name: test\n");

            List<CodeChunk> chunks = processor.process(f, "order-service");

            assertThat(chunks).extracting(CodeChunk::serviceName)
                    .containsOnly("order-service");
        }

        @Test
        @DisplayName("Returns empty list for a blank YAML file")
        void returnsEmptyForBlankFile() throws IOException {
            Path f = tempDir.resolve("empty.yml");
            Files.writeString(f, "");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).isEmpty();
        }
    }
}
