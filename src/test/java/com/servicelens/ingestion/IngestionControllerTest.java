package com.servicelens.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicelens.incremental.IncrementalIngestionService;
import com.servicelens.incremental.IncrementalIngestionService.IncrementalResult;
import com.servicelens.registry.ServiceRecord;
import com.servicelens.registry.ServiceRegistryService;
import com.servicelens.registry.ServiceStatus;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link IngestionController}.
 *
 * <p>Uses {@code @WebMvcTest} — loads only the web layer. All collaborators
 * are replaced by Mockito beans so no live database, Ollama, or filesystem
 * access is required.</p>
 *
 * <p>Test categories:</p>
 * <ul>
 *   <li><strong>POST /api/ingest (FRESH)</strong> — strategy resolver returns FRESH,
 *       pipeline is called, registry is updated.</li>
 *   <li><strong>POST /api/ingest (INCREMENTAL)</strong> — resolver returns INCREMENTAL,
 *       incremental service is called.</li>
 *   <li><strong>POST /api/ingest (FORCE_FULL)</strong> — resolver returns FORCE_FULL,
 *       deletion then pipeline are called.</li>
 *   <li><strong>DELETE /api/services/{name}</strong> — returns 200 when registered,
 *       404 when not found.</li>
 *   <li><strong>GET /api/services</strong> — lists all registered services.</li>
 * </ul>
 */
@WebMvcTest(IngestionController.class)
@DisplayName("IngestionController — unit")
class IngestionControllerTest {

    @Autowired private MockMvc       mvc;
    @Autowired private ObjectMapper  objectMapper;

    @MockitoBean private IngestionPipeline           pipeline;
    @MockitoBean private IncrementalIngestionService incrementalService;
    @MockitoBean private IngestionStrategyResolver   strategyResolver;
    @MockitoBean private ServiceDeletionService      deletionService;
    @MockitoBean private ServiceRegistryService      registryService;
    @MockitoBean private CodeRetriever               retriever;

    // ── POST /api/ingest — FRESH ──────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/ingest — FRESH strategy")
    class FreshIngestTests {

        @Test
        @DisplayName("Returns 200 with IngestionResult fields when strategy is FRESH")
        void returns200WithIngestionResult() throws Exception {
            when(strategyResolver.resolve(eq("order-svc"), anyBoolean()))
                    .thenReturn(IngestionStrategy.FRESH);
            when(pipeline.ingest(any(Path.class), eq("order-svc")))
                    .thenReturn(ingestionResult("order-svc", 5, 2, 1, 3, 10));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "order-svc", false)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.serviceName").value("order-svc"))
               .andExpect(jsonPath("$.totalCodeChunks").value(5))
               .andExpect(jsonPath("$.totalDocChunks").value(2))
               .andExpect(jsonPath("$.totalClasses").value(1))
               .andExpect(jsonPath("$.totalMethods").value(3));
        }

        @Test
        @DisplayName("Registers service in registry after fresh ingestion")
        void registersServiceAfterFreshIngest() throws Exception {
            when(strategyResolver.resolve(anyString(), anyBoolean()))
                    .thenReturn(IngestionStrategy.FRESH);
            when(pipeline.ingest(any(Path.class), anyString()))
                    .thenReturn(ingestionResult("svc", 4, 2, 0, 0, 0));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc", false)))
               .andExpect(status().isOk());

            verify(registryService).register(eq("svc"), eq("/tmp/repo"), anyInt());
        }

