package com.servicelens.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicelens.incremental.IncrementalIngestionService;
import com.servicelens.incremental.IncrementalIngestionService.IncrementalResult;
import com.servicelens.retrieval.CodeRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link IngestionController}.
 *
 * <p>Uses {@code @WebMvcTest} — loads only the web layer (Jackson, MockMvc).
 * {@link IngestionPipeline}, {@link IncrementalIngestionService}, and
 * {@link CodeRetriever} are replaced by Mockito beans so no live database,
 * Ollama, or filesystem access is required.</p>
 *
 * <p>Test categories:</p>
 * <ul>
 *   <li><strong>POST /api/ingest</strong> — verifies delegation to
 *       {@link IngestionPipeline} and correct JSON serialisation of
 *       {@link IngestionPipeline.IngestionResult}.</li>
 *   <li><strong>POST /api/ingest/incremental</strong> — verifies delegation to
 *       {@link IncrementalIngestionService}, correct JSON fields (including the
 *       derived {@code totalChanged} field), and that the full pipeline is never
 *       called on the incremental path.</li>
 * </ul>
 */
@WebMvcTest(IngestionController.class)
@DisplayName("IngestionController — unit")
class IngestionControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IngestionPipeline pipeline;

    @MockitoBean
    private IncrementalIngestionService incrementalService;

    @MockitoBean
    private CodeRetriever retriever;

    // ── POST /api/ingest ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/ingest")
    class FullIngestTests {

        @Test
        @DisplayName("Returns 200 with serviceName and totalCodeChunks from IngestionResult")
        void returns200WithIngestionResult() throws Exception {
            when(pipeline.ingest(any(Path.class), eq("order-svc")))
                    .thenReturn(ingestionResult("order-svc", 5, 2, 1, 3, 10));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.serviceName").value("order-svc"))
               .andExpect(jsonPath("$.totalCodeChunks").value(5))
               .andExpect(jsonPath("$.totalDocChunks").value(2))
               .andExpect(jsonPath("$.totalClasses").value(1))
               .andExpect(jsonPath("$.totalMethods").value(3));
        }

        @Test
        @DisplayName("Delegates to IngestionPipeline with the exact serviceName from the request body")
        void delegatesToPipelineWithCorrectServiceName() throws Exception {
            when(pipeline.ingest(any(Path.class), eq("my-svc")))
                    .thenReturn(ingestionResult("my-svc", 0, 0, 0, 0, 0));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "my-svc")))
               .andExpect(status().isOk());

            verify(pipeline).ingest(any(Path.class), eq("my-svc"));
        }

        @Test
        @DisplayName("Does not call IncrementalIngestionService when full ingest endpoint is used")
        void doesNotCallIncrementalServiceForFullIngest() throws Exception {
            when(pipeline.ingest(any(Path.class), anyString()))
                    .thenReturn(ingestionResult("svc", 0, 0, 0, 0, 0));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc")))
               .andExpect(status().isOk());

            verifyNoInteractions(incrementalService);
        }
    }

    // ── POST /api/ingest/incremental ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/ingest/incremental")
    class IncrementalIngestTests {

        @Test
        @DisplayName("Returns 200 with all IncrementalResult fields: serviceName, added, modified, deleted, unchanged")
        void returns200WithAllIncrementalResultFields() throws Exception {
            when(incrementalService.ingest(any(Path.class), eq("order-svc")))
                    .thenReturn(new IncrementalResult("order-svc", 3, 1, 0, 5));

            mvc.perform(post("/api/ingest/incremental")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "order-svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.serviceName").value("order-svc"))
               .andExpect(jsonPath("$.added").value(3))
               .andExpect(jsonPath("$.modified").value(1))
               .andExpect(jsonPath("$.deleted").value(0))
               .andExpect(jsonPath("$.unchanged").value(5));
        }

        @Test
        @DisplayName("Returns derived totalChanged field equal to added + modified + deleted")
        void returnsTotalChangedDerivedField() throws Exception {
            when(incrementalService.ingest(any(Path.class), anyString()))
                    .thenReturn(new IncrementalResult("svc", 2, 1, 1, 3));

            mvc.perform(post("/api/ingest/incremental")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalChanged").value(4)); // 2 + 1 + 1
        }

        @Test
        @DisplayName("Delegates to IncrementalIngestionService with the exact serviceName from the request body")
        void delegatesToIncrementalServiceWithCorrectServiceName() throws Exception {
            when(incrementalService.ingest(any(Path.class), eq("my-svc")))
                    .thenReturn(new IncrementalResult("my-svc", 0, 0, 0, 2));

            mvc.perform(post("/api/ingest/incremental")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "my-svc")))
               .andExpect(status().isOk());

            verify(incrementalService).ingest(any(Path.class), eq("my-svc"));
        }

        @Test
        @DisplayName("Does not call IngestionPipeline when incremental endpoint is used")
        void doesNotCallPipelineForIncrementalRequest() throws Exception {
            when(incrementalService.ingest(any(Path.class), anyString()))
                    .thenReturn(new IncrementalResult("svc", 0, 0, 0, 0));

            mvc.perform(post("/api/ingest/incremental")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc")))
               .andExpect(status().isOk());

            verifyNoInteractions(pipeline);
        }

        @Test
        @DisplayName("Returns zero totalChanged when all files are unchanged")
        void zeroTotalChangedWhenAllUnchanged() throws Exception {
            when(incrementalService.ingest(any(Path.class), anyString()))
                    .thenReturn(new IncrementalResult("svc", 0, 0, 0, 10));

            mvc.perform(post("/api/ingest/incremental")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalChanged").value(0))
               .andExpect(jsonPath("$.unchanged").value(10));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String body(String repoPath, String serviceName) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("repoPath", repoPath, "serviceName", serviceName));
    }

    /**
     * Build a minimal {@link IngestionPipeline.IngestionResult} for stub returns.
     */
    private static IngestionPipeline.IngestionResult ingestionResult(
            String serviceName,
            int codeChunks, int docChunks,
            int classes, int methods, int cfgNodes) {
        return new IngestionPipeline.IngestionResult(
                serviceName, codeChunks, docChunks, classes, methods, cfgNodes, Map.of());
    }
}
