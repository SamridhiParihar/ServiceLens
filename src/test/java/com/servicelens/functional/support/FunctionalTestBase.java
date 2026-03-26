package com.servicelens.functional.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Abstract base for all ServiceLens functional tests.
 *
 * <h3>Container lifecycle — start once, share everywhere</h3>
 * <p>Containers are <em>not</em> managed by {@code @Testcontainers} / {@code @Container}
 * annotations.  Instead, {@link SharedTestInfrastructure} holds them as JVM-level
 * static singletons initialised in a {@code static} block.  The JVM class-loader
 * guarantees the block runs exactly once, so containers start the first time any
 * functional test class is loaded and stay up for the entire test suite.  The
 * Testcontainers Ryuk reaper cleans them up when the JVM exits.</p>
 *
 * <h3>Dev / test isolation</h3>
 * <p>Testcontainers binds each container to a <em>random free port</em>.  Those
 * ports are injected into the Spring context by {@link #configureProperties} via
 * {@code @DynamicPropertySource}, so the production database on {@code 5432} and
 * the dev Neo4j on {@code 7687} are never touched.</p>
 *
 * <h3>Data isolation between tests</h3>
 * <p>{@link #cleanData()} runs after every test and truncates {@code vector_store},
 * deletes all Neo4j nodes, and removes fingerprint files.  Schema objects
 * (tables, indexes, Neo4j constraints) are preserved.</p>
 *
 * <h3>Graceful skip when infrastructure is missing</h3>
 * <ul>
 *   <li>{@link #requireInfrastructure()} skips the test class if Docker is not
 *       available or Testcontainers failed to start the containers.</li>
 *   <li>{@link #requireOllama()} skips the test class if Ollama is not reachable
 *       at {@code localhost:11434}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("functional")
public abstract class FunctionalTestBase {

    // ── Temp directory for FileFingerprinter hash files ───────────────────────

    static final Path TEST_DATA_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "servicelens-functional-" + ProcessHandle.current().pid()
    );

    // ── Pre-test guards ───────────────────────────────────────────────────────

    /**
     * Skips the entire test class if Testcontainers failed to start
     * (Docker unavailable, image pull failed, etc.).
     */
    @BeforeAll
    static void requireInfrastructure() {
        Assumptions.assumeTrue(
                SharedTestInfrastructure.AVAILABLE,
                "Test containers not available — Docker may not be running. " +
                "Start Docker Desktop and rerun."
        );
    }

    /**
     * Skips the entire test class if Ollama is not reachable.
     * Tests run fine in CI without Ollama — they will be marked as skipped,
     * not failed.
     */
    @BeforeAll
    static void requireOllama() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://localhost:11434").openConnection();
            conn.setConnectTimeout(2_000);
            conn.setReadTimeout(2_000);
            conn.setRequestMethod("GET");
            conn.connect();
            Assumptions.assumeTrue(conn.getResponseCode() > 0,
                    "Ollama returned unexpected response");
        } catch (IOException e) {
            Assumptions.assumeTrue(false,
                    "Ollama not available at localhost:11434 — start Ollama and " +
                    "ensure nomic-embed-text + phi3 are pulled.");
        }
    }

    // ── Dynamic property injection (random container ports → Spring context) ──

    /**
     * Injects the Testcontainers-allocated random ports into the Spring context
     * before it starts.  This overwrites the static fallback values in
     * {@code application-functional.yml} and ensures the app connects to the
     * isolated test containers, never to the developer's local databases.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Guard: if containers aren't running, skip injection to avoid NPE
        // (requireInfrastructure @BeforeAll will skip the test class anyway)
        if (!SharedTestInfrastructure.AVAILABLE) return;

        registry.add("spring.datasource.url",      SharedTestInfrastructure.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", SharedTestInfrastructure.POSTGRES::getUsername);
        registry.add("spring.datasource.password", SharedTestInfrastructure.POSTGRES::getPassword);

        registry.add("spring.neo4j.uri",
                SharedTestInfrastructure.NEO4J::getBoltUrl);
        registry.add("spring.neo4j.authentication.username",
                () -> "neo4j");
        registry.add("spring.neo4j.authentication.password",
                SharedTestInfrastructure.NEO4J::getAdminPassword);

        registry.add("servicelens.data-path", TEST_DATA_PATH::toString);
    }

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected Driver neo4jDriver;

    // ── Per-test data cleanup ─────────────────────────────────────────────────

    /**
     * Wipes all test data after every test so the next test starts from a clean
     * slate.  Schema (tables, Neo4j constraints, HNSW index) is preserved.
     */
    @AfterEach
    void cleanData() {
        // 1. PostgreSQL — remove all stored vectors
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");

        // 2. PostgreSQL — remove all service registry entries
        jdbcTemplate.execute("DELETE FROM service_registry");

        // 3. PostgreSQL — remove all conversation sessions
        jdbcTemplate.execute("DELETE FROM conversation_sessions");

        // 4. Neo4j — delete all nodes and relationships
        try (Session session = neo4jDriver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }

        // 5. FileFingerprinter hash files
        deleteDirectory(TEST_DATA_PATH);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Recursively deletes a directory, silently ignoring any errors. */
    protected static void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }
}
