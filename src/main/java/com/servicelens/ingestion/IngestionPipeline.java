package com.servicelens.ingestion;

import com.servicelens.cfg.CfgNode;
import com.servicelens.chunking.CodeChunk;
import com.servicelens.chunking.FileProcessor;
import com.servicelens.chunking.processors.JavaFileProcessor;
import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.incremental.FileFingerprinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private static final Set<String> SKIP_DIRS = Set.of(
            "target", "build", ".git", ".idea", "node_modules",
            ".mvn", "out", "dist", ".gradle"
    );

    private final List<FileProcessor> processors;
    private final VectorStore vectorStore;
    private final KnowledgeGraphService graphService;
    private final JavaFileProcessor javaProcessor;
    private final JdbcTemplate jdbcTemplate;
    private final FileFingerprinter fingerprinter;

    public IngestionPipeline(List<FileProcessor> processors,
                             VectorStore vectorStore,
                             KnowledgeGraphService graphService,
                             JavaFileProcessor javaProcessor,
                             JdbcTemplate jdbcTemplate,
                             FileFingerprinter fingerprinter) {
        this.processors   = processors.stream()
                .sorted(Comparator.comparingInt(FileProcessor::priority).reversed())
                .collect(Collectors.toList());
        this.vectorStore   = vectorStore;
        this.graphService  = graphService;
        this.javaProcessor = javaProcessor;
        this.jdbcTemplate  = jdbcTemplate;
        this.fingerprinter = fingerprinter;
    }

    public IngestionResult ingest(Path repoRoot, String serviceName) {
        log.info("═══ Starting ingestion: {} ═══", serviceName);
        purgeService(serviceName);

        List<CodeChunk> allChunks         = new ArrayList<>();
        List<Document> allDocChunks       = new ArrayList<>(); // Javadoc docs
        List<CfgNode> allCfgNodes         = new ArrayList<>();
        Map<String, String> currentHashes = new HashMap<>();

        // For graph
        var allClassNodes  = new ArrayList<com.servicelens.graph.domain.ClassNode>();
        var allMethodNodes = new ArrayList<com.servicelens.graph.domain.MethodNode>();
        var allDataFlows   = new ArrayList<com.servicelens.dfg.MethodDataFlow>();

        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    return SKIP_DIRS.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (javaProcessor.supports(file)) {
                        // ── Java: full processing ──────────────────────────
                        JavaFileProcessor.JavaFileResult result =
                                javaProcessor.processFile(file, serviceName);

                        allChunks.addAll(result.chunks());
                        allDocChunks.addAll(result.documentationChunks());
                        allClassNodes.addAll(result.classNodes());
                        allMethodNodes.addAll(result.methodNodes());
                        allCfgNodes.addAll(result.cfgNodes());
                        allDataFlows.addAll(result.dataFlows());

                        log.debug("Java: {} → {} chunks, {} doc chunks, {} classes, {} methods, {} cfg nodes",
                                file.getFileName(),
                                result.chunks().size(), result.documentationChunks().size(),
                                result.classNodes().size(), result.methodNodes().size(),
                                result.cfgNodes().size());

                        recordHash(file, currentHashes);
                    } else {
                        processors.stream()
                                .filter(p -> !(p instanceof JavaFileProcessor))
                                .filter(p -> p.supports(file))
                                .findFirst()
                                .ifPresent(p -> {
                                    try {
                                        allChunks.addAll(p.process(file, serviceName));
                                        recordHash(file, currentHashes);
                                    }
                                    catch (Exception e) {
                                        log.warn("Failed: {} — {}", file.getFileName(), e.getMessage());
                                    }
                                });
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Ingestion walk failed", e);
        }

        // ── Step 1: Save knowledge graph ────────────────────────────────────
        log.info("Saving graph: {} classes, {} methods to Neo4j",
                allClassNodes.size(), allMethodNodes.size());
        graphService.saveFileResult(
                new JavaFileProcessor.JavaFileResult(
                        allChunks, allDocChunks, allClassNodes,
                        allMethodNodes, allCfgNodes, allDataFlows));

        // ── Step 2: Embed and store code chunks ─────────────────────────────
        log.info("Embedding {} code chunks into pgvector", allChunks.size());
        List<Document> codeDocs = allChunks.stream()
                .map(CodeChunk::toDocument).collect(Collectors.toList());
        storeInBatches(codeDocs, "code");

        // ── Step 3: Embed and store documentation chunks separately ─────────
        log.info("Embedding {} documentation chunks into pgvector", allDocChunks.size());
        storeInBatches(allDocChunks, "documentation");

        fingerprinter.saveHashes(serviceName, currentHashes);
        log.info("═══ Ingestion complete for: {} — {} file hashes saved ═══", serviceName, currentHashes.size());

        return new IngestionResult(
                serviceName,
                allChunks.size(),
                allDocChunks.size(),
                allClassNodes.size(),
                allMethodNodes.size(),
                allCfgNodes.size(),
                countByType(allChunks)
        );
    }

    private void recordHash(Path file, Map<String, String> hashes) {
        try {
            hashes.put(file.toAbsolutePath().toString(), fingerprinter.computeHash(file));
        } catch (IOException e) {
            log.warn("Could not hash file {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /**
     * Deletes all existing data for a service before re-ingesting.
     *
     * <p>Prevents duplicate chunks and graph nodes when the same service is
     * ingested more than once via the full ingest endpoint.</p>
     *
     * @param serviceName the service whose prior data should be removed
     */
    private void purgeService(String serviceName) {
        log.info("Purging existing data for service: {}", serviceName);
        graphService.deleteServiceGraph(serviceName);
        jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'service_name' = ?",
                serviceName);
        log.debug("Purge complete for service: {}", serviceName);
    }

    private void storeInBatches(List<Document> docs, String label) {
        for (int i = 0; i < docs.size(); i += 50) {
            List<Document> batch = docs.subList(i, Math.min(i + 50, docs.size()));
            try {
                vectorStore.add(batch);
                log.debug("Stored {} batch {}/{}", label,
                        Math.min(i + 50, docs.size()), docs.size());
            } catch (Exception e) {
                log.warn("Batch storage failed for {} docs [{}-{}]: {}",
                        label, i, i + 50, e.getMessage());
            }
        }
    }

    private Map<String, Long> countByType(List<CodeChunk> chunks) {
        return chunks.stream().collect(
                Collectors.groupingBy(c -> c.chunkType().name(), Collectors.counting()));
    }

    public record IngestionResult(
            String serviceName,
            int totalCodeChunks,
            int totalDocChunks,
            int totalClasses,
            int totalMethods,
            int totalCfgNodes,
            Map<String, Long> chunksByType
    ) {}
}