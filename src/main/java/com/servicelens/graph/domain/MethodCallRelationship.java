package com.servicelens.graph.domain;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

/**
 * Neo4j relationship entity representing a method call from one {@link MethodNode}
 * to another within the knowledge graph.
 *
 * <p>This class models a {@code CALLS} relationship between two method nodes and
 * carries properties that describe the call site. Because it is annotated with
 * {@link RelationshipProperties}, it can store additional metadata beyond the
 * mere existence of an edge — specifically the source-code line number where the
 * call occurs and the semantic type of the call.</p>
 *
 * <p>Instances of this class are embedded inside {@link MethodNode} as elements of
 * its outgoing {@code CALLS} relationship collection. They are persisted and
 * retrieved automatically by Spring Data Neo4j.</p>
 */
@RelationshipProperties
@Data
public class MethodCallRelationship {

    /**
     * Internal Neo4j relationship identifier, generated automatically.
     */
    @Id
    @GeneratedValue
    private Long id;

    /**
     * The 1-based line number in the source file where this call site appears.
     * Used for precise source-location reporting in analysis results.
     */
    private int callLine;

    /**
     * The semantic type of the method call.
     * Known values: {@code "DIRECT"} (normal virtual/interface dispatch),
     * {@code "SUPER"} (explicit {@code super.method()} call),
     * {@code "INTERFACE"} (call through an interface reference).
     */
    private String callType;

    /**
     * The method node that is being called (the callee end of the relationship).
     */
    @TargetNode
    private MethodNode callee;
}
