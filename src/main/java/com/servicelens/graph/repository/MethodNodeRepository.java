package com.servicelens.graph.repository;

import com.servicelens.graph.domain.MethodNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;

/**
 * Spring Data Neo4j repository for {@link MethodNode} entities.
 *
 * <p>Provides CRUD operations inherited from {@link Neo4jRepository} as well as
 * domain-specific Cypher queries for:</p>
 * <ul>
 *   <li>Call-graph traversal (callers, downstream call chains)</li>
 *   <li>Spring-annotation–specific lookups (endpoints, transactional, scheduled, event handlers)</li>
 *   <li>Complexity filtering (high-cyclomatic-complexity methods)</li>
 *   <li>Override tracking (which methods override a given base method)</li>
 *   <li>File-level deletion for incremental re-ingestion</li>
 * </ul>
 *
 * <p>All queries that accept {@code serviceName} are scoped to a single logical
 * service, preventing cross-service result pollution.</p>
 */
public interface MethodNodeRepository extends Neo4jRepository<MethodNode, String> {

    /**
     * Find all methods belonging to a given service.
     *
     * @param serviceName the logical service name (e.g., "payment-service")
     * @return list of all {@link MethodNode} instances for the service
     */
    List<MethodNode> findByServiceName(String serviceName);

    /**
     * Find all methods in a specific class within a given service.
     *
     * @param className   the simple class name (e.g., "OrderValidator")
     * @param serviceName the logical service name
     * @return list of {@link MethodNode} instances for the class
     */
    List<MethodNode> findByClassNameAndServiceName(String className, String serviceName);

    /**
     * Find all callers of a method (reverse CALLS traversal).
     *
     * <p>Returns all methods that have a {@code CALLS} relationship pointing to
     * any method whose qualified name contains {@code methodName}. Limited to 20
     * results to prevent overwhelming the context window.</p>
     *
     * @param methodName the simple or qualified method name to search for
     * @return up to 20 {@link MethodNode} instances that call the target method
     */
    @Query("MATCH (caller:Method)-[:CALLS]->(callee:Method) " +
            "WHERE callee.qualifiedName CONTAINS $methodName " +
            "RETURN caller LIMIT 20")
    List<MethodNode> findCallers(String methodName);

    /**
     * Find the downstream call chain from a method up to 5 hops deep.
     *
     * <p>Traverses {@code CALLS} relationships transitively to discover all methods
     * that the given method (directly or indirectly) invokes within the same service.
     * Scoped to the given service to avoid cross-service noise. Limited to 30 results.</p>
     *
     * @param methodName  the simple or qualified name of the root method
     * @param serviceName the logical service name to restrict traversal to
     * @return up to 30 {@link MethodNode} instances in the downstream call chain
     */
    @Query("MATCH path = (m:Method)-[:CALLS*1..5]->(called:Method) " +
            "WHERE m.qualifiedName CONTAINS $methodName " +
            "AND called.serviceName = $serviceName " +
            "RETURN called LIMIT 30")
    List<MethodNode> findCallChain(String methodName, String serviceName);

    /**
     * Find all REST endpoint methods in a service.
     *
     * <p>Returns methods with {@code isEndpoint = true}, which are methods
     * annotated with {@code @GetMapping}, {@code @PostMapping}, etc.</p>
     *
     * @param serviceName the logical service name
     * @return list of endpoint {@link MethodNode} instances
     */
    @Query("MATCH (m:Method {serviceName: $serviceName, isEndpoint: true}) RETURN m")
    List<MethodNode> findEndpoints(String serviceName);

    /**
     * Find all methods annotated with {@code @Transactional} in a service.
     *
     * @param serviceName the logical service name
     * @return list of transactional {@link MethodNode} instances
     */
    @Query("MATCH (m:Method {serviceName: $serviceName, isTransactional: true}) RETURN m")
    List<MethodNode> findTransactionalMethods(String serviceName);

    /**
     * Find all methods annotated with {@code @Scheduled} in a service.
     *
     * @param serviceName the logical service name
     * @return list of scheduled {@link MethodNode} instances
     */
    @Query("MATCH (m:Method {serviceName: $serviceName, isScheduled: true}) RETURN m")
    List<MethodNode> findScheduledMethods(String serviceName);

    /**
     * Find all event-handler methods (e.g., {@code @EventListener}) in a service.
     *
     * @param serviceName the logical service name
     * @return list of event-handler {@link MethodNode} instances
     */
    @Query("MATCH (m:Method {serviceName: $serviceName, isEventHandler: true}) RETURN m")
    List<MethodNode> findEventHandlers(String serviceName);

    /**
     * Find methods with cyclomatic complexity at or above a given threshold.
     *
     * <p>Useful for identifying hot-spots that carry high bug risk and need
     * the most thorough unit-test coverage. Results are ordered from highest
     * to lowest complexity.</p>
     *
     * @param serviceName   the logical service name
     * @param minComplexity the inclusive lower bound on cyclomatic complexity
     * @return list of high-complexity {@link MethodNode} instances, ordered descending
     */
    @Query("MATCH (m:Method) " +
            "WHERE m.serviceName = $serviceName " +
            "AND m.cyclomaticComplexity >= $minComplexity " +
            "RETURN m ORDER BY m.cyclomaticComplexity DESC")
    List<MethodNode> findHighComplexityMethods(String serviceName, int minComplexity);

    /**
     * Find all methods that override a given base method.
     *
     * <p>Traverses {@code OVERRIDES} relationships to identify polymorphic
     * implementations of the specified method in any subclass.</p>
     *
     * @param methodName the simple or qualified name of the base method
     * @return list of {@link MethodNode} instances that override the base method
     */
    @Query("MATCH (m:Method)-[:OVERRIDES]->(base:Method) " +
            "WHERE base.qualifiedName CONTAINS $methodName " +
            "RETURN m")
    List<MethodNode> findOverridingMethods(String methodName);

    /**
     * Delete all method nodes (and their relationships) associated with a given file path.
     *
     * <p>Used during incremental re-ingestion to clean up stale method nodes before
     * re-parsing and re-indexing a changed file. Uses {@code DETACH DELETE} to remove
     * both the node and all its relationships atomically.</p>
     *
     * @param filePath the absolute file path whose method nodes should be deleted
     */
    @Query("MATCH (m:Method {filePath: $filePath}) DETACH DELETE m")
    void deleteByFilePath(String filePath);
}
