package com.servicelens.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates ServiceLens-owned PostgreSQL tables on application startup.
 *
 * <p>All DDL statements are idempotent ({@code CREATE TABLE IF NOT EXISTS}) so they
 * are safe to run on every startup regardless of whether the schema already exists.</p>
 *
 * <h3>Tables managed here</h3>
 * <ul>
 *   <li>{@code service_registry} — tracks every service ingested into ServiceLens,
 *       its lifecycle status, repo path, ingestion timestamps, and file count.</li>
 * </ul>
 *
 * <p>The {@code vector_store} table is created by Spring AI's pgvector auto-configuration
 * ({@code spring.ai.vectorstore.pgvector.initialize-schema: true}) and is not
 * managed here.</p>
 */
@Configuration
public class PostgresSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaInitializer.class);

    /**
     * ApplicationRunner that creates the {@code service_registry} table.
     *
     * @param jdbc the auto-configured JDBC template
     * @return a startup runner that is non-blocking for the rest of the context
     */
    @Bean
    public ApplicationRunner postgresSchemaInit(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS service_registry (
                        service_name    VARCHAR(255)             PRIMARY KEY,
                        repo_path       TEXT                     NOT NULL,
                        status          VARCHAR(50)              NOT NULL,
                        ingested_at     TIMESTAMP WITH TIME ZONE,
                        last_updated_at TIMESTAMP WITH TIME ZONE,
                        file_count      INT                      DEFAULT 0
                    )
                    """);
            log.info("PostgreSQL schema initialised (service_registry ready)");
        };
    }
}
