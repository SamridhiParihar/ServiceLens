package com.servicelens.graph;

import com.servicelens.cfg.CfgEdge;
import com.servicelens.cfg.CfgNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists CFG nodes to Neo4j using flat Cypher via {@link Neo4jClient},
 * bypassing Spring Data Neo4j's recursive object-graph serialiser.
 *
 * <h3>Why not {@code CfgNodeRepository.saveAll()}?</h3>
 * <p>SDN's {@code saveAll()} recursively follows every
 * {@code CfgNode → CfgEdge → CfgNode → ...} relationship chain to build the
 * full subgraph before issuing any Cypher. On real codebases (100+ methods,
 * deeply nested loops/try-catch blocks) this recursion overflows the Java
 * call stack ({@link StackOverflowError}), poisoning the enclosing transaction
 * and rolling back the already-persisted class and method nodes.</p>
 *
 * <h3>Two-pass flat Cypher strategy</h3>
 * <ol>
 *   <li><strong>Pass 1 — nodes:</strong> MERGE every {@code CfgNode} as a flat
 *       property map. A {@code batchId} (UUID) combined with a per-node
 *       integer {@code tempId} (list index) acts as a collision-free batch
 *       key without requiring any entity-model change.</li>
 *   <li><strong>Pass 2 — edges:</strong> For every outgoing {@link CfgEdge},
 *       MATCH both endpoints by their {@code batchId/tempId} and CREATE the
 *       {@code CFG_EDGE} relationship with edge properties.</li>
 *   <li><strong>Cleanup:</strong> Remove the temporary {@code batchId} and
 *       {@code tempId} properties so they do not pollute the stored graph.</li>
 * </ol>
 *
 * <p>All three steps run inside a single {@link Propagation#REQUIRES_NEW}
 * transaction that is fully independent of the caller's class/method-node
 * transaction.</p>
 */
@Component
public class CfgSaver {

    private static final Logger log = LoggerFactory.getLogger(CfgSaver.class);

    private final Neo4jClient neo4jClient;

    public CfgSaver(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Persist {@code nodes} and all their outgoing {@link CfgEdge}s to Neo4j
     * using flat Cypher — no SDN recursive serialisation.
     *
     * @param nodes the CFG nodes to persist (may include cycles)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(List<CfgNode> nodes) {
        if (nodes.isEmpty()) return;

        // Unique key for this batch so parallel ingestions don't collide
        String batchId = UUID.randomUUID().toString();

        // Map each CfgNode object identity → integer index (tempId)
        Map<CfgNode, Integer> tempIds = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            tempIds.put(nodes.get(i), i);
        }

        // ── Pass 1: MERGE flat node properties ───────────────────────────
        for (CfgNode n : nodes) {
            neo4jClient.query(
                    "MERGE (n:CfgNode {batchId: $batchId, tempId: $tempId}) " +
                    "SET n.methodQualifiedName = $mq, " +
                    "    n.serviceName         = $sn, " +
                    "    n.nodeType            = $nt, " +
                    "    n.code                = $code, " +
                    "    n.startLine           = $sl, " +
                    "    n.endLine             = $el, " +
                    "    n.condition           = $cond, " +
                    "    n.calledMethod        = $cm, " +
                    "    n.exceptionType       = $et"
            )
            .bind(batchId)                                    .to("batchId")
            .bind(tempIds.get(n))                             .to("tempId")
            .bind(n.getMethodQualifiedName())                 .to("mq")
            .bind(n.getServiceName())                         .to("sn")
            .bind(n.getNodeType() != null
                    ? n.getNodeType().name() : null)          .to("nt")
            .bind(n.getCode())                                .to("code")
            .bind(n.getStartLine())                           .to("sl")
            .bind(n.getEndLine())                             .to("el")
            .bind(n.getCondition())                           .to("cond")
            .bind(n.getCalledMethod())                        .to("cm")
            .bind(n.getExceptionType())                       .to("et")
            .run();
        }

        // ── Pass 2: CREATE CFG_EDGE relationships ─────────────────────────
        for (CfgNode src : nodes) {
            if (src.getSuccessors() == null) continue;
            for (CfgEdge edge : src.getSuccessors()) {
                CfgNode tgt = edge.getTarget();
                if (tgt == null || !tempIds.containsKey(tgt)) continue;

                neo4jClient.query(
                        "MATCH (s:CfgNode {batchId: $batchId, tempId: $srcId}) " +
                        "MATCH (t:CfgNode {batchId: $batchId, tempId: $tgtId}) " +
                        "CREATE (s)-[:CFG_EDGE {edgeType: $et, " +
                        "                       exceptionType: $exc, " +
                        "                       conditionText: $ct}]->(t)"
                )
                .bind(batchId)                                        .to("batchId")
                .bind(tempIds.get(src))                               .to("srcId")
                .bind(tempIds.get(tgt))                               .to("tgtId")
                .bind(edge.getEdgeType() != null
                        ? edge.getEdgeType().name() : null)           .to("et")
                .bind(edge.getExceptionType())                        .to("exc")
                .bind(edge.getConditionText())                        .to("ct")
                .run();
            }
        }

        // ── Cleanup: remove batch-local temp properties ───────────────────
        neo4jClient.query(
                "MATCH (n:CfgNode {batchId: $batchId}) REMOVE n.batchId, n.tempId"
        )
        .bind(batchId).to("batchId")
        .run();

        log.debug("Saved {} CFG nodes (batchId={})", nodes.size(), batchId);
    }
}
