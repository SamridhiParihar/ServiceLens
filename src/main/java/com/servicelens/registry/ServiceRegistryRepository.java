package com.servicelens.registry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed repository for the {@code service_registry} table.
 *
 * <p>Uses {@link JdbcTemplate} directly rather than Spring Data JPA to avoid
 * transaction-manager conflicts with the co-existing Neo4j transaction manager.</p>
 *
 * <p>The table is created on startup by
 * {@link com.servicelens.config.PostgresSchemaInitializer}.</p>
 */
@Repository
public class ServiceRegistryRepository {

    private final JdbcTemplate jdbc;

    public ServiceRegistryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Find a registered service by its logical name.
     *
     * @param serviceName the service to look up
     * @return an {@link Optional} containing the record, or empty if not registered
     */
    public Optional<ServiceRecord> findByServiceName(String serviceName) {
        List<ServiceRecord> rows = jdbc.query(
                "SELECT * FROM service_registry WHERE service_name = ?",
                MAPPER, serviceName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Return all registered services ordered by ingestion time descending.
     *
     * @return list of all service records, newest first
     */
    public List<ServiceRecord> findAll() {
        return jdbc.query(
                "SELECT * FROM service_registry ORDER BY ingested_at DESC",
                MAPPER);
    }

    /**
     * Insert or update a service record.
     *
     * <p>On conflict (same {@code service_name}), updates all fields except
     * {@code ingested_at} which is preserved from the original registration.</p>
     *
     * @param record the record to persist
     */
    public void upsert(ServiceRecord record) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO service_registry
                    (service_name, repo_path, status, ingested_at, last_updated_at, file_count)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (service_name) DO UPDATE SET
                    repo_path       = EXCLUDED.repo_path,
                    status          = EXCLUDED.status,
                    ingested_at     = COALESCE(service_registry.ingested_at, EXCLUDED.ingested_at),
                    last_updated_at = EXCLUDED.last_updated_at,
                    file_count      = EXCLUDED.file_count
                """,
                record.serviceName(),
                record.repoPath(),
                record.status().name(),
                Timestamp.from(record.ingestedAt() != null ? record.ingestedAt() : now),
                Timestamp.from(now),
                record.fileCount());
    }

    /**
     * Update only the status column for an existing service.
     *
     * @param serviceName the service to update
     * @param status      the new status to set
     */
    public void updateStatus(String serviceName, ServiceStatus status) {
        jdbc.update(
                "UPDATE service_registry SET status = ?, last_updated_at = ? WHERE service_name = ?",
                status.name(), Timestamp.from(Instant.now()), serviceName);
    }

    /**
     * Remove a service record from the registry.
     *
     * @param serviceName the service to remove
     */
    public void delete(String serviceName) {
        jdbc.update("DELETE FROM service_registry WHERE service_name = ?", serviceName);
    }

    /**
     * Check whether a service is present in the registry.
     *
     * @param serviceName the service to check
     * @return {@code true} if the service is registered
     */
    public boolean exists(String serviceName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_registry WHERE service_name = ?",
                Integer.class, serviceName);
        return count != null && count > 0;
    }

    // ── row mapper ────────────────────────────────────────────────────────────

    private static final RowMapper<ServiceRecord> MAPPER = (rs, rowNum) -> new ServiceRecord(
            rs.getString("service_name"),
            rs.getString("repo_path"),
            ServiceStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("ingested_at")     != null ? rs.getTimestamp("ingested_at").toInstant()     : null,
            rs.getTimestamp("last_updated_at") != null ? rs.getTimestamp("last_updated_at").toInstant() : null,
            rs.getInt("file_count"));
}
