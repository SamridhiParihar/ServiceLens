package com.servicelens.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CodeChunk} and its nested {@link CodeChunk.ChunkType} enum.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Record construction and canonical accessor methods.</li>
 *   <li>{@link CodeChunk#toDocument()} metadata population.</li>
 *   <li>Enriched content preamble produced by {@code buildEnrichedContent()}
 *       (verified indirectly through the {@link Document#getContent()} result).</li>
 *   <li>Behaviour when {@code extraMetadata} is {@code null} or populated.</li>
 *   <li>All seven {@link CodeChunk.ChunkType} variants.</li>
 * </ul>
 *
 * <p>No mocks and no Spring context are needed — {@code CodeChunk} is a pure
 * Java record whose behaviour is entirely deterministic.</p>
 */
@DisplayName("CodeChunk")
class CodeChunkTest {

    // ─────────────────────────────────────────────────────────────────────
    // Record construction
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("All accessors return the values supplied at construction time")
        void accessorsReturnCorrectValues() {
            CodeChunk chunk = new CodeChunk(
                    "public void pay() {}",
                    "/src/PaymentService.java",
                    "pay",
                    10,
                    15,
                    "java",
                    "JAVA",
                    CodeChunk.ChunkType.CODE,
                    "payment-service",
                    null
            );

            assertThat(chunk.content()).isEqualTo("public void pay() {}");
            assertThat(chunk.filePath()).isEqualTo("/src/PaymentService.java");
            assertThat(chunk.elementName()).isEqualTo("pay");
            assertThat(chunk.startLine()).isEqualTo(10);
            assertThat(chunk.endLine()).isEqualTo(15);
            assertThat(chunk.language()).isEqualTo("java");
            assertThat(chunk.fileType()).isEqualTo("JAVA");
            assertThat(chunk.chunkType()).isEqualTo(CodeChunk.ChunkType.CODE);
            assertThat(chunk.serviceName()).isEqualTo("payment-service");
            assertThat(chunk.extraMetadata()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // toDocument() — metadata entries
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toDocument() — metadata")
    class ToDocumentMetadataTests {

        @Test
        @DisplayName("Document metadata contains all mandatory chunk fields")
        void mandatoryMetadataIsPresent() {
            CodeChunk chunk = codeChunk("processPayment", CodeChunk.ChunkType.CODE, null);

            Document doc = chunk.toDocument();
            Map<String, Object> meta = doc.getMetadata();

            assertThat(meta).containsEntry("file_path",   "/src/PaymentService.java")
                            .containsEntry("element_name", "processPayment")
                            .containsEntry("start_line",   5)
                            .containsEntry("end_line",     20)
                            .containsEntry("language",     "java")
                            .containsEntry("file_type",    "JAVA")
                            .containsEntry("chunk_type",   "CODE")
                            .containsEntry("service_name", "payment-service");
        }

        @Test
        @DisplayName("Extra metadata entries are merged into the document metadata")
        void extraMetadataIsMerged() {
            Map<String, String> extra = Map.of(
                    "http_method",  "POST",
                    "endpoint_path", "/api/payments"
            );
            CodeChunk chunk = codeChunk("postPayment", CodeChunk.ChunkType.API_SPEC, extra);

            Document doc = chunk.toDocument();

            assertThat(doc.getMetadata())
                    .containsEntry("http_method",   "POST")
                    .containsEntry("endpoint_path", "/api/payments");
        }

        @Test
        @DisplayName("Null extraMetadata does not throw and produces no extra entries")
        void nullExtraMetadataIsIgnored() {
            CodeChunk chunk = codeChunk("validate", CodeChunk.ChunkType.CODE, null);

            Document doc = chunk.toDocument();

            // Standard entries only — no NPE and no unexpected keys
            assertThat(doc.getMetadata()).doesNotContainKey("http_method");
        }

        @Test
        @DisplayName("chunk_type metadata reflects the ChunkType enum name")
        void chunkTypeNameIsStored() {
            for (CodeChunk.ChunkType type : CodeChunk.ChunkType.values()) {
                CodeChunk chunk = codeChunk("elem", type, null);
                assertThat(chunk.toDocument().getMetadata())
                        .containsEntry("chunk_type", type.name());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // toDocument() — enriched content
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toDocument() — enriched content preamble")
    class EnrichedContentTests {

        @Test
        @DisplayName("Enriched content starts with 'Service:' preamble")
        void contentStartsWithServicePreamble() {
            CodeChunk chunk = codeChunk("myMethod", CodeChunk.ChunkType.CODE, null);

            String content = chunk.toDocument().getContent();

            assertThat(content).startsWith("Service: payment-service");
        }

        @Test
        @DisplayName("Enriched content includes file path")
        void contentIncludesFilePath() {
            CodeChunk chunk = codeChunk("myMethod", CodeChunk.ChunkType.CODE, null);

            assertThat(chunk.toDocument().getContent())
                    .contains("File: /src/PaymentService.java");
        }

        @Test
        @DisplayName("Enriched content includes chunk type")
        void contentIncludesChunkType() {
            CodeChunk chunk = codeChunk("myMethod", CodeChunk.ChunkType.CONFIG, null);

            assertThat(chunk.toDocument().getContent()).contains("Type: CONFIG");
        }

        @Test
        @DisplayName("Enriched content includes element name when non-blank")
        void contentIncludesElementName() {
            CodeChunk chunk = codeChunk("processPayment", CodeChunk.ChunkType.CODE, null);

            assertThat(chunk.toDocument().getContent()).contains("Element: processPayment");
        }

        @Test
        @DisplayName("Enriched content ends with the raw source content")
        void contentEndsWithRawSource() {
            CodeChunk chunk = codeChunk("myMethod", CodeChunk.ChunkType.CODE, null);

            assertThat(chunk.toDocument().getContent())
                    .endsWith("public void myMethod() { return; }");
        }

        @Test
        @DisplayName("Extra metadata key-value pairs appear in the enriched content")
        void extraMetadataAppearsInContent() {
            Map<String, String> extra = Map.of("annotation", "@Transactional");
            CodeChunk chunk = codeChunk("save", CodeChunk.ChunkType.CODE, extra);

            assertThat(chunk.toDocument().getContent())
                    .contains("annotation: @Transactional");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ChunkType enum
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ChunkType enum")
    class ChunkTypeTests {

        @Test
        @DisplayName("All seven ChunkType variants exist")
        void allVariantsPresent() {
            assertThat(CodeChunk.ChunkType.values()).containsExactlyInAnyOrder(
                    CodeChunk.ChunkType.CODE,
                    CodeChunk.ChunkType.TEST,
                    CodeChunk.ChunkType.CONFIG,
                    CodeChunk.ChunkType.SCHEMA,
                    CodeChunk.ChunkType.API_SPEC,
                    CodeChunk.ChunkType.DOCUMENTATION,
                    CodeChunk.ChunkType.BUSINESS_CONTEXT
            );
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Build a minimal {@link CodeChunk} with a fixed service name and source file for
     * use across most test cases.
     *
     * @param elementName   the method or element name stored in the chunk
     * @param type          semantic classification of the chunk
     * @param extraMetadata optional extra key-value metadata; may be {@code null}
     * @return a fully populated {@code CodeChunk} ready for testing
     */
    private static CodeChunk codeChunk(String elementName,
                                       CodeChunk.ChunkType type,
                                       Map<String, String> extraMetadata) {
        return new CodeChunk(
                "public void " + elementName + "() { return; }",
                "/src/PaymentService.java",
                elementName,
                5,
                20,
                "java",
                "JAVA",
                type,
                "payment-service",
                extraMetadata
        );
    }
}
