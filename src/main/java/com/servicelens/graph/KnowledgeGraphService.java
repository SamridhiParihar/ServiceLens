package com.servicelens.graph;

import com.servicelens.cfg.CfgNode;
import com.servicelens.chunking.processors.JavaFileProcessor;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.repository.CfgNodeRepository;
import com.servicelens.graph.repository.ClassNodeRepository;
import com.servicelens.graph.repository.MethodNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Facade over all Neo4j repositories in the ServiceLens knowledge graph.
 *
 * <p>This service acts as the single point of entry for all graph reads and writes.
 * It coordinates across three underlying repositories — {@link ClassNodeRepository},
 * {@link MethodNodeRepository}, and {@link CfgNodeRepository} — and exposes a
 * unified API to the rest of the application.</p>
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Persisting parsed Java file results (class nodes, method nodes, CFG nodes)</li>
 *   <li>Deleting graph data by service name or individual file path during re-ingestion</li>
 *   <li>Providing read operations for call-chain traversal, inheritance hierarchy queries,
 *       dependency impact analysis, and Spring-specific endpoint/annotation lookups</li>
 *   <li>Supplying aggregated service-level statistics for health dashboards</li>
 * </ul>
 *
 * <p>All methods that mutate the graph are annotated with {@code @Transactional} to
 * ensure atomicity. Read operations rely on repository-level Neo4j queries.</p>
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final ClassNodeRepository classRepo;
    private final MethodNodeRepository methodRepo;
    private final CfgNodeRepository cfgRepo;
    private final CfgSaver cfgSaver;
    private final Neo4jClient neo4jClient;

    public KnowledgeGraphService(
            ClassNodeRepository classRepo,
            MethodNodeRepository methodRepo,
            CfgNodeRepository cfgRepo,
            CfgSaver cfgSaver,
            Neo4jClient neo4jClient) {
        this.classRepo   = classRepo;
        this.methodRepo  = methodRepo;
        this.cfgRepo     = cfgRepo;
        this.cfgSaver    = cfgSaver;
        this.neo4jClient = neo4jClient;
    }

    /**
     * Persist all graph nodes produced by parsing a single Java file.
     *
     * <p>Saves class nodes (including INHERITS, IMPLEMENTS, DEPENDS_ON relationships),
     * method nodes (including CALLS and OVERRIDES relationships), and CFG nodes in
     * bulk. Each save is individually guarded so that a failure on one node does not
     * abort the rest of the file.</p>
     *
     * @param result the parsed result produced by {@link JavaFileProcessor}, containing
     *               class nodes, method nodes, CFG nodes, and documentation chunks
     */
    @Transactional
    public void saveFileResult(JavaFileProcessor.JavaFileResult result) {
        result.classNodes().forEach(classNode -> {
            try { classRepo.save(classNode); }
            catch (Exception e) {
                log.warn("Failed to save class {}: {}",
                        classNode.getQualifiedName(), e.getMessage());
            }
        });

        result.methodNodes().forEach(methodNode -> {
            try { methodRepo.save(methodNode); }
            catch (Exception e) {
                log.warn("Failed to save method {}: {}",
                        methodNode.getQualifiedName(), e.getMessage());
            }
        });

        if (!result.cfgNodes().isEmpty()) {
            try {
                cfgSaver.save(result.cfgNodes());
                log.debug("Saved {} CFG nodes", result.cfgNodes().size());
            } catch (Throwable e) {
                log.warn("Failed to save CFG nodes (skipped): {}", e.getMessage());
            }
        }
    }

    /**
     * Delete all graph nodes associated with an entire service.
     *
     * <p>Removes CFG nodes first, then method nodes (which cascades CALLS
     * relationships), then class nodes (which cascades INHERITS, IMPLEMENTS, and
     * DEPENDS_ON relationships). Used during a full re-index of a service.</p>
     *
     * @param serviceName the logical name of the service whose graph should be wiped
     */
    @Transactional
    public void deleteServiceGraph(String serviceName) {
        log.info("Deleting graph for service: {}", serviceName);

        cfgRepo.deleteByServiceName(serviceName);

        methodRepo.findByServiceName(serviceName).forEach(methodRepo::delete);

        classRepo.findByServiceName(serviceName).forEach(classRepo::delete);

        log.info("Graph deleted for service: {}", serviceName);
    }

    /**
     * Delete all graph nodes associated with a specific source file.
     *
     * <p>Issues a single Cypher {@code DETACH DELETE} against the {@code filePath}
     * property, removing every node that originated from the given file along with
     * all their relationships. Used by incremental ingestion when a file changes.</p>
     *
     * @param filePath the absolute file path whose nodes should be removed
     */
    @Transactional
    public void deleteByFilePath(String filePath) {
        neo4jClient.query(
                "MATCH (n) WHERE n.filePath = $filePath DETACH DELETE n"
        ).bind(filePath).to("filePath").run();
        log.debug("Deleted graph nodes for file: {}", filePath);
    }

    /**
     * Find all methods that directly call the specified method.
     *
     * <p>Traverses CALLS relationships in the reverse direction (callee → callers).
     * Results are limited to 20 to avoid overwhelming the context window.</p>
     *
     * @param methodQN the qualified name of the method to look up callers for
     * @return list of {@link MethodNode} instances that call {@code methodQN},
     *         or an empty list if none are found
     */
    public List<MethodNode> findCallers(String methodQN) {
        return methodRepo.findCallers(methodQN);
    }

    /**
     * Traverse the downstream call chain from the specified method within a service.
     *
     * <p>Follows CALLS relationships outward up to 5 hops, returning all reachable
     * methods that belong to the given service. Useful for blast-radius analysis.</p>
     *
     * @param methodQN    the qualified name of the entry-point method
     * @param serviceName the service scope to restrict results to
     * @return list of {@link MethodNode} instances reachable via CALLS from {@code methodQN},
     *         limited to 30 results
     */
    public List<MethodNode> findCallChain(String methodQN, String serviceName) {
        return methodRepo.findCallChain(methodQN, serviceName);
    }

    /**
     * Find all classes that directly inherit from the specified class.
     *
     * <p>Traverses INHERITS relationships in the forward direction to locate
     * immediate subclasses.</p>
     *
     * @param classQN the qualified name of the parent class
     * @return list of {@link ClassNode} instances that extend {@code classQN}
     */
    public List<ClassNode> findSubclasses(String classQN) {
        return classRepo.findSubclasses(classQN);
    }

    /**
     * Find all classes that implement the specified interface.
     *
     * <p>Traverses IMPLEMENTS relationships to locate concrete implementors.</p>
     *
     * @param interfaceQN the qualified name of the interface
     * @return list of {@link ClassNode} instances that implement {@code interfaceQN}
     */
    public List<ClassNode> findImplementors(String interfaceQN) {
        return classRepo.findImplementors(interfaceQN);
    }

    /**
     * Find all classes within a service that depend on the specified class.
     *
     * <p>Traverses DEPENDS_ON relationships in the reverse direction to identify
     * which classes inject or reference the target. Used for impact analysis.</p>
     *
     * @param classQN     the qualified name of the target class
     * @param serviceName the service scope to restrict results to
     * @return list of {@link ClassNode} instances that depend on {@code classQN}
     */
    public List<ClassNode> findDependents(String classQN, String serviceName) {
        return classRepo.findDependents(classQN, serviceName);
    }

    /**
     * Find all REST endpoint methods within a service.
     *
     * <p>Returns methods whose {@code isEndpoint} flag is {@code true}, meaning they
     * carry a Spring MVC mapping annotation ({@code @GetMapping}, {@code @PostMapping},
     * etc.).</p>
     *
     * @param serviceName the service to query
     * @return list of {@link MethodNode} instances that are HTTP endpoints
     */
    public List<MethodNode> findEndpoints(String serviceName) {
        return methodRepo.findEndpoints(serviceName);
    }

    /**
     * Find all methods annotated with {@code @Transactional} within a service.
     *
     * @param serviceName the service to query
     * @return list of {@link MethodNode} instances that are transactional
     */
    public List<MethodNode> findTransactionalMethods(String serviceName) {
        return methodRepo.findTransactionalMethods(serviceName);
    }

    /**
     * Find all methods in a service whose cyclomatic complexity meets or exceeds a threshold.
     *
     * <p>Results are ordered by complexity descending so the most complex methods
     * appear first.</p>
     *
     * @param serviceName   the service to query
     * @param minComplexity the minimum cyclomatic complexity score (inclusive)
     * @return list of {@link MethodNode} instances ordered by complexity descending
     */
    public List<MethodNode> findHighComplexityMethods(String serviceName, int minComplexity) {
        return methodRepo.findHighComplexityMethods(serviceName, minComplexity);
    }

    /**
     * Retrieve the control-flow graph nodes for a specific method.
     *
     * <p>Returns all {@link CfgNode} instances associated with the given method's
     * qualified name. Used for control-flow analysis and path coverage queries.</p>
     *
     * @param methodQN the qualified name of the method
     * @return list of {@link CfgNode} instances representing the CFG of the method,
     *         or an empty list if no CFG was stored
     */
    public List<CfgNode> getCfgForMethod(String methodQN) {
        return cfgRepo.findByMethodQualifiedName(methodQN);
    }

    /**
     * Compute aggregated statistics for a service, intended for health dashboards.
     *
     * <p>Returns counts for: total classes, total methods, REST endpoint methods,
     * and methods with high cyclomatic complexity (threshold: 10).</p>
     *
     * @param serviceName the service to compute statistics for
     * @return an immutable {@link Map} with keys {@code classes}, {@code methods},
     *         {@code endpoints}, and {@code highComplexityMethods}
     */
    public Map<String, Object> getServiceStats(String serviceName) {
        long classCount  = classRepo.findByServiceName(serviceName).size();
        long methodCount = methodRepo.findByServiceName(serviceName).size();
        long endpointCount = methodRepo.findEndpoints(serviceName).size();
        long highComplexity = methodRepo
                .findHighComplexityMethods(serviceName, 10).size();

        return Map.of(
                "classes", classCount,
                "methods", methodCount,
                "endpoints", endpointCount,
                "highComplexityMethods", highComplexity
        );
    }
}
