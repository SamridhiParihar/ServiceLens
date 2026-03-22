package com.servicelens.incremental;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileFingerprinter}.
 *
 * <p>All tests operate against a temporary directory created by JUnit 5's
 * {@link TempDir} extension, so the file system is never polluted between
 * runs and no clean-up is needed after each test.</p>
 *
 * <p>A real {@link ObjectMapper} is used throughout — the hashing and JSON
 * serialisation logic is pure, deterministic, and requires no mocking.</p>
 *
 * <p>The {@code dataPath} field (normally injected by {@code @Value}) is
 * set reflectively via {@link ReflectionTestUtils} to point at the temp
 * directory, so the component under test runs exactly as it would in
 * production.</p>
 */
@DisplayName("FileFingerprinter")
class FileFingerprinterTest {

    /** The component under test — instantiated without a Spring context. */
    private FileFingerprinter fingerprinter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fingerprinter = new FileFingerprinter(new ObjectMapper());
        // Override @Value("${servicelens.data-path:./servicelens-data}") with temp dir
        ReflectionTestUtils.setField(fingerprinter, "dataPath", tempDir.toString());
    }

    // ─────────────────────────────────────────────────────────────────────
    // computeHash()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("computeHash()")
    class ComputeHashTests {

        @Test
        @DisplayName("Returns a 64-character lower-case hex SHA-256 string")
        void returnsHexSha256() throws IOException {
            Path file = writeFile("hello.java", "public class Hello {}");

            String hash = fingerprinter.computeHash(file);

            assertThat(hash)
                    .isNotBlank()
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Same content always produces the same hash (deterministic)")
        void deterministicForSameContent() throws IOException {
            String source = "public class Foo { void bar() {} }";
            Path file1 = writeFile("Foo1.java", source);
            Path file2 = writeFile("Foo2.java", source);

            assertThat(fingerprinter.computeHash(file1))
                    .isEqualTo(fingerprinter.computeHash(file2));
        }

        @Test
        @DisplayName("Different content produces different hashes")
        void differentContentDifferentHash() throws IOException {
            Path file1 = writeFile("A.java", "class A {}");
            Path file2 = writeFile("B.java", "class B {}");

            assertThat(fingerprinter.computeHash(file1))
                    .isNotEqualTo(fingerprinter.computeHash(file2));
        }

        @Test
        @DisplayName("Mutating file content changes its hash")
        void hashChangesAfterFileModification() throws IOException {
            Path file = writeFile("Service.java", "class Service { }");
            String before = fingerprinter.computeHash(file);

            Files.writeString(file, "class Service { void extra() {} }");
            String after = fingerprinter.computeHash(file);

            assertThat(after).isNotEqualTo(before);
        }

        @Test
        @DisplayName("Empty file produces a valid hash (not blank)")
        void emptyFileProducesValidHash() throws IOException {
            Path emptyFile = writeFile("Empty.java", "");

            String hash = fingerprinter.computeHash(emptyFile);

            assertThat(hash).isNotBlank().hasSize(64);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // loadHashes() / saveHashes()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("loadHashes() and saveHashes()")
    class LoadSaveTests {

        @Test
        @DisplayName("loadHashes() returns empty map when no hash file exists")
        void returnsEmptyMapOnFirstRun() {
            Map<String, String> hashes = fingerprinter.loadHashes("new-service");

            assertThat(hashes).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("saveHashes() then loadHashes() round-trips the map")
        void roundTrip() {
            Map<String, String> original = Map.of(
                    "/repo/PaymentService.java", "abc123",
                    "/repo/OrderService.java",   "def456"
            );

            fingerprinter.saveHashes("payment-service", original);
            Map<String, String> loaded = fingerprinter.loadHashes("payment-service");

            assertThat(loaded).isEqualTo(original);
        }

        @Test
        @DisplayName("saveHashes() creates parent directories automatically")
        void createsDirectoriesOnSave() {
            String serviceName = "brand-new-service";

            fingerprinter.saveHashes(serviceName, Map.of("/file.java", "abc"));

            Path expectedDir = tempDir.resolve(serviceName);
            assertThat(expectedDir).isDirectory();
            assertThat(expectedDir.resolve("file-hashes.json")).isRegularFile();
        }

        @Test
        @DisplayName("saveHashes() overwrites an existing hash file")
        void overwritesExistingFile() {
            fingerprinter.saveHashes("svc", Map.of("/a.java", "hash1"));
            fingerprinter.saveHashes("svc", Map.of("/b.java", "hash2"));

            Map<String, String> loaded = fingerprinter.loadHashes("svc");

            assertThat(loaded).containsOnlyKeys("/b.java");
        }

        @Test
        @DisplayName("loadHashes() returns empty map when JSON file is corrupt")
        void returnsEmptyMapOnCorruptFile() throws IOException {
            Path dir = Files.createDirectories(tempDir.resolve("broken-svc"));
            Files.writeString(dir.resolve("file-hashes.json"), "NOT_VALID_JSON{{{");

            Map<String, String> hashes = fingerprinter.loadHashes("broken-svc");

            assertThat(hashes).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // updateHash()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateHash()")
    class UpdateHashTests {

        @Test
        @DisplayName("Adds a new entry when file path is not yet tracked")
        void addsNewEntry() {
            fingerprinter.updateHash("svc", "/new/File.java", "deadbeef");

            Map<String, String> loaded = fingerprinter.loadHashes("svc");
            assertThat(loaded).containsEntry("/new/File.java", "deadbeef");
        }

        @Test
        @DisplayName("Updates an existing entry without disturbing other entries")
        void updatesExistingWithoutDisturbing() {
            fingerprinter.saveHashes("svc", Map.of(
                    "/FileA.java", "old_hash",
                    "/FileB.java", "unchanged_hash"
            ));

            fingerprinter.updateHash("svc", "/FileA.java", "new_hash");

            Map<String, String> loaded = fingerprinter.loadHashes("svc");
            assertThat(loaded)
                    .containsEntry("/FileA.java", "new_hash")
                    .containsEntry("/FileB.java", "unchanged_hash");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // removeHash()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeHash()")
    class RemoveHashTests {

        @Test
        @DisplayName("Removes the specified file entry from the persisted map")
        void removesEntry() {
            fingerprinter.saveHashes("svc", Map.of(
                    "/DeletedFile.java", "hash_a",
                    "/KeepFile.java",    "hash_b"
            ));

            fingerprinter.removeHash("svc", "/DeletedFile.java");

            Map<String, String> loaded = fingerprinter.loadHashes("svc");
            assertThat(loaded)
                    .doesNotContainKey("/DeletedFile.java")
                    .containsEntry("/KeepFile.java", "hash_b");
        }

        @Test
        @DisplayName("No-op when the path does not exist in the stored map")
        void noOpWhenKeyAbsent() {
            fingerprinter.saveHashes("svc", Map.of("/FileA.java", "abc"));

            // Should not throw — gracefully ignores missing key
            fingerprinter.removeHash("svc", "/NonExistent.java");

            Map<String, String> loaded = fingerprinter.loadHashes("svc");
            assertThat(loaded).containsOnlyKeys("/FileA.java");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Write a file with the given content into the shared temp directory and
     * return its {@link Path}.
     */
    private Path writeFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }
}
