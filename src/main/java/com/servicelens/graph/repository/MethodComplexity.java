package com.servicelens.graph.repository;

/**
 * Spring Data Neo4j projection for method cyclomatic-complexity queries.
 *
 * <p>This projection is used by {@link CfgNodeRepository#findMostComplexMethods}
 * to return only the qualified method name and its branch count, avoiding
 * the overhead of fully hydrating a {@link com.servicelens.graph.domain.MethodNode}
 * entity when only complexity ranking data is needed.</p>
 *
 * <p>The {@code branches} value is the number of {@code CONDITION} and
 * {@code LOOP_HEADER} CFG nodes for the method, which approximates
 * {@code cyclomaticComplexity - 1}.</p>
 */
public interface MethodComplexity {

    /**
     * The fully qualified name of the method (e.g., {@code com.example.Service.processOrder}).
     *
     * @return fully qualified method name
     */
    String getMethod();

    /**
     * The number of branching CFG nodes (CONDITION + LOOP_HEADER) for this method.
     *
     * <p>Cyclomatic complexity ≈ {@code branches + 1}.</p>
     *
     * @return branch node count
     */
    int getBranches();
}
