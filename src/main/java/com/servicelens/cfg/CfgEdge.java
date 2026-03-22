package com.servicelens.cfg;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

/**
 * A directed edge in the Control Flow Graph.
 *
 * Each edge carries a label describing why execution flows
 * from the source node to the target node.
 *
 * Edge types:
 *   UNCONDITIONAL  → always taken (no branch)
 *   TRUE           → taken when condition is true
 *   FALSE          → taken when condition is false
 *   EXCEPTION      → taken when an exception of exceptionType is thrown
 *   LOOP_BACK      → loop iteration edge
 *   FALL_THROUGH   → switch fall-through
 */
@RelationshipProperties
@Data
public class CfgEdge {

    @Id
    @GeneratedValue
    private Long id;

    /** Label describing this edge */
    private EdgeType edgeType;

    /** For EXCEPTION edges: the exception class name */
    private String exceptionType;

    /** For conditional edges: the condition expression */
    private String conditionText;

    @TargetNode
    private CfgNode target;

    public enum EdgeType {
        UNCONDITIONAL,
        TRUE,
        FALSE,
        EXCEPTION,
        LOOP_BACK,
        FALL_THROUGH
    }
}