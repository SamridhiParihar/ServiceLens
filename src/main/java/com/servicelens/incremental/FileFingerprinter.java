package com.servicelens.incremental;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Tracks file content hashes to detect changes between ingestion runs.
 *
 * <p>Before ingesting a file, its SHA-256 hash is computed and compared against
 * the hash stored from the previous run:</p>
 * <ul>
 *   <li>Hash matches stored value — file is unchanged; skip to save processing time.</li>
 *   <li>Hash differs from stored value — file was modified; delete old data and re-ingest.</li>
 *   <li>No stored hash — new file; ingest and store hash.</li>
 *   <li>Stored hash exists but file is absent — file was deleted; clean up stores.</li>
 * </ul>
 *
 * <p>Hashes are persisted as a JSON map at:</p>
 * <pre>
 *   {servicelens.data-path}/{serviceName}/file-hashes.json
 * </pre>
 * <p>Example format:</p>
 * <pre>
 * {
 *   "/path/to/PaymentService.java": "a3f8b2c1...",
 *   "/path/to/application.yml":     "d4e9f1a2..."
 * }
 * </pre>
 *
 * <p>The data path defaults to {@code ./servicelens-data} but can be overridden
 * via the {@code servicelens.data-path} application property.</p>
 */
@Component
public class FileFingerprinter {

    private static final Logger log = LoggerFactory.getLogger(FileFingerprinter.class);

    @Value("${servicelens.data-path:./servicelens-data}")
    private String dataPath;

    private final ObjectMapper objectMapper;

    public FileFingerprinter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Compute the SHA-256 hash of a file's entire content.
     *
     * <p>Reads the complete file into memory and computes a digest using the
     * JVM's built-in SHA-256 provider. The result is encoded as a lower-case
     * hex string.</p>
     *
     * @param file the path of the file to hash
     * @return a lower-case hexadecimal SHA-256 digest string such as {@code "a3f8b2c1..."}
     * @throws IOException              if the file cannot be read
     * @throws RuntimeException         if SHA-256 is unavailable in the JVM (should never happen)
     */
    public String computeHash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] content = Files.readAllBytes(file);
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Load the stored file-hash map for a service from disk.
     *
     * <p>Returns an empty {@link HashMap} if no hash file exists yet (first run)
     * or if the file cannot be parsed, so callers always receive a non-null result.</p>
     *
     * @param serviceName the logical service name whose hashes should be loaded
     * @return a mutable map of absolute file path strings to their stored SHA-256 hashes;
     *         empty if this is the first run or if the hash file is unreadable
     */
    public Map<String, String> loadHashes(String serviceName) {
        Path hashFile = getHashFilePath(serviceName);
        if (!Files.exists(hashFile)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(hashFile.toFile(),
                    new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            log.warn("Could not load hashes for {}: {} — starting fresh", serviceName, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Persist the current file-hash map for a service to disk.
     *
     * <p>Called after a successful ingestion run to update the baseline so that
     * the next run can accurately determine which files changed. The file is
     * written with pretty-printing for human readability.</p>
     *
     * @param serviceName the logical service name whose hashes should be saved
     * @param hashes      the complete map of absolute file path strings to SHA-256 hashes
     */
    public void saveHashes(String serviceName, Map<String, String> hashes) {
        Path hashFile = getHashFilePath(serviceName);
        try {
            Files.createDirectories(hashFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(hashFile.toFile(), hashes);
            log.debug("Saved {} file hashes for service: {}", hashes.size(), serviceName);
        } catch (IOException e) {
            log.warn("Could not save hashes for {}: {}", serviceName, e.getMessage());
        }
    }

    /**
     * Update the stored hash for a single file after it has been processed.
     *
     * <p>Loads the existing hash map, updates the entry for the given file path,
     * and persists the result. Suitable for updating one file at a time during
     * incremental ingestion without rewriting the entire map from scratch.</p>
     *
     * @param serviceName the logical service name that owns this file
     * @param filePath    the absolute path of the file whose hash should be updated
     * @param hash        the new SHA-256 hash to store for this file
     */
    public void updateHash(String serviceName, String filePath, String hash) {
        Map<String, String> hashes = loadHashes(serviceName);
        hashes.put(filePath, hash);
        saveHashes(serviceName, hashes);
    }

    /**
     * Remove the stored hash entry for a file that has been deleted from the repository.
     *
     * <p>Loads the existing hash map, removes the entry for the given path, and
     * persists the result.</p>
     *
     * @param serviceName the logical service name that owned this file
     * @param filePath    the absolute path of the deleted file
     */
    public void removeHash(String serviceName, String filePath) {
        Map<String, String> hashes = loadHashes(serviceName);
        hashes.remove(filePath);
        saveHashes(serviceName, hashes);
    }

    private Path getHashFilePath(String serviceName) {
        return Path.of(dataPath, serviceName, "file-hashes.json");
    }
}
