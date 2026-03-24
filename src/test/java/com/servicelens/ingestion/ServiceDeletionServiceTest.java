package com.servicelens.ingestion;

import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.incremental.FileFingerprinter;
import com.servicelens.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ServiceDeletionService}.
 *
 * <p>Verifies that both deletion modes correctly coordinate across all four
 * stores: Neo4j, pgvector, file fingerprints, and the service registry.</p>
 */
@DisplayName("ServiceDeletionService")
@ExtendWith(MockitoExtension.class)
class ServiceDeletionServiceTest {

    @Mock private KnowledgeGraphService  graphService;
    @Mock private JdbcTemplate           jdbcTemplate;
    @Mock private FileFingerprinter      fingerprinter;
    @Mock private ServiceRegistryService registryService;

    private ServiceDeletionService deletionService;

    private static final String SERVICE = "task-manager";

    @BeforeEach
    void setUp() {
        deletionService = new ServiceDeletionService(
                graphService, jdbcTemplate, fingerprinter, registryService);
    }

    // ── delete() — full removal including registry ─────────────────────────────

    @Nested
    @DisplayName("delete() — full removal including registry")
    class FullDeleteTests {

        @Test
        @DisplayName("Deletes graph nodes, vector embeddings, hashes, and registry entry")
        void deletesAllStores() {
            deletionService.delete(SERVICE);

            verify(graphService).deleteServiceGraph(SERVICE);
            verify(jdbcTemplate).update(contains("vector_store"), eq(SERVICE));
            verify(fingerprinter).clearHashes(SERVICE);
            verify(registryService).remove(SERVICE);
        }

        @Test
        @DisplayName("Marks service as DELETING before data removal")
        void marksAsDeleteBeforeDataRemoval() {
            InOrder order = inOrder(registryService, graphService);

            deletionService.delete(SERVICE);

            order.verify(registryService).markDeleting(SERVICE);
            order.verify(graphService).deleteServiceGraph(SERVICE);
        }

        @Test
        @DisplayName("Removes from registry after all data is deleted")
        void removesFromRegistryAfterDataDeleted() {
            InOrder order = inOrder(graphService, registryService);

            deletionService.delete(SERVICE);

            order.verify(graphService).deleteServiceGraph(SERVICE);
            order.verify(registryService).remove(SERVICE);
        }
    }

    // ── purgeData() — data only, registry entry preserved ─────────────────────

    @Nested
    @DisplayName("purgeData() — data only, registry entry preserved")
    class PurgeDataTests {

        @Test
        @DisplayName("Deletes graph nodes, vector embeddings, and hashes")
        void deletesDataStores() {
            deletionService.purgeData(SERVICE);

            verify(graphService).deleteServiceGraph(SERVICE);
            verify(jdbcTemplate).update(contains("vector_store"), eq(SERVICE));
            verify(fingerprinter).clearHashes(SERVICE);
        }

        @Test
        @DisplayName("Does NOT touch the registry — registry entry is preserved for force re-ingest")
        void doesNotTouchRegistry() {
            deletionService.purgeData(SERVICE);

            verifyNoInteractions(registryService);
        }
    }
}
