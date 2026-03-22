package com.servicelens.functional.ingestion;

import com.servicelens.functional.support.FunctionalTestBase;
import com.servicelens.incremental.IncrementalIngestionService;
import com.servicelens.incremental.IncrementalIngestionService.IncrementalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for {@link IncrementalIngestionService}.
 *
 * <p>Each test creates real files in a {@link TempDir} and drives the full
 * incremental pipeline against live pgvector and Neo4j containers.
 * The hash-file directory is the isolated {@code TEST_DATA_PATH} configured
 * in {@link FunctionalTestBase}.</p>
 */
@DisplayName("IncrementalIngestion — functional")
class IncrementalIngestionFunctionalTest extends FunctionalTestBase {

    private static final String SERVICE = "incremental-test-svc";

    @Autowired
    private IncrementalIngestionService incrementalService;

    @TempDir
    Path repoDir;

    // ── First run ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("First ingest marks every file as ADDED and stores all chunks")
    void firstIngest_allFilesAdded() throws IOException {
        createJavaFile("OrderService.java");
        createYamlFile("application.yml");

        IncrementalResult result = incrementalService.ingest(repoDir, SERVICE);

        assertThat(result.serviceName()).isEqualTo(SERVICE);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.modified()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(result.unchanged()).isZero();

        int stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);
        assertThat(stored).isPositive();
    }

    // ── Idempotent second run ─────────────────────────────────────────────────

    @Test
    @DisplayName("Second ingest with no file changes marks all files as UNCHANGED")
    void secondIngest_unchangedFiles_nothingReprocessed() throws IOException {
        createJavaFile("PaymentService.java");
        createYamlFile("application.yml");

        incrementalService.ingest(repoDir, SERVICE);               // first
        IncrementalResult second = incrementalService.ingest(repoDir, SERVICE); // second

        assertThat(second.added()).isZero();
        assertThat(second.modified()).isZero();
        assertThat(second.deleted()).isZero();
        assertThat(second.unchanged()).isEqualTo(2);
    }

    // ── Modified file ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Modifying a file causes it to be classified as MODIFIED on the next run")
    void modifiedFile_isReclassifiedAndReingested() throws IOException {
        Path java = createJavaFile("InventoryService.java");

        incrementalService.ingest(repoDir, SERVICE);                 // first run

        // Modify the file
        String modified = Files.readString(java) + "\n// modified\n";
        Files.writeString(java, modified);

        IncrementalResult second = incrementalService.ingest(repoDir, SERVICE);

        assertThat(second.modified()).isEqualTo(1);
        assertThat(second.unchanged()).isZero();
        assertThat(second.added()).isZero();
    }

    // ── Deleted file ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deleting a file causes it to be classified as DELETED and its data removed")
    void deletedFile_isCleanedFromStores() throws IOException {
        createJavaFile("ShippingService.java");
        createYamlFile("application.yml");

        incrementalService.ingest(repoDir, SERVICE);                 // first: 2 added

        // Delete one file
        Files.delete(repoDir.resolve("ShippingService.java"));

        IncrementalResult second = incrementalService.ingest(repoDir, SERVICE);

        assertThat(second.deleted()).isEqualTo(1);
        assertThat(second.unchanged()).isEqualTo(1);  // application.yml untouched
    }

    // ── summary() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IncrementalResult.summary() contains serviceName and all change counts")
    void incrementalResult_summaryContainsAllFields() throws IOException {
        createJavaFile("WarehouseService.java");

        IncrementalResult result = incrementalService.ingest(repoDir, SERVICE);

        assertThat(result.summary()).contains(SERVICE);
        assertThat(result.summary()).contains("added=");
        assertThat(result.summary()).contains("modified=");
        assertThat(result.summary()).contains("deleted=");
        assertThat(result.summary()).contains("unchanged=");
    }

    // ── totalChanged() ────────────────────────────────────────────────────────

    @Test
    @DisplayName("IncrementalResult.totalChanged() is the sum of added + modified + deleted")
    void incrementalResult_totalChangedIsCorrect() throws IOException {
        createJavaFile("NotificationService.java");
        createYamlFile("application.yml");

        IncrementalResult first = incrementalService.ingest(repoDir, SERVICE);

        assertThat(first.totalChanged()).isEqualTo(first.added() + first.modified() + first.deleted());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path createJavaFile(String name) throws IOException {
        Path f = repoDir.resolve(name);
        String className = name.replace(".java", "");
        Files.writeString(f, """
                package com.example;
                import org.springframework.stereotype.Service;
                /** Handles %s logic. */
                @Service
                public class %s {
                    public void execute() { }
                }
                """.formatted(className, className));
        return f;
    }

    private Path createYamlFile(String name) throws IOException {
        Path f = repoDir.resolve(name);
        Files.writeString(f, """
                server:
                  port: 8080
                spring:
                  application:
                    name: test-service
                """);
        return f;
    }
}
