package com.servicelens.cfg;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the Control Flow Graph.
 *
 * Every method is decomposed into basic blocks — sequences of statements
 * with no branching. Each block is one CfgNode. Edges between nodes
 * represent possible execution paths.
 *
 * Example for:
 *   if (x > 0) { doA(); } else { doB(); }
 *
 *   ENTRY ──► [x > 0 check] ──true──►  [doA()] ──► EXIT
 *                           └─false──► [doB()] ──► EXIT
 */
@Node("CfgNode")
@Data
public class CfgNode {

    @Id
    @GeneratedValue
    private Long id;

    /** Owning method qualified name */
    private String methodQualifiedName;

    /** Service this belongs to */
    private String serviceName;

    /** Type of this block */
    private CfgNodeType nodeType;

    /** The code/expression in this block */
    private String code;

    /** Start line in source file */
    private int startLine;

    /** End line in source file */
    private int endLine;

    /** For CONDITION nodes: the condition expression text */
    private String condition;

    /** For METHOD_CALL nodes: the called method name */
    private String calledMethod;

    /** For EXCEPTION_HANDLER nodes: exception type caught */
    private String exceptionType;

    /**
     * Outgoing control flow edges.
     * Each edge has a condition label (true/false/exception type/unconditional).
     */
    @Relationship(type = "CFG_EDGE", direction = Relationship.Direction.OUTGOING)
    private List<CfgEdge> successors = new ArrayList<>();

    public enum CfgNodeType {
        ENTRY,              // method entry point
        EXIT,               // method exit (return/end)
        STATEMENT,          // regular statement
        CONDITION,          // if/switch condition
        LOOP_HEADER,        // while/for condition
        LOOP_BACK,          // loop back edge target
        METHOD_CALL,        // call to another method
        EXCEPTION_THROW,    // throw statement
        EXCEPTION_HANDLER,  // catch block entry
        FINALLY_BLOCK       // finally block entry
    }
}