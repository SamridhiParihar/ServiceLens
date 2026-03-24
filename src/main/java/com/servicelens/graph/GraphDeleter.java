package com.servicelens.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Deletes graph nodes from Neo4j using flat Cypher via {@link Neo4jClient},
 * bypassing Spring Data Neo4j's object-graph mapper.
 *
 * <h3>Why not SDN repositories?</h3>
 * <p>SDN's {@code delete()} loads the full entity object-graph (including all
 * relationships) into the JVM before issuing DELETE statements — one round-trip
 * per node. On large services this causes N+1 queries and connection read timeouts.
 * Direct Cypher with {@code DETACH DELETE} lets Neo4j handle the entire operation
 * server-side.</p>
 *
 * <h3>Why no @Transactional?</h3>
 * <p>{@link Neo4jClient} auto-manages its own transaction per query when called
 * outside a Spring transaction context. Using {@code REQUIRES_NEW} forced Spring
 * to open a brand-new physical connection from the pool for every batch, which
 * exhausted the pool under load and caused connection timeouts. Letting
 * {@code Neo4jClient} self-manage avoids that entirely — each query gets a
 * connection, runs, and releases it immediately.</p>
 */
@Component
public class GraphDeleter {

    private static final Logger log = LoggerFactory.getLogger(GraphDeleter.class);

    private static final int BATCH_SIZE = 500;

    private final Neo4jClient neo4jClient;

    public GraphDeleter(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Delete one batch of up to {@value BATCH_SIZE} nodes per label for the given service.
     *
     * <p>Queries are label-specific so Neo4j uses the {@code serviceName} indexes
     * on each label rather than doing a full graph scan.</p>
     *
     * @param serviceName the service whose nodes to delete
     * @return total number of nodes deleted across all labels in this batch
     */
    public long deleteBatch(String serviceName) {
        return deleteByLabel("CfgNode", serviceName)
             + deleteByLabel("Method", serviceName)
             + deleteByLabel("Class", serviceName);
    }

    private long deleteByLabel(String label, String serviceName) {
        return neo4jClient
                .query("MATCH (n:" + label + " {serviceName: $serviceName}) " +
                       "WITH n LIMIT " + BATCH_SIZE + " DETACH DELETE n RETURN count(n) AS deleted")
                .bindAll(Map.of("serviceName", serviceName))
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("deleted").asLong())
                .one()
                .orElse(0L);
    }

    /**
     * Delete all nodes for a given file path.
     *
     * @param filePath absolute path of the file whose nodes should be removed
     */
    public void deleteByFilePath(String filePath) {
        neo4jClient
                .query("MATCH (n) WHERE n.filePath = $filePath DETACH DELETE n")
                .bind(filePath).to("filePath")
                .run();
    }
}
