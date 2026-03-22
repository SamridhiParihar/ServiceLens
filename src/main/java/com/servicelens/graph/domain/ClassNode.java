package com.servicelens.graph.domain;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j graph node representing a Java type declaration (class, interface,
 * enum, record, or annotation type).
 *
 * <p>Every compilable Java type in the analysed codebase produces one
 * {@code ClassNode}.  The node captures identity, structural properties,
 * Spring stereotype classification, and a Javadoc summary so graph queries
 * can return human-readable context without a separate vector lookup.
 *
 * <p>Relationships modelled:
 * <ul>
 *   <li>{@code INHERITS}   – single superclass (absent for {@code Object}).</li>
 *   <li>{@code IMPLEMENTS} – zero or more implemented interfaces.</li>
 *   <li>{@code DEFINES}    – all methods and constructors declared in this type.</li>
 *   <li>{@code DEPENDS_ON} – types referenced as field types or constructor parameters
 *       (injected collaborators), enabling dependency and impact analysis.</li>
 * </ul>
 *
 * <p>Stored in Neo4j under the label {@code Class}.  The primary key is
 * {@link #qualifiedName}, which is unique across the entire graph.
 *
 * @see MethodNode
 * @see com.servicelens.graph.repository.ClassNodeRepository
 */
@Node("Class")
@Data
public class ClassNode {

    /**
     * Fully-qualified class name, e.g. {@code com.example.payment.PaymentService}.
     * Used as the Neo4j node ID – must be globally unique.
     */
    @Id
    private String qualifiedName;

    /** Unqualified class name, e.g. {@code PaymentService}. */
    private String simpleName;

    /** Package the type belongs to, e.g. {@code com.example.payment}. */
    private String packageName;

    /** Absolute or relative path to the source file declaring this type. */
    private String filePath;

    /** Logical service name assigned during ingestion (matches repository root name). */
    private String serviceName;

    /** {@code true} if the type is declared {@code abstract}. */
    private boolean isAbstract;

    /** {@code true} if the type has {@code public} top-level visibility. */
    private boolean isPublic;

    /**
     * {@code true} for interface declarations; {@code false} for class, enum,
     * record, and annotation types.
     *
     * <p>Kept for backward compatibility with existing Cypher queries.
     * Prefer {@link #nodeType} for new queries — it is the canonical discriminator.</p>
     */
    private boolean isInterface;

    /**
     * The exact kind of Java type this node represents.
     *
     * <p>This is the canonical discriminator for graph queries that need to filter
     * by type kind. For example, to find all enums in a service:
     * <pre>
     *   MATCH (c:Class {serviceName: $svc, nodeType: 'ENUM'}) RETURN c
     * </pre>
     * </p>
     *
     * <p>Set during ingestion by {@code JavaFileProcessor} — every code path
     * that constructs a {@code ClassNode} must set this field. Valid values:
     * {@code CLASS}, {@code INTERFACE}, {@code ENUM}, {@code RECORD},
     * {@code ANNOTATION_TYPE}.</p>
     *
     * @see NodeType
     */
    private NodeType nodeType;

    /** All annotation simple names present on the type declaration. */
    private List<String> annotations = new ArrayList<>();

    /**
     * Spring stereotype detected on the type, or {@code null} if none.
     * Possible values: {@code @Service}, {@code @Controller}, {@code @RestController},
     * {@code @Repository}, {@code @Configuration}, {@code @Component}.
     */
    private String springStereotype;

    /**
     * First sentence of the class-level Javadoc comment, extracted during ingestion.
     * Empty string if no Javadoc is present.
     */
    private String javadocSummary;

    // ── Relationships ────────────────────────────────────────────────────────

    /**
     * Direct superclass of this type.
     * {@code null} for {@code java.lang.Object} and interfaces
     * that have no explicit superinterface.
     */
    @Relationship(type = "INHERITS", direction = Relationship.Direction.OUTGOING)
    private ClassNode superClass;

    /**
     * Interfaces explicitly implemented (for classes) or extended (for interfaces).
     */
    @Relationship(type = "IMPLEMENTS", direction = Relationship.Direction.OUTGOING)
    private List<ClassNode> implementedInterfaces = new ArrayList<>();

    /**
     * All methods and constructors declared directly within this type.
     */
    @Relationship(type = "DEFINES", direction = Relationship.Direction.OUTGOING)
    private List<MethodNode> methods = new ArrayList<>();

    /**
     * Types on which this type depends – typically field types of injected
     * collaborators and constructor parameter types.
     * Used for dependency and impact analysis queries.
     */
    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.OUTGOING)
    private List<ClassNode> dependencies = new ArrayList<>();
}
