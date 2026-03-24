package com.servicelens.registry;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Immutable snapshot of a registered service in the ServiceLens registry.
 *
 * <p>Maps directly to a row in the {@code service_registry} PostgreSQL table.
 * All timestamps are stored in UTC.</p>
 *
 * @param serviceName   logical identifier for the service (primary key)
 * @param repoPath      absolute path to the repository root on disk
 * @param status        current lifecycle status of this service
 * @param ingestedAt    timestamp of the first successful ingestion
 * @param lastUpdatedAt timestamp of the most recent ingestion or update
 * @param fileCount     number of files indexed in the last ingestion run
 */
public record ServiceRecord(
        String serviceName,
        String repoPath,
        ServiceStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant ingestedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastUpdatedAt,
        int fileCount
) {}
