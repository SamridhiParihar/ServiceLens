package com.servicelens.ingestion;

import com.servicelens.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the appropriate {@link IngestionStrategy} for an ingestion request.
 *
 * <p>Decision logic:</p>
 * <pre>
 *   service NOT in registry  →  FRESH        (first-time; no purge needed)
 *   service in registry, force=false  →  INCREMENTAL  (hash diff; only changed files)
 *   service in registry, force=true   →  FORCE_FULL   (purge + full re-ingest)
 * </pre>
 *
 * <p>This keeps the decision fully testable in isolation from the pipeline,
 * the deletion service, and the registry update logic.</p>
 */
@Component
public class IngestionStrategyResolver {

    private static final Logger log = LoggerFactory.getLogger(IngestionStrategyResolver.class);

    private final ServiceRegistryService registryService;

    public IngestionStrategyResolver(ServiceRegistryService registryService) {
        this.registryService = registryService;
    }

    /**
     * Determine the ingestion strategy for the given service and force flag.
     *
     * @param serviceName the logical service identifier
     * @param force       {@code true} to force a full purge and re-ingest
     * @return the resolved {@link IngestionStrategy}
     */
    public IngestionStrategy resolve(String serviceName, boolean force) {
        boolean registered = registryService.isRegistered(serviceName);

        IngestionStrategy strategy;
        if (!registered) {
            strategy = IngestionStrategy.FRESH;
        } else if (force) {
            strategy = IngestionStrategy.FORCE_FULL;
        } else {
            strategy = IngestionStrategy.INCREMENTAL;
        }

        log.info("Strategy for '{}': {} (registered={}, force={})",
                serviceName, strategy, registered, force);
        return strategy;
    }
}
