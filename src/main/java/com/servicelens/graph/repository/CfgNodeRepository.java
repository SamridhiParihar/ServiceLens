package com.servicelens.graph.repository;

import com.servicelens.cfg.CfgNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;

/**
 * Spring Data Neo4j repository for {@link CfgNode} entities.
 *
 * <p>Provides query methods to retrieve and manage Control Flow Graph nodes
 * stored in Neo4j.  Each node represents a basic block in a method's CFG.
 *
 * <p>The index on {@code methodQualifiedName} (created by
 * {@link com.servicelens.config.Neo4jConfig}) ensures all per-method
 * lookups are O(log n) rather than O(n) full scans.
 *
 * @see CfgNode
 * @see com.servicelens.graph.KnowledgeGraphService
 * @see com.servicelens.cfg.CfgBuilder
 */
public interface CfgNodeRepository extends Neo4jRepository<CfgNode, Long> {

    /**
     * Return all CFG nodes belonging to a specific method.
     *
     * @param methodQualifiedName fully-qualified method name including parameter types
     * @return list of CFG nodes for the method; empty if none found
     */
    List<CfgNode> findByMethodQualifiedName(String methodQualifiedName);

    /**
     * Delete all CFG nodes belonging to a service.
     * Called during full service re-ingestion or service removal.
     *
     * @param serviceName the logical service name assigned during ingestion
     */
    void deleteByServiceName(String serviceName);

    /**
     * Find all exception-throw nodes within a method's CFG.
     *
     * <p>Useful for determining whether a method can propagate an unchecked
     * exception to its callers.  If a throw node has no {@code EXCEPTION_HANDLER}
     * reachable on every path, the exception can escape the method.
     *
     * @param methodQN fully-qualified method name
     * @return list of CFG nodes of type {@code EXCEPTION_THROW}
     */
    @Query("MATCH (n:CfgNode {methodQualifiedName: $methodQN, nodeType: 'EXCEPTION_THROW'}) " +
            "RETURN n")
    List<CfgNode> findThrowNodes(String methodQN);

    /**
     * Find all method-call nodes within a method's CFG.
     *
     * <p>Each returned node carries the called method's simple name in its
     * {@code calledMethod} field, useful for answering "what external calls
     * does this method make, and in what order?"
     *
     * @param methodQN fully-qualified method name
     * @return list of CFG nodes of type {@code METHOD_CALL}
     */
    @Query("MATCH (n:CfgNode {methodQualifiedName: $methodQN, nodeType: 'METHOD_CALL'}) " +
            "RETURN n")
    List<CfgNode> findCallNodes(String methodQN);

    /**
     * Rank methods in a service by structural complexity.
     *
     * <p>Complexity is measured as the count of {@code CONDITION} and
     * {@code LOOP_HEADER} nodes, which equals {@code cyclomaticComplexity - 1}.
     * Methods with the highest branch counts appear first.
     *
     * @param serviceName service to rank methods for
     * @param limit       maximum number of results
     * @return projections of (method, branchCount) ordered descending
     */
    @Query("MATCH (n:CfgNode) " +
            "WHERE n.serviceName = $serviceName " +
            "AND n.nodeType IN ['CONDITION', 'LOOP_HEADER'] " +
            "RETURN n.methodQualifiedName AS method, COUNT(n) AS branches " +
            "ORDER BY branches DESC LIMIT $limit")
    List<MethodComplexity> findMostComplexMethods(String serviceName, int limit);
}
