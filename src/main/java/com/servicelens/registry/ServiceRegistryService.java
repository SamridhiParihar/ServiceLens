package com.servicelens.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service facade over the {@link ServiceRegistryRepository}.
 *
 * <p>Tracks which services have been ingested into ServiceLens, their current
 * lifecycle status, and metadata about the last ingestion run. This registry
 * is the source of truth that {@link com.servicelens.ingestion.IngestionStrategyResolver}
 * uses to decide between fresh, incremental, and force-full ingestion strategies.</p>
 */
@Service
public class ServiceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistryService.class);

    private final ServiceRegistryRepository repository;

    public ServiceRegistryService(ServiceRegistryRepository repository) {
        this.repository = repository;
    }

    /**
     * Return {@code true} if the service has previously been ingested.
     *
     * @param serviceName the service to check
     * @return {@code true} if the service exists in the registry
     */
    public boolean isRegistered(String serviceName) {
        return repository.exists(serviceName);
    }

    /**
     * Register a service for the first time after a fresh ingestion.
     *
     * @param serviceName the logical service identifier
     * @param repoPath    absolute path to the repository root
     * @param fileCount   number of files indexed
     * @return the persisted {@link ServiceRecord}
     */
    public ServiceRecord register(String serviceName, String repoPath, int fileCount) {
        Instant now = Instant.now();
        ServiceRecord record = new ServiceRecord(
                serviceName, repoPath, ServiceStatus.ACTIVE, now, now, fileCount);
        repository.upsert(record);
        log.info("Registered service: {} at {} ({} files)", serviceName, repoPath, fileCount);
        return record;
    }

    /**
     * Update an already-registered service after re-ingestion.
     *
     * <p>Preserves the original {@code ingestedAt} timestamp to record when
     * the service was first seen by ServiceLens.</p>
     *
     * @param serviceName the service to update
     * @param repoPath    absolute path to the repository root
     * @param fileCount   number of files indexed in this run
     * @return the updated {@link ServiceRecord}
     */
    public ServiceRecord update(String serviceName, String repoPath, int fileCount) {
        Instant originalIngestedAt = repository.findByServiceName(serviceName)
                .map(ServiceRecord::ingestedAt)
                .orElse(Instant.now());
        ServiceRecord record = new ServiceRecord(
                serviceName, repoPath, ServiceStatus.ACTIVE,
                originalIngestedAt, Instant.now(), fileCount);
        repository.upsert(record);
        log.info("Updated service registry: {} ({} files)", serviceName, fileCount);
        return record;
    }

    /**
     * Transition a service to {@link ServiceStatus#INGESTING} status.
     *
     * @param serviceName the service being ingested
     */
    public void markIngesting(String serviceName) {
        repository.updateStatus(serviceName, ServiceStatus.INGESTING);
    }

    /**
     * Transition a service to {@link ServiceStatus#DELETING} status.
     *
     * @param serviceName the service being deleted
     */
    public void markDeleting(String serviceName) {
        repository.updateStatus(serviceName, ServiceStatus.DELETING);
    }

    /**
     * Remove a service from the registry entirely.
     *
     * @param serviceName the service to remove
     */
    public void remove(String serviceName) {
        repository.delete(serviceName);
        log.info("Removed service from registry: {}", serviceName);
    }

    /**
     * Find a registered service by name.
     *
     * @param serviceName the service to look up
     * @return an {@link Optional} containing the record, or empty if not registered
     */
    public Optional<ServiceRecord> find(String serviceName) {
        return repository.findByServiceName(serviceName);
    }

    /**
     * Return all registered services, newest first.
     *
     * @return list of all service records
     */
    public List<ServiceRecord> listAll() {
        return repository.findAll();
    }
}
