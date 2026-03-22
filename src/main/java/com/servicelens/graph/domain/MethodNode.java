package com.servicelens.graph.domain;

import lombok.Data;
import org.springframework.data.annotation.Transient;
import org.springframework.data.neo4j.core.schema.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Neo4j graph node representing a single Java method or constructor.
 *
 * <p>Each {@code MethodNode} corresponds to one method in the analysed codebase.
 * Beyond basic identity (qualified name, class, package), it carries:
 * <ul>
 *   <li><b>Source metadata</b> – line range, parameter signature, return type, source content.</li>
 *   <li><b>Spring enrichment</b> – whether the method is an HTTP endpoint, transactional,
 *       scheduled, or an event handler, with associated metadata extracted from annotations.</li>
 *   <li><b>CFG/DFG summary</b> – cyclomatic complexity, CFG node count, and a data-flow
 *       summary derived from the {@link com.servicelens.dfg.MethodDataFlow}.</li>
 *   <li><b>Documentation</b> – one-line Javadoc summary embedded so graph queries can
 *       surface human-readable descriptions without vector lookups.</li>
 *   <li><b>Relationships</b> – outgoing {@code CALLS} edges to called methods and
 *       an optional {@code OVERRIDES} edge to the parent-class method.</li>
 * </ul>
 *
 * <p>Stored in Neo4j under the label {@code Method}.  The primary key is
 * {@link #qualifiedName}, which is unique across the entire graph.
 *
 * @see ClassNode
 * @see MethodCallRelationship
 * @see com.servicelens.graph.repository.MethodNodeRepository
 */
@Node("Method")
@Data
public class MethodNode {

    /**
     * Fully-qualified method name including parameter types,
     * e.g. {@code com.example.PaymentService.processPayment(Order,String)}.
     * This is the Neo4j node ID and must be globally unique.
     */
    @Id
    private String qualifiedName;

    /** Simple method name without class prefix, e.g. {@code processPayment}. */
    private String simpleName;

    /** Simple name of the declaring class, e.g. {@code PaymentService}. */
    private String className;

    /** Package of the declaring class, e.g. {@code com.example.payment}. */
    private String packageName;

    /** Absolute or relative path to the source file containing this method. */
    private String filePath;

    /** Logical service name assigned during ingestion (matches repository root name). */
    private String serviceName;

    /** Declared return type, e.g. {@code void}, {@code Order}, {@code List<String>}. */
    private String returnType;

    /**
     * Parenthesised parameter type list, e.g. {@code (Order, String)}.
     * Used for display and disambiguation between overloaded methods.
     */
    private String parameterSignature;

    /** 1-based line number where the method declaration begins. */
    private int startLine;

    /** 1-based line number of the closing brace. */
    private int endLine;

    /** Full source text of the method body (used for code chunk embedding). */
    private String content;

    /** All annotation simple names present on the method, e.g. {@code ["Override","Transactional"]}. */
    private List<String> annotations = new ArrayList<>();

    // ── Spring enrichment ────────────────────────────────────────────────────

    /** {@code true} if the method is annotated with an HTTP mapping annotation. */
    private boolean isEndpoint;

    /** HTTP verb extracted from the mapping annotation, e.g. {@code GET}, {@code POST}. */
    private String httpMethod;

    /** URL path extracted from the mapping annotation, e.g. {@code /api/payments/{id}}. */
    private String endpointPath;

    /** {@code true} if the method carries {@code @Transactional}. */
    private boolean isTransactional;

    /** {@code true} if the method carries {@code @Scheduled}. */
    private boolean isScheduled;

    /** Cron expression or fixed-rate string from {@code @Scheduled}, if present. */
    private String scheduleExpression;

    /** {@code true} if the method carries {@code @EventListener} or similar. */
    private boolean isEventHandler;

    /** {@code true} if this method is declared inside a test class. */
    private boolean isTestMethod;

    // ── Documentation ────────────────────────────────────────────────────────

    /**
     * First sentence of the method's Javadoc comment, extracted during ingestion.
     * Empty string if no Javadoc is present.
     */
    private String javadocSummary;

    // ── CFG / complexity metrics ─────────────────────────────────────────────

    /**
     * Number of CFG nodes generated for this method.
     * Gives a rough indication of method size at the block level.
     */
    private int cfgNodeCount;

    /**
     * Cyclomatic complexity: number of linearly independent paths through the method.
     * Computed as {@code (branch count) + 1}.
     * Values above 10 typically indicate a method worth simplifying.
     */
    private int cyclomaticComplexity;

    // ── DFG summary ──────────────────────────────────────────────────────────

    /**
     * Serialisable summary of the Data-Flow Graph for this method.
     * Contains counts of definitions and uses per variable, keyed by metric name.
     * Produced by {@link com.servicelens.dfg.MethodDataFlow#toSummaryMap()}.
     *
     * <p>Marked {@code @Transient} because Neo4j only supports primitive types and
     * arrays of primitives as node properties; the nested {@code Map<String,Object>}
     * structure would cause a {@code Neo.ClientError.Statement.TypeError} on every
     * {@code save()} call.  The DFG data is available in-memory during ingestion
     * and can be queried from the {@code MethodDataFlow} layer instead.</p>
     */
    @Transient
    private Map<String, Object> dataFlowSummary;

    /**
     * Variables or method calls that appear to come from outside this method's
     * own definitions (i.e. fields, injected services, static utilities).
     * Useful for impact analysis: changing an external dependency may affect
     * every method that lists it here.
     */
    private List<String> externalReferences = new ArrayList<>();

    // ── Relationships ────────────────────────────────────────────────────────

    /**
     * Outgoing {@code CALLS} relationships to methods directly invoked
     * inside this method body. Each edge carries the call-site line number.
     */
    @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
    private List<MethodCallRelationship> calls = new ArrayList<>();

    /**
     * Method in a superclass or interface that this method overrides or implements.
     * {@code null} if the method does not override anything detectable.
     */
    @Relationship(type = "OVERRIDES", direction = Relationship.Direction.OUTGOING)
    private MethodNode overrides;
}
