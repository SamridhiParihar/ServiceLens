package com.servicelens.graph;

import com.servicelens.cfg.CfgEdge;
import com.servicelens.cfg.CfgNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CfgSaver}.
 *
 * <p>Verifies the flat two-pass Cypher strategy:
 * <ol>
 *   <li>Pass 1 — one {@code Neo4jClient.query()} call per CFG node</li>
 *   <li>Pass 2 — one {@code Neo4jClient.query()} call per outgoing CFG edge</li>
 *   <li>Cleanup — one final {@code query()} to strip temp properties</li>
 * </ol>
 * Total expected queries = nodes + edges + 1 (cleanup).
 *
 * <p>All Neo4j interaction is replaced by Mockito mocks; no live database is needed.</p>
 */
@DisplayName("CfgSaver")
@ExtendWith(MockitoExtension.class)
class CfgSaverTest {

    @Mock
    private Neo4jClient neo4jClient;

    // Fluent chain mocks
    @Mock
    private Neo4jClient.UnboundRunnableSpec unboundSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private Neo4jClient.OngoingBindSpec ongoingBindSpec;

    private CfgSaver cfgSaver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cfgSaver = new CfgSaver(neo4jClient);

        // Wire the fluent chain: query() → bind() → to() → (loops back for next bind).
        // Lenient because the empty-list test never calls any Neo4jClient method.
        lenient().when(neo4jClient.query(anyString())).thenReturn(unboundSpec);
        lenient().when(unboundSpec.bind(any())).thenReturn(ongoingBindSpec);
        lenient().when(ongoingBindSpec.to(anyString())).thenReturn(unboundSpec);
        lenient().when(unboundSpec.run()).thenReturn(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Empty list
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty node list")
    class EmptyListTests {

        @Test
        @DisplayName("Issues no queries when node list is empty")
        void noQueriesForEmptyList() {
            cfgSaver.save(List.of());

            verify(neo4jClient, never()).query(anyString());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nodes without edges
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Nodes with no outgoing edges")
    class NoEdgeTests {

        @Test
        @DisplayName("Issues 1 node query + 1 cleanup query for a single node")
        void singleNode_twoQueries() {
            CfgNode entry = cfgNode(CfgNode.CfgNodeType.ENTRY);

            cfgSaver.save(List.of(entry));

            // Pass 1: 1 node MERGE  +  Cleanup: 1 REMOVE query
            verify(neo4jClient, times(2)).query(anyString());
        }

        @Test
        @DisplayName("Issues N node queries + 1 cleanup query for N nodes")
        void multipleNodes_nPlusOneQueries() {
            CfgNode entry  = cfgNode(CfgNode.CfgNodeType.ENTRY);
            CfgNode stmt   = cfgNode(CfgNode.CfgNodeType.STATEMENT);
            CfgNode exit   = cfgNode(CfgNode.CfgNodeType.EXIT);

            cfgSaver.save(List.of(entry, stmt, exit));

            // Pass 1: 3 node MERGEs  +  Cleanup: 1 REMOVE query
            verify(neo4jClient, times(4)).query(anyString());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nodes with edges
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Nodes with outgoing edges")
    class WithEdgesTests {

        @Test
        @DisplayName("Issues node + edge + cleanup queries: total = nodes + edges + 1")
        void nodesAndEdges_correctQueryCount() {
            CfgNode entry = cfgNode(CfgNode.CfgNodeType.ENTRY);
            CfgNode cond  = cfgNode(CfgNode.CfgNodeType.CONDITION);
            CfgNode exit  = cfgNode(CfgNode.CfgNodeType.EXIT);

            // entry → cond (true), entry → exit (false)
            entry.setSuccessors(List.of(
                    edge(CfgEdge.EdgeType.TRUE,  cond),
                    edge(CfgEdge.EdgeType.FALSE, exit)
            ));

            cfgSaver.save(List.of(entry, cond, exit));

            // Pass 1: 3 node MERGEs
            // Pass 2: 2 edge CREATEs
            // Cleanup: 1 REMOVE
            // Total: 6
            verify(neo4jClient, times(6)).query(anyString());
        }

        @Test
        @DisplayName("Skips edge whose target node is not in the batch")
        void edgeToExternalNode_isSkipped() {
            CfgNode entry   = cfgNode(CfgNode.CfgNodeType.ENTRY);
            CfgNode outside = cfgNode(CfgNode.CfgNodeType.EXIT); // NOT in the save list

            entry.setSuccessors(List.of(edge(CfgEdge.EdgeType.UNCONDITIONAL, outside)));

            cfgSaver.save(List.of(entry)); // only entry in the list

            // Pass 1: 1 node MERGE  +  Cleanup: 1 REMOVE  (edge skipped)
            verify(neo4jClient, times(2)).query(anyString());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static CfgNode cfgNode(CfgNode.CfgNodeType type) {
        CfgNode node = new CfgNode();
        node.setNodeType(type);
        node.setMethodQualifiedName("com.example.SomeClass.someMethod");
        node.setServiceName("test-svc");
        node.setStartLine(1);
        node.setEndLine(5);
        return node;
    }

    private static CfgEdge edge(CfgEdge.EdgeType type, CfgNode target) {
        CfgEdge edge = new CfgEdge();
        edge.setEdgeType(type);
        edge.setTarget(target);
        return edge;
    }
}
