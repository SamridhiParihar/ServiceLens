package com.servicelens.graph.domain;

/**
 * Enumeration of all node types that can appear in the ServiceLens knowledge graph.
 *
 * <p>Each constant corresponds to a distinct kind of Java source-code element or
 * repository structure artifact. Node types are stored as properties on graph nodes
 * and used by queries and analysis logic to discriminate between classes, interfaces,
 * methods, and other constructs.</p>
 */
public enum NodeType {

    /** The root of a repository or multi-module project. */
    PROJECT,

    /** A Java package (e.g. {@code com.example.payment}). */
    PACKAGE,

    /** A physical source file on disk. */
    FILE,

    /** A concrete Java class ({@code class} declaration). */
    CLASS,

    /** A Java interface ({@code interface} declaration). */
    INTERFACE,

    /** A Java enum type ({@code enum} declaration). */
    ENUM,

    /** A Java record type ({@code record} declaration, Java 16+). */
    RECORD,

    /** A Java annotation type ({@code @interface} declaration). */
    ANNOTATION_TYPE,

    /** A regular instance or static method. */
    METHOD,

    /** A constructor ({@code ClassName(...)}). */
    CONSTRUCTOR
}
