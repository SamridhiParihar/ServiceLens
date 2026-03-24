package com.servicelens.ingestion;

import com.servicelens.chunking.FileProcessor;
import com.servicelens.chunking.processors.JavaFileProcessor;
import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.incremental.FileFingerprinter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IngestionPipeline}.
 *
 * <p>The pipeline is a pure <em>write</em> operation — it walks the repository,
 * processes files, and persists results. It no longer purges existing data;
 * purging is the responsibility of {@link ServiceDeletionService}.
 * These tests verify that data is correctly written and that file hashes are
 * saved after ingestion.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IngestionPipeline — unit")
class IngestionPipelineTest {

    @Mock private VectorStore            vectorStore;
    @Mock private KnowledgeGraphService  graphService;
    @Mock private JavaFileProcessor      javaProcessor;
    @Mock private FileFingerprinter      fingerprinter;

    @TempDir
    Path repoDir;

    private IngestionPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new IngestionPipeline(
                List.of(javaProcessor),
                vectorStore,
                graphService,
                javaProcessor,
                fingerprinter);
    }

    // ── No purge in pipeline ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Pipeline does not purge — purge is ServiceDeletionService's job")
    class NoPurgeBehaviour {

        @Test
        @DisplayName("Does NOT call deleteServiceGraph — pipeline is additive only")
        void doesNotDeleteGraph() throws IOException {
            Files.writeString(repoDir.resolve("Dummy.txt"), "hello");
            stubJavaProcessorForEmptyRepo();

            pipeline.ingest(repoDir, "my-svc");

            verify(graphService, never()).deleteServiceGraph(anyString());
        }
    }

    // ── Hash persistence ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Hash persistence after full ingest")
    class HashPersistence {

        @Test
        @DisplayName("Saves file hashes after ingestion so incremental runs have a baseline")
        void savesHashesAfterIngestion() throws IOException {
            stubJavaProcessorForEmptyRepo();

            pipeline.ingest(repoDir, "my-svc");

            verify(fingerprinter).saveHashes(eq("my-svc"), any());
        }

        @Test
        @DisplayName("saveHashes is called even when the repo has no supported files")
        void savesHashesForEmptyRepo() throws IOException {
            stubJavaProcessorForEmptyRepo();

            pipeline.ingest(repoDir, "empty-svc");

            verify(fingerprinter).saveHashes(eq("empty-svc"), any());
        }

        @Test
        @DisplayName("saveHashes is called after graph and vector data are written")
        void hashSavedAfterData() throws IOException {
            stubJavaProcessorForEmptyRepo();

            var order = inOrder(graphService, fingerprinter);

            pipeline.ingest(repoDir, "order-svc");

            order.verify(graphService).saveFileResult(any());
            order.verify(fingerprinter).saveHashes(eq("order-svc"), any());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubJavaProcessorForEmptyRepo() {
        lenient().when(javaProcessor.supports(any(Path.class))).thenReturn(false);
        lenient().when(javaProcessor.processFile(any(Path.class), anyString()))
                 .thenReturn(new JavaFileProcessor.JavaFileResult(
                         List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        lenient().doNothing().when(graphService).saveFileResult(any());
    }
}
