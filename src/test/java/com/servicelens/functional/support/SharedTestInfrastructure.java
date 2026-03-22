package com.servicelens.functional.support;

import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * JVM-scoped singleton for Testcontainers infrastructure.
 *
 * <h3>Why a dedicated class?</h3>
 * <p>{@code @Container} on {@code static} fields in a JUnit base class starts
 * and stops containers once <em>per test class</em>, not once per JVM.  Each
 * subclass that inherits {@code @Testcontainers} triggers a separate lifecycle
 * event, which means containers are torn down and recreated between
 * {@code CodeRetrieverFunctionalTest} and {@code IntentBasedRetrieverFunctionalTest},
 * wasting 30–60 s and defeating Spring's application-context cache.</p>
 *
 * <p>The static-initializer pattern here guarantees that containers are started
 * <em>exactly once</em> when this class is first loaded by the JVM —
 * regardless of how many functional test classes run.  Cleanup is handled
 * automatically by the Testcontainers Ryuk resource-reaper (a background
 * Docker container that removes orphaned containers when the JVM exits).</p>
 *
 * <h3>Isolation from the dev environment</h3>
 * <p>Testcontainers allocates <em>random free ports</em> for both containers.
 * These ports are injected into the Spring context via
 * {@link FunctionalTestBase#configureProperties}.  The running dev database
 * (typically PostgreSQL on 5432, Neo4j on 7687) is never touched.</p>
 *
 * <h3>Failure handling</h3>
 * <p>If Docker is unavailable or containers fail to start, {@link #AVAILABLE}
 * is set to {@code false} and the container references are {@code null}.
 * {@link FunctionalTestBase} checks this flag in a {@code @BeforeAll} method
 * and skips the test class gracefully via JUnit {@code Assumptions} rather
 * than failing with a {@code NullPointerException}.</p>
 */
public final class SharedTestInfrastructure {

    /** PostgreSQL 16 with the {@code pgvector} extension pre-installed. */
    public static final PostgreSQLContainer<?> POSTGRES;

    /** Neo4j 5 graph database. */
    public static final Neo4jContainer<?> NEO4J;

    /**
     * {@code true} when both containers started successfully.
     * {@code false} when Docker is unavailable or startup failed.
     * Checked by {@link FunctionalTestBase#requireInfrastructure()}.
     */
    public static final boolean AVAILABLE;

    static {
        PostgreSQLContainer<?> pg   = null;
        Neo4jContainer<?>      neo4j = null;
        boolean ok = false;

        try {
            pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("servicelens")
                    .withUsername("servicelens")
                    .withPassword("servicelens");

            neo4j = new Neo4jContainer<>("neo4j:5.15.0")
                    .withAdminPassword("servicelens-test");

            // Start both in parallel — reduces total startup time by ~50%
            Startables.deepStart(pg, neo4j).join();
            ok = pg.isRunning() && neo4j.isRunning();
        } catch (Exception e) {
            // Docker not available or image pull failed — tests will skip, not fail
            System.err.println("[SharedTestInfrastructure] Could not start containers: " + e.getMessage());
        }

        POSTGRES = pg;
        NEO4J    = neo4j;
        AVAILABLE = ok;
    }

    private SharedTestInfrastructure() {}
}
