package com.servicelens.ingestion;

import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.incremental.FileFingerprinter;
import com.servicelens.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deletes all data associated with a service from every store.
 *
 * <p>ServiceLens stores service data in three places:</p>
 * <ol>
 *   <li>Neo4j — class nodes, method nodes, CFG nodes, and their relationships.</li>
 *   <li>pgvector — embedded code and documentation chunks.</li>
 *   <li>Filesystem — SHA-256 fingerprint file used by incremental ingestion.</li>
 * </ol>
 *
 * <p>This service coordinates deletion across all three and updates the
 * {@link ServiceRegistryService} accordingly.</p>
 *
 * <h3>Two deletion modes</h3>
 * <ul>
 *   <li>{@link #delete(String)} — full deletion including registry removal.
 *       Used when a user explicitly removes a service via
 *       {@code DELETE /api/services/{name}}.</li>
 *   <li>{@link #purgeData(String)} — data-only deletion, registry entry kept.
 *       Used before a force re-ingestion so the registry row survives and
 *       the {@code ingestedAt} timestamp is preserved.</li>
 * </ul>
 */
@Service
public class ServiceDeletionService {

    private static final Logger log = LoggerFactory.getLogger(ServiceDeletionService.class);

    private final KnowledgeGraphService graphService;
    private final JdbcTemplate jdbcTemplate;
    private final FileFingerprinter fingerprinter;
    private final ServiceRegistryService registryService;

    public ServiceDeletionService(KnowledgeGraphService graphService,
                                  JdbcTemplate jdbcTemplate,
                                  FileFingerprinter fingerprinter,
                                  ServiceRegistryService registryService) {
        this.graphService    = graphService;
        this.jdbcTemplate    = jdbcTemplate;
        this.fingerprinter   = fingerprinter;
        this.registryService = registryService;
    }

    /**
     * Fully delete a service: remove all data from every store and remove
     * it from the registry.
     *
     * @param serviceName the service to delete
     */
    public void delete(String serviceName) {
        log.info("═══ Deleting service: {} ═══", serviceName);
        registryService.markDeleting(serviceName);
        purgeData(serviceName);
        registryService.remove(serviceName);
        log.info("═══ Service '{}' fully deleted ═══", serviceName);
    }

    /**
     * Delete all indexed data for a service without removing its registry entry.
     *
     * <p>Used before a {@link IngestionStrategy#FORCE_FULL} re-ingestion to wipe
     * stale data while preserving the registry row (and its original
     * {@code ingestedAt} timestamp).</p>
     *
     * @param serviceName the service whose data should be purged
     */
    public void purgeData(String serviceName) {
        log.info("Purging data for service: {}", serviceName);
        graphService.deleteServiceGraph(serviceName);
        jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'service_name' = ?", serviceName);
        fingerprinter.clearHashes(serviceName);
        log.debug("Purge complete for service: {}", serviceName);
    }
}