        @Test
        @DisplayName("Does not call incremental service or deletion service for FRESH")
        void noIncrementalOrDeletionForFresh() throws Exception {
            when(strategyResolver.resolve(anyString(), anyBoolean()))
                    .thenReturn(IngestionStrategy.FRESH);
            when(pipeline.ingest(any(Path.class), anyString()))
                    .thenReturn(ingestionResult("svc", 0, 0, 0, 0, 0));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc", false)))
               .andExpect(status().isOk());

            verifyNoInteractions(incrementalService);
            verifyNoInteractions(deletionService);
        }
    }

    // ── POST /api/ingest — INCREMENTAL ────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/ingest — INCREMENTAL strategy")
    class IncrementalIngestTests {

        @Test
        @DisplayName("Returns 200 with IncrementalResult fields when strategy is INCREMENTAL")
        void returns200WithIncrementalResult() throws Exception {
            when(strategyResolver.resolve(eq("order-svc"), anyBoolean()))
                    .thenReturn(IngestionStrategy.INCREMENTAL);
            when(incrementalService.ingest(any(Path.class), eq("order-svc")))
                    .thenReturn(new IncrementalResult("order-svc", 3, 1, 0, 5));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "order-svc", false)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.serviceName").value("order-svc"))
               .andExpect(jsonPath("$.added").value(3))
               .andExpect(jsonPath("$.modified").value(1))
               .andExpect(jsonPath("$.deleted").value(0))
               .andExpect(jsonPath("$.unchanged").value(5));
        }

        @Test
        @DisplayName("Does not call full pipeline for INCREMENTAL")
        void doesNotCallPipelineForIncremental() throws Exception {
            when(strategyResolver.resolve(anyString(), anyBoolean()))
                    .thenReturn(IngestionStrategy.INCREMENTAL);
            when(incrementalService.ingest(any(Path.class), anyString()))
                    .thenReturn(new IncrementalResult("svc", 0, 0, 0, 2));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc", false)))
               .andExpect(status().isOk());

            verifyNoInteractions(pipeline);
            verifyNoInteractions(deletionService);
        }
    }

    // ── POST /api/ingest — FORCE_FULL ─────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/ingest — FORCE_FULL strategy")
    class ForceFullIngestTests {

        @Test
        @DisplayName("Calls purgeData then pipeline for FORCE_FULL")
        void callsPurgeThenPipelineForForceFull() throws Exception {
            when(strategyResolver.resolve(eq("svc"), eq(true)))
                    .thenReturn(IngestionStrategy.FORCE_FULL);
            when(pipeline.ingest(any(Path.class), eq("svc")))
                    .thenReturn(ingestionResult("svc", 5, 1, 2, 4, 8));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc", true)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalCodeChunks").value(5));

            var order = inOrder(deletionService, pipeline);
            order.verify(deletionService).purgeData("svc");
            order.verify(pipeline).ingest(any(Path.class), eq("svc"));
        }

        @Test
        @DisplayName("Does not call incremental service for FORCE_FULL")
        void doesNotCallIncrementalForForceFull() throws Exception {
            when(strategyResolver.resolve(anyString(), eq(true)))
                    .thenReturn(IngestionStrategy.FORCE_FULL);
            when(pipeline.ingest(any(Path.class), anyString()))
                    .thenReturn(ingestionResult("svc", 0, 0, 0, 0, 0));

            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("/tmp/repo", "svc", true)))
               .andExpect(status().isOk());

            verifyNoInteractions(incrementalService);
        }
    }

    // ── DELETE /api/services/{name} ───────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/services/{serviceName}")
    class DeleteServiceTests {

        @Test
        @DisplayName("Returns 200 with deletion message when service is registered")
        void returns200WhenServiceExists() throws Exception {
            when(registryService.isRegistered("payment-svc")).thenReturn(true);

            mvc.perform(delete("/api/services/payment-svc"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.serviceName").value("payment-svc"))
               .andExpect(jsonPath("$.message").value("Service deleted successfully"));
        }

        @Test
        @DisplayName("Delegates to ServiceDeletionService when service exists")
        void callsDeletionServiceWhenExists() throws Exception {
            when(registryService.isRegistered("payment-svc")).thenReturn(true);

            mvc.perform(delete("/api/services/payment-svc"))
               .andExpect(status().isOk());

            verify(deletionService).delete("payment-svc");
        }

        @Test
        @DisplayName("Returns 404 when service is not registered")
        void returns404WhenServiceNotFound() throws Exception {
            when(registryService.isRegistered("unknown-svc")).thenReturn(false);

            mvc.perform(delete("/api/services/unknown-svc"))
               .andExpect(status().isNotFound());

            verifyNoInteractions(deletionService);
        }
    }

    // ── GET /api/services ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/services")
    class ListServicesTests {

        @Test
        @DisplayName("Returns 200 with list of registered services")
        void returnsAllServices() throws Exception {
            when(registryService.listAll()).thenReturn(List.of(
                    serviceRecord("task-manager"),
                    serviceRecord("payment-svc")));

            mvc.perform(get("/api/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(2))
               .andExpect(jsonPath("$[0].serviceName").value("task-manager"))
               .andExpect(jsonPath("$[1].serviceName").value("payment-svc"));
        }

        @Test
        @DisplayName("Returns empty list when no services are registered")
        void returnsEmptyListWhenNoServices() throws Exception {
            when(registryService.listAll()).thenReturn(List.of());

            mvc.perform(get("/api/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(0));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String body(String repoPath, String serviceName, boolean force) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("repoPath", repoPath, "serviceName", serviceName,
                        "force", String.valueOf(force)));
    }

    private static IngestionPipeline.IngestionResult ingestionResult(
            String serviceName, int codeChunks, int docChunks,
            int classes, int methods, int cfgNodes) {
        return new IngestionPipeline.IngestionResult(
                serviceName, codeChunks, docChunks, classes, methods, cfgNodes, Map.of());
    }

    private static ServiceRecord serviceRecord(String serviceName) {
        return new ServiceRecord(serviceName, "/tmp/" + serviceName,
                ServiceStatus.ACTIVE, Instant.now(), Instant.now(), 10);
    }
}
