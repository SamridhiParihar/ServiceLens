package com.servicelens.dfg;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Represents a single variable <em>definition</em> (DEF) within a method's data-flow graph.
 *
 * <p>In data-flow analysis every point where a variable receives a value is a
 * definition site.  This class records:
 * <ul>
 *   <li>The variable's name and declared type.</li>
 *   <li>How the definition arose ({@link DefType}) – parameter, local declaration,
 *       plain assignment, loop variable, or caught exception.</li>
 *   <li>The source expression on the right-hand side of the definition.</li>
 *   <li>The source-file line number for traceability.</li>
 * </ul>
 *
 * <p>{@code DataFlowNode} instances are created by {@link DfgBuilder} and stored
 * in a {@link MethodDataFlow} per analysed method.  They are kept in-memory only;
 * they are not persisted to Neo4j individually.
 *
 * @see DataFlowUse
 * @see MethodDataFlow
 * @see DfgBuilder
 */
@Data
@AllArgsConstructor
public class DataFlowNode {

    /** Name of the variable being defined, e.g. {@code order}. */
    String variableName;

    /**
     * Declared type of the variable, e.g. {@code Order}, {@code List<String>}.
     * May be {@code null} for plain assignments where the type is already known
     * from an earlier declaration.
     */
    String variableType;

    /** How this variable received its value. */
    DefType defType;

    /**
     * The source expression that produced the value, i.e. the right-hand side
     * of the assignment, the initialiser, or the parameter name.
     * Used to understand data provenance during analysis.
     */
    String sourceExpression;

    /** 1-based source-file line number where this definition occurs. */
    int line;

    /**
     * Classifies the origin of a variable definition.
     *
     * <p>These categories map directly to the Java language constructs
     * that can introduce a new binding for a variable:
     * <ul>
     *   <li>{@link #PARAMETER}        – the variable is a formal method parameter.</li>
     *   <li>{@link #LOCAL_DECL}       – the variable is declared and initialised
     *       ({@code Type name = expr}).</li>
     *   <li>{@link #ASSIGNMENT}       – the variable already exists and is assigned
     *       a new value ({@code name = expr}).</li>
     *   <li>{@link #LOOP_VAR}         – the variable is the iteration variable in
     *       an enhanced for-loop ({@code for (Type v : collection)}).</li>
     *   <li>{@link #CAUGHT_EXCEPTION} – the variable is bound in a catch clause
     *       ({@code catch (ExceptionType e)}).</li>
     * </ul>
     */
    public enum DefType {
        /** Formal method parameter. */
        PARAMETER,
        /** Local variable declaration with an initialiser. */
        LOCAL_DECL,
        /** Assignment to an already-declared variable. */
        ASSIGNMENT,
        /** Enhanced for-loop iteration variable. */
        LOOP_VAR,
        /** Exception variable bound in a {@code catch} clause. */
        CAUGHT_EXCEPTION
    }
}
