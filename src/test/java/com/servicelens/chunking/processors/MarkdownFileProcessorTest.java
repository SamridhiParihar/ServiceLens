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
 * Unit tests for {@link MarkdownFileProcessor}.
 *
 * <p>Real Markdown files are written to a {@link TempDir}. Tests verify
 * section splitting on {@code ##} headings, chunk type classification
 * (BUSINESS_CONTEXT vs DOCUMENTATION), and metadata population.</p>
 */
@DisplayName("MarkdownFileProcessor")
class MarkdownFileProcessorTest {

    private MarkdownFileProcessor processor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        processor = new MarkdownFileProcessor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // supports()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("supports()")
    class SupportsTests {

        @Test
        @DisplayName("Returns true for .md files")
        void trueForMd() {
            assertThat(processor.supports(Path.of("README.md"))).isTrue();
        }

        @Test
        @DisplayName("Returns true for .markdown files")
        void trueForMarkdown() {
            assertThat(processor.supports(Path.of("guide.markdown"))).isTrue();
        }

        @Test
        @DisplayName("Returns false for .txt files")
        void falseForTxt() {
            assertThat(processor.supports(Path.of("notes.txt"))).isFalse();
        }

        @Test
        @DisplayName("Returns false for .java files")
        void falseForJava() {
            assertThat(processor.supports(Path.of("Service.java"))).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // process() — section splitting
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("process() — section splitting")
    class SectionSplittingTests {

        @Test
        @DisplayName("Produces one chunk per ## heading")
        void oneChunkPerH2Heading() throws IOException {
            Path f = tempDir.resolve("guide.md");
            Files.writeString(f, """
                    ## Overview
                    This service handles payments.

                    ## Configuration
                    Set the timeout in application.yml.

                    ## Deployment
                    Deploy via Docker.
                    """);

            List<CodeChunk> chunks = processor.process(f, "payment-svc");

            assertThat(chunks).hasSize(3);
            assertThat(chunks).extracting(CodeChunk::elementName)
                    .containsExactly("Overview", "Configuration", "Deployment");
        }

        @Test
        @DisplayName("Content before first ## heading is gathered as Introduction")
        void contentBeforeFirstHeadingIsIntroduction() throws IOException {
            Path f = tempDir.resolve("readme.md");
            Files.writeString(f, """
                    This is the intro paragraph before any headings.

                    ## Details
                    Here are the details.
                    """);

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0).elementName()).isEqualTo("Introduction");
            assertThat(chunks.get(0).content()).contains("intro paragraph");
        }

        @Test
        @DisplayName("Chunk content includes the ## heading prefix")
        void chunkContentIncludesHeading() throws IOException {
            Path f = tempDir.resolve("doc.md");
            Files.writeString(f, """
                    ## Retry Policy
                    Payments retry up to 3 times.
                    """);

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks.get(0).content()).startsWith("## Retry Policy");
        }

        @Test
        @DisplayName("Single-section file produces one chunk")
        void singleSectionProducesOneChunk() throws IOException {
            Path f = tempDir.resolve("single.md");
            Files.writeString(f, """
                    ## Only Section
                    Just one section here.
                    """);

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).hasSize(1);
        }

        @Test
        @DisplayName("Returns one chunk for file with no ## headings")
        void noHeadingsProducesSingleIntroductionChunk() throws IOException {
            Path f = tempDir.resolve("flat.md");
            Files.writeString(f, "No headings, just text.\nSecond line.\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).elementName()).isEqualTo("Introduction");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // process() — chunk type classification
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("process() — chunk type classification")
    class ChunkTypeTests {

        @Test
        @DisplayName("Files with 'context' in name produce BUSINESS_CONTEXT chunks")
        void contextInFileNameGivesBusinessContextType() throws IOException {
            Path f = tempDir.resolve("payment-context.md");
            Files.writeString(f, "## Retry Rule\nRetry 3 times per BR-PAY-042.\n");

            List<CodeChunk> chunks = processor.process(f, "payment-svc");

            assertThat(chunks).extracting(CodeChunk::chunkType)
                    .containsOnly(CodeChunk.ChunkType.BUSINESS_CONTEXT);
        }

        @Test
        @DisplayName("Files with 'business' in name produce BUSINESS_CONTEXT chunks")
        void businessInFileNameGivesBusinessContextType() throws IOException {
            Path f = tempDir.resolve("business-rules.md");
            Files.writeString(f, "## SLA\nResponse in 200ms.\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).extracting(CodeChunk::chunkType)
                    .containsOnly(CodeChunk.ChunkType.BUSINESS_CONTEXT);
        }

        @Test
        @DisplayName("Regular Markdown files produce DOCUMENTATION chunks")
        void regularMarkdownGivesDocumentationType() throws IOException {
            Path f = tempDir.resolve("README.md");
            Files.writeString(f, "## Setup\nRun mvn install.\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).extracting(CodeChunk::chunkType)
                    .containsOnly(CodeChunk.ChunkType.DOCUMENTATION);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // process() — metadata
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("process() — metadata")
    class MetadataTests {

        @Test
        @DisplayName("section_title metadata matches the heading text")
        void sectionTitleMetadataMatchesHeading() throws IOException {
            Path f = tempDir.resolve("doc.md");
            Files.writeString(f, "## Payment Flow\nSome content.\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks.get(0).extraMetadata().get("section_title"))
                    .isEqualTo("Payment Flow");
        }

        @Test
        @DisplayName("Service name is set on every chunk")
        void serviceNameSetOnEveryChunk() throws IOException {
            Path f = tempDir.resolve("guide.md");
            Files.writeString(f, "## A\nContent A.\n## B\nContent B.\n");

            List<CodeChunk> chunks = processor.process(f, "inventory-svc");

            assertThat(chunks).extracting(CodeChunk::serviceName)
                    .containsOnly("inventory-svc");
        }
    }
}
