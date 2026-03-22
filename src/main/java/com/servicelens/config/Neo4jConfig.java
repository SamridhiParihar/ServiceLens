package com.servicelens.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.data.neo4j.core.mapping.Neo4jMappingContext;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Neo4j-specific Spring configuration.
 *
 * <p>Enables Spring Data Neo4j repository scanning for the
 * {@code com.servicelens.graph.repository} package and registers an
 * {@link org.springframework.boot.ApplicationRunner} that creates
 * Neo4j schema constraints and indexes on every application startup.
 *
 * <h2>Why these indexes matter</h2>
 * <p>{@code qualifiedName} is queried on every CALLS resolution, graph
 * expansion, and callers lookup.  Without an index that is an O(n) full
 * node scan across potentially millions of Method and Class nodes.
 * With an index the same lookup is O(log n).
 *
 * <p>{@code serviceName} appears in every WHERE clause to isolate one
 * micro-service from another.  The composite index
 * {@code (serviceName, simpleName)} accelerates the very common query
 * pattern "find all methods named X inside service Y".
 *
 * <p>All Cypher DDL statements are idempotent ({@code IF NOT EXISTS}),
 * so they are safe to execute on every startup even if the schema
 * already exists from a previous run.
 *
 * <h2>Transaction manager wiring</h2>
 * <p>This project uses both {@code spring-boot-starter-data-jpa} (for JdbcTemplate /
 * pgvector) and {@code spring-boot-starter-data-neo4j}.  Spring Boot's
 * {@code Neo4jDataAutoConfiguration} wires the transaction manager into
 * {@link Neo4jTemplate} via an {@code ObjectProvider} — which only resolves when
 * there is <em>exactly one</em> {@code PlatformTransactionManager} in the context.
 * When both {@code JpaTransactionManager} and {@code Neo4jTransactionManager} are
 * present the provider returns nothing and {@code Neo4jTemplate.transactionTemplate}
 * stays {@code null}, causing a {@link NullPointerException} on every repository
 * {@code save()} call.  Declaring both beans explicitly here bypasses the
 * conditional autoconfiguration and guarantees correct wiring regardless of
 * autoconfiguration ordering.</p>
 */
@Configuration
@EnableNeo4jRepositories(
        basePackages = "com.servicelens.graph.repository",
        transactionManagerRef = "neo4jTransactionManager")
@EnableTransactionManagement
public class Neo4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConfig.class);

    /**
     * Dedicated Neo4j transaction manager.
     *
     * <p>Marked {@code @Primary} so that {@code @Transactional} annotations without
     * an explicit {@code transactionManager} attribute (such as those on
     * {@link com.servicelens.graph.KnowledgeGraphService}) use this manager by
     * default.  This is correct because all transactional writes in ServiceLens
     * target Neo4j, not JPA entities.</p>
     *
     * @param driver the auto-configured Neo4j driver
     * @return a configured {@link Neo4jTransactionManager}
     */
    @Bean("neo4jTransactionManager")
    @Primary
    public Neo4jTransactionManager neo4jTransactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }

    /**
     * Neo4j template explicitly wired with the dedicated transaction manager.
     *
     * <p>Overrides Spring Boot's autoconfigured template so that
     * {@link Neo4jTemplate#setTransactionManager} is always called with the
     * correct {@link Neo4jTransactionManager}, ensuring
     * {@code Neo4jTemplate.transactionTemplate} is never {@code null} when
     * repository {@code save()} methods are invoked without an outer Neo4j
     * transaction.</p>
     *
     * @param neo4jClient        the Spring Data Neo4j low-level client
     * @param mappingContext     entity metadata used by the template for persistence
     * @param transactionManager the dedicated Neo4j transaction manager
     * @return a fully configured {@link Neo4jTemplate}
     */
    @Bean
    public Neo4jTemplate neo4jTemplate(
            Neo4jClient neo4jClient,
            Neo4jMappingContext mappingContext,
            @Qualifier("neo4jTransactionManager") Neo4jTransactionManager transactionManager) {
        Neo4jTemplate template = new Neo4jTemplate(neo4jClient, mappingContext);
        template.setTransactionManager(transactionManager);
        return template;
    }

    /**
     * ApplicationRunner that initialises the Neo4j schema on startup.
     *
     * <p>Creates uniqueness constraints on {@code qualifiedName} for Class,
     * Method, and CfgNode labels, and performance indexes on
     * {@code serviceName}, {@code simpleName}, {@code isEndpoint}, and
     * {@code methodQualifiedName} (CFG nodes).
     *
     * <p>Individual failures are caught and logged at DEBUG level rather
     * than crashing the application, because Neo4j may raise an error if
     * the constraint or index already exists (driver-version dependent
     * behaviour).
     *
     * @param neo4jClient the Spring Data Neo4j low-level client
     * @return a non-blocking startup runner
     */
    @Bean
    public org.springframework.boot.ApplicationRunner neo4jSchemaInit(Neo4jClient neo4jClient) {
        return args -> {
            runCypher(neo4jClient,
                    "CREATE CONSTRAINT class_qn_unique IF NOT EXISTS " +
                            "FOR (c:Class) REQUIRE c.qualifiedName IS UNIQUE");

            runCypher(neo4jClient,
                    "CREATE CONSTRAINT method_qn_unique IF NOT EXISTS " +
                            "FOR (m:Method) REQUIRE m.qualifiedName IS UNIQUE");

            runCypher(neo4jClient,
                    "CREATE CONSTRAINT cfg_node_id IF NOT EXISTS " +
                            "FOR (n:CfgNode) REQUIRE n.id IS UNIQUE");

            runCypher(neo4jClient,
                    "CREATE INDEX class_service IF NOT EXISTS " +
                            "FOR (c:Class) ON (c.serviceName)");

            runCypher(neo4jClient,
                    "CREATE INDEX method_service IF NOT EXISTS " +
                            "FOR (m:Method) ON (m.serviceName)");

            runCypher(neo4jClient,
                    "CREATE INDEX method_service_name IF NOT EXISTS " +
                            "FOR (m:Method) ON (m.serviceName, m.simpleName)");

            runCypher(neo4jClient,
                    "CREATE INDEX method_endpoint IF NOT EXISTS " +
                            "FOR (m:Method) ON (m.isEndpoint)");

            runCypher(neo4jClient,
                    "CREATE INDEX cfg_method IF NOT EXISTS " +
                            "FOR (n:CfgNode) ON (n.methodQualifiedName)");

            log.info("Neo4j schema initialised successfully");
        };
    }

    /**
     * Execute a single Cypher DDL statement, swallowing exceptions that
     * indicate the schema element already exists.
     *
     * @param client the Neo4j client to use for execution
     * @param cypher the Cypher CREATE CONSTRAINT / CREATE INDEX statement
     */
    private void runCypher(Neo4jClient client, String cypher) {
        try {
            client.query(cypher).run();
        } catch (Exception e) {
            log.debug("Schema statement skipped (may already exist): {}", e.getMessage());
        }
    }
}
