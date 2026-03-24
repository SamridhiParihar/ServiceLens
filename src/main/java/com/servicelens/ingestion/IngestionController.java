package com.servicelens.ingestion;

import com.servicelens.incremental.IncrementalIngestionService;
import com.servicelens.registry.ServiceRecord;
import com.servicelens.registry.ServiceRegistryService;
import com.servicelens.retrieval.CodeRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for the ServiceLens ingestion and service-management API.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /api/ingest} — smart ingestion: automatically selects
 *       {@link IngestionStrategy#FRESH}, {@link IngestionStrategy#INCREMENTAL},
 *       or {@link IngestionStrategy#FORCE_FULL} based on the registry and the
 *       optional {@code force} flag in the request body.</li>
 *   <li>{@code DELETE /api/services/{name}} — remove all data for a service
 *       from Neo4j, pgvector, and the registry.</li>
 *   <li>{@code GET /api/services} — list all registered services.</li>
 *   <li>{@code GET /api/services/{name}} — fetch a single service's registry record.</li>
 *   <li>{@code GET /api/retrieve} — semantic similarity search.</li>
 * </ul>
 *
 * <h3>Strategy selection</h3>
 * <pre>
 *   service NOT in registry            →  FRESH        (index everything, no purge)
 *   service in registry, force=false   →  INCREMENTAL  (hash diff, only changed files)
 *   service in registry, force=true    →  FORCE_FULL   (purge then re-index everything)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final IngestionPipeline            pipeline;
    private final IncrementalIngestionService  incrementalService;
    private final IngestionStrategyResolver    strategyResolver;
    private final ServiceDeletionService       deletionService;
    private final ServiceRegistryService       registryService;
    private final CodeRetriever                retriever;

    public IngestionController(IngestionPipeline pipeline,
                               IncrementalIngestionService incrementalService,
                               IngestionStrategyResolver strategyResolver,
                               ServiceDeletionService deletionService,
                               ServiceRegistryService registryService,
                               CodeRetriever retriever) {
        this.pipeline          = pipeline;
        this.incrementalService = incrementalService;
        this.strategyResolver  = strategyResolver;
        this.deletionService   = deletionService;
        this.registryService   = registryService;
        this.retriever         = retriever;
    }

    // ── POST /api/ingest ──────────────────────────────────────────────────────

    /**
     * Smart ingestion endpoint — selects the strategy automatically.
     *
     * <p>Request body fields:</p>
     * <ul>
     *   <li>{@code serviceName} — logical service identifier (required)</li>
     *   <li>{@code repoPath}    — absolute path to the repository root (required)</li>
     *   <li>{@code force}       — {@code "true"} to force a full purge + re-ingest,
     *                            defaults to {@code "false"} (optional)</li>
     * </ul>
     *
     * <p>Returns {@link IngestionPipeline.IngestionResult} for FRESH and FORCE_FULL,
     * or {@link IncrementalIngestionService.IncrementalResult} for INCREMENTAL.</p>
     *
     * @param request JSON body with {@code serviceName}, {@code repoPath},
     *                and optional {@code force}
     * @return 200 OK with the appropriate result record
     */
    @PostMapping("/ingest")
    public ResponseEntity<Object> ingest(@RequestBody Map<String, String> request) {
        String  serviceName = request.get("serviceName");
        String  repoPath    = request.get("repoPath");
        boolean force       = Boolean.parseBoolean(request.getOrDefault("force", "false"));

        log.info("Ingest request: service={} repoPath={} force={}", serviceName, repoPath, force);

        IngestionStrategy strategy = strategyResolver.resolve(serviceName, force);

        return switch (strategy) {
            case FRESH -> {
                IngestionPipeline.IngestionResult result =
                        pipeline.ingest(Path.of(repoPath), serviceName);
                registryService.register(serviceName, repoPath,
                        result.totalCodeChunks() + result.totalDocChunks());
                log.info("Fresh ingestion complete: {}", serviceName);
                yield ResponseEntity.ok((Object) result);
            }
            case INCREMENTAL -> {
                IncrementalIngestionService.IncrementalResult result =
                        incrementalService.ingest(Path.of(repoPath), serviceName);
                registryService.update(serviceName, repoPath, result.totalChanged());
                log.info("Incremental ingestion complete: {}", result.summary());
                yield ResponseEntity.ok((Object) result);
            }
            case FORCE_FULL -> {
                deletionService.purgeData(serviceName);
                IngestionPipeline.IngestionResult result =
                        pipeline.ingest(Path.of(repoPath), serviceName);
                registryService.update(serviceName, repoPath,
                        result.totalCodeChunks() + result.totalDocChunks());
                log.info("Force-full ingestion complete: {}", serviceName);
                yield ResponseEntity.ok((Object) result);
            }
        };
    }

    // ── DELETE /api/services/{serviceName} ────────────────────────────────────

    /**
     * Delete all data for a service from Neo4j, pgvector, fingerprint store,
     * and the service registry.
     *
     * @param serviceName the service to delete
     * @return 200 OK with a {@link DeleteResult} summary, or 404 if not registered
     */
    @DeleteMapping("/services/{serviceName}")
    public ResponseEntity<DeleteResult> deleteService(
            @PathVariable String serviceName) {
        if (!registryService.isRegistered(serviceName)) {
            log.warn("Delete requested for unknown service: {}", serviceName);
            return ResponseEntity.notFound().build();
        }
        deletionService.delete(serviceName);
        return ResponseEntity.ok(new DeleteResult(serviceName, "Service deleted successfully"));
    }

    // ── GET /api/services ─────────────────────────────────────────────────────

    /**
     * List all services currently registered in ServiceLens.
     *
     * @return 200 OK with a list of {@link ServiceRecord} objects
     */
    @GetMapping("/services")
    public ResponseEntity<List<ServiceRecord>> listServices() {
        return ResponseEntity.ok(registryService.listAll());
    }

    /**
     * Fetch a single service's registry record.
     *
     * @param serviceName the service to look up
     * @return 200 OK with the {@link ServiceRecord}, or 404 if not found
     */
    @GetMapping("/services/{serviceName}")
    public ResponseEntity<ServiceRecord> getService(@PathVariable String serviceName) {
        Optional<ServiceRecord> record = registryService.find(serviceName);
        return record.map(ResponseEntity::ok)
                     .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── GET /api/retrieve ─────────────────────────────────────────────────────

    /**
     * Perform a semantic similarity search against a service's indexed chunks.
     *
     * @param query       natural-language query
     * @param serviceName service to restrict the search to
     * @param topK        maximum number of results (default 8)
     * @return 200 OK with a list of content + metadata maps
     */
    @GetMapping("/retrieve")
    public ResponseEntity<List<Map<String, Object>>> retrieve(
            @RequestParam String query,
            @RequestParam String serviceName,
            @RequestParam(defaultValue = "8") int topK) {

        log.debug("Retrieve: service={} topK={} query='{}'", serviceName, topK, query);
        List<Document> docs = retriever.retrieve(query, serviceName, topK);

        List<Map<String, Object>> results = docs.stream()
                .map(doc -> Map.of(
                        "content",  (Object) doc.getContent(),
                        "metadata", (Object) doc.getMetadata()))
                .toList();

        return ResponseEntity.ok(results);
    }

    // ── result records ────────────────────────────────────────────────────────

    /**
     * Response body returned by {@link #deleteService(String)}.
     *
     * @param serviceName the service that was deleted
     * @param message     human-readable confirmation message
     */
    public record DeleteResult(String serviceName, String message) {}
}
