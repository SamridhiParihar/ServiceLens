package com.servicelens.dfg;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Represents a single variable <em>use</em> (USE) within a method's data-flow graph.
 *
 * <p>A use site is any point in the code where the current value of a variable
 * is read.  This class records:
 * <ul>
 *   <li>The variable's name.</li>
 *   <li>How the variable is being read ({@link UseType}) – passed as an argument,
 *       tested in a condition, returned, used on the right of an assignment,
 *       thrown, or used as an array index.</li>
 *   <li>The full expression context in which the use appears, for readability.</li>
 *   <li>The source-file line number for traceability.</li>
 * </ul>
 *
 * <p>Together with {@link DataFlowNode} (DEF sites), {@code DataFlowUse}
 * instances stored in a {@link MethodDataFlow} form a lightweight intra-procedural
 * DEF-USE chain that is used for:
 * <ul>
 *   <li>Detecting external references (variables used but never defined locally,
 *       indicating dependency on fields or injected collaborators).</li>
 *   <li>Producing a human-readable data-flow summary embedded in
 *       {@link com.servicelens.graph.domain.MethodNode}.</li>
 * </ul>
 *
 * @see DataFlowNode
 * @see MethodDataFlow
 * @see DfgBuilder
 */
@Data
@AllArgsConstructor
public class DataFlowUse {

    /** Name of the variable being read, e.g. {@code order}. */
    String variableName;

    /** How the variable's value is being consumed at this use site. */
    UseType useType;

    /**
     * The full expression context surrounding the use, e.g.
     * {@code orderService.save(order)} for a {@link UseType#METHOD_ARG} use.
     * Aids in understanding the role of the variable at a glance.
     */
    String expressionContext;

    /** 1-based source-file line number where this use occurs. */
    int line;

    /**
     * Classifies how a variable's value is consumed at a use site.
     *
     * <p>These categories map to the major syntactic positions in which
     * a variable name can appear as an rvalue in Java:
     * <ul>
     *   <li>{@link #METHOD_ARG}      – passed as an argument in a method invocation.</li>
     *   <li>{@link #CONDITION}       – evaluated inside an {@code if}, {@code while},
     *       or {@code for} condition.</li>
     *   <li>{@link #RETURN_VALUE}    – returned from the method via a {@code return} statement.</li>
     *   <li>{@link #ASSIGNMENT_RHS}  – appears on the right-hand side of an assignment.</li>
     *   <li>{@link #THROWN_VALUE}    – used in a {@code throw} statement.</li>
     *   <li>{@link #ARRAY_INDEX}     – used as the index in an array-access expression.</li>
     * </ul>
     */
    public enum UseType {
        /** Passed as an argument to a method call. */
        METHOD_ARG,
        /** Used inside a conditional expression. */
        CONDITION,
        /** Returned from the current method. */
        RETURN_VALUE,
        /** Appears on the right-hand side of an assignment. */
        ASSIGNMENT_RHS,
        /** Supplied to a {@code throw} statement. */
        THROWN_VALUE,
        /** Used as an array subscript. */
        ARRAY_INDEX
    }
}
