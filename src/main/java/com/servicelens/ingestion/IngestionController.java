package com.servicelens.ingestion;

import com.servicelens.incremental.IncrementalIngestionService;
import com.servicelens.retrieval.CodeRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST controller that exposes the ServiceLens ingestion and retrieval pipeline
 * over HTTP.
 *
 * <p>Provides three endpoints:</p>
 * <ul>
 *   <li>{@code POST /api/ingest} — trigger full ingestion of a service repository,
 *       parsing all supported source files and storing embeddings and graph nodes.</li>
 *   <li>{@code POST /api/ingest/incremental} — trigger incremental ingestion, processing
 *       only files that have changed since the last run (detected via SHA-256 fingerprint).
 *       Significantly faster than a full ingest for small change sets; avoids creating
 *       duplicate embeddings in the vector store.</li>
 *   <li>{@code GET /api/retrieve} — perform a semantic similarity search against
 *       a service's indexed chunks and return the top matching results.</li>
 * </ul>
 *
 * <p>This controller is intended for development, integration testing, and
 * internal tooling use. The ingestion endpoint is synchronous and may take
 * several minutes for large repositories.</p>
 */
@RestController
@RequestMapping("/api")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final IngestionPipeline pipeline;
    private final IncrementalIngestionService incrementalService;
    private final CodeRetriever retriever;

    public IngestionController(IngestionPipeline pipeline,
                               IncrementalIngestionService incrementalService,
                               CodeRetriever retriever) {
        this.pipeline           = pipeline;
        this.incrementalService = incrementalService;
        this.retriever          = retriever;
    }

    /**
     * Trigger a full ingestion of a service repository.
     *
     * <p>Walks the directory tree rooted at {@code repoPath}, processes all
     * supported source files (Java, YAML, Markdown, SQL, OpenAPI), generates
     * embeddings, and writes the results to both the pgvector store and the
     * Neo4j knowledge graph.</p>
     *
     * @param request a JSON body containing {@code repoPath} (absolute path to the
     *                repository root) and {@code serviceName} (logical service identifier)
     * @return an {@link IngestionPipeline.IngestionResult} summary wrapped in a 200 OK response
     */
    @PostMapping("/ingest")
    public ResponseEntity<IngestionPipeline.IngestionResult> ingest(
            @RequestBody Map<String, String> request) {
        String repoPath = request.get("repoPath");
        String serviceName = request.get("serviceName");
        log.info("Ingestion request: service={} repoPath={}", serviceName, repoPath);
        IngestionPipeline.IngestionResult result =
                pipeline.ingest(Path.of(repoPath), serviceName);
        log.info("Ingestion complete: service={} ", serviceName);
        return ResponseEntity.ok(result);
    }

    /**
     * Trigger an incremental ingestion of a service repository.
     *
     * <p>Compares each file's current SHA-256 hash against the hash stored from the
     * previous run.  Only files classified as <em>added</em>, <em>modified</em>, or
     * <em>deleted</em> are processed; unchanged files are skipped entirely.  This
     * prevents duplicate embeddings in the vector store that would otherwise result
     * from re-running the full {@code /api/ingest} endpoint on an already-indexed
     * repository.</p>
     *
     * <p>Use this endpoint for all re-ingestion calls after the initial full ingest.
     * Typical speedup over full ingest: ~32× for a 600-file service with 3 changed
     * files.</p>
     *
     * @param request a JSON body containing {@code repoPath} (absolute path to the
     *                repository root) and {@code serviceName} (logical service identifier)
     * @return an {@link IncrementalIngestionService.IncrementalResult} summary wrapped
     *         in a 200 OK response
     */
    @PostMapping("/ingest/incremental")
    public ResponseEntity<IncrementalIngestionService.IncrementalResult> ingestIncremental(
            @RequestBody Map<String, String> request) {
        String repoPath    = request.get("repoPath");
        String serviceName = request.get("serviceName");
        log.info("Incremental ingestion request: service={} repoPath={}", serviceName, repoPath);
        IncrementalIngestionService.IncrementalResult result =
                incrementalService.ingest(Path.of(repoPath), serviceName);
        log.info("Incremental ingestion complete: {}", result.summary());
        return ResponseEntity.ok(result);
    }

    /**
     * Perform a semantic similarity search against a service's indexed chunks.
     *
     * <p>Embeds the query string and searches the pgvector store for the
     * {@code topK} most similar chunks belonging to the specified service.
     * Results are returned as a flat list of maps containing chunk content
     * and metadata.</p>
     *
     * @param query       the natural-language query to search for
     * @param serviceName the logical service name to restrict the search to
     * @param topK        the maximum number of results to return (defaults to 8)
     * @return a list of result maps each containing {@code "content"} and
     *         {@code "metadata"} keys, wrapped in a 200 OK response
     */
    @GetMapping("/retrieve")
    public ResponseEntity<List<Map<String, Object>>> retrieve(
            @RequestParam String query,
            @RequestParam String serviceName,
            @RequestParam(defaultValue = "8") int topK) {

        log.debug("Retrieve request: service={} topK={} query='{}'", serviceName, topK, query);
        List<Document> docs = retriever.retrieve(query, serviceName, topK);

        List<Map<String, Object>> results = docs.stream()
                .map(doc -> Map.of(
                        "content", doc.getContent(),
                        "metadata", doc.getMetadata()
                ))
                .toList();

        return ResponseEntity.ok(results);
    }
}
