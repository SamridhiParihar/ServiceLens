package com.servicelens.chunking;

import org.springframework.ai.document.Document;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable value object representing a discrete unit of parsed source content
 * that is ready to be embedded and stored in the vector store.
 *
 * <p>A {@code CodeChunk} captures a logically cohesive segment of a file — for
 * example, a single Java method, a YAML configuration group, a SQL statement, or
 * a Markdown documentation section. Each chunk carries enough metadata to allow
 * the retrieval layer to filter by service, file type, and chunk type without
 * needing to re-read the source file.</p>
 *
 * <p>The {@link #toDocument()} method converts a chunk into a Spring AI
 * {@link Document} whose text content is enriched with contextual preamble
 * (service name, file path, chunk type, element name) to improve embedding
 * quality and retrieval accuracy.</p>
 *
 * @param content        the raw source text of this chunk
 * @param filePath       absolute path of the source file this chunk originated from
 * @param elementName    human-readable identifier for the element (method name,
 *                       endpoint path, SQL table name, config key, etc.)
 * @param startLine      1-based line number where this chunk begins in the source file
 * @param endLine        1-based line number where this chunk ends in the source file
 * @param language       lowercase language identifier (e.g. {@code "java"}, {@code "yaml"})
 * @param fileType       uppercase file-type label stored as metadata
 *                       (e.g. {@code "JAVA"}, {@code "YAML"}, {@code "SQL"})
 * @param chunkType      semantic classification of the chunk's role (see {@link ChunkType})
 * @param serviceName    the logical service name this chunk belongs to
 * @param extraMetadata  optional additional metadata key-value pairs such as Spring
 *                       annotation names, HTTP method, or config file name; may be null
 */
public record CodeChunk(
        String content,
        String filePath,
        String elementName,
        int startLine,
        int endLine,
        String language,
        String fileType,
        ChunkType chunkType,
        String serviceName,
        Map<String, String> extraMetadata
) {

    /**
     * Classifies the semantic role of a {@link CodeChunk} within a service codebase.
     *
     * <p>The chunk type is stored as metadata and used by the retrieval layer
     * to apply type-specific filters and similarity-score boosting.</p>
     */
    public enum ChunkType {

        /** Production source code (classes, methods, functions). */
        CODE,

        /** Unit or integration test code. */
        TEST,

        /** Configuration files such as YAML or properties files. */
        CONFIG,

        /** Database schema definitions (DDL, migrations). */
        SCHEMA,

        /** OpenAPI / Swagger specification endpoints. */
        API_SPEC,

        /** General documentation such as README or architecture notes. */
        DOCUMENTATION,

        /** Domain-specific business rules and policies from context documents. */
        BUSINESS_CONTEXT
    }

    /**
     * Convert this chunk into a Spring AI {@link Document} suitable for
     * embedding and storage in the vector store.
     *
     * <p>All chunk fields are mapped to flat metadata entries. The document's
     * text content is built by {@link #buildEnrichedContent()}, which prepends
     * contextual labels to the raw source text to improve embedding quality.</p>
     *
     * @return a {@link Document} containing enriched text content and a flat
     *         metadata map with all chunk fields
     */
    public Document toDocument() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_path", filePath);
        metadata.put("element_name", elementName);
        metadata.put("start_line", startLine);
        metadata.put("end_line", endLine);
        metadata.put("language", language);
        metadata.put("file_type", fileType);
        metadata.put("chunk_type", chunkType.name());
        metadata.put("service_name", serviceName);

        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }

        String enrichedContent = buildEnrichedContent();

        return new Document(enrichedContent, metadata);
    }

    /**
     * Build the enriched text content that will be embedded and stored in the vector store.
     *
     * <p>Prepending structured metadata labels (service, file, type, element name) to
     * the raw source text allows the embedding model to understand context such as
     * "This is a Java method named X in file Y" rather than receiving raw code alone.
     * This significantly improves retrieval accuracy for domain-specific queries.</p>
     *
     * <p>Any extra metadata key-value pairs are also included before the raw content.</p>
     *
     * @return the full enriched string to be used as the document text for embedding
     */
    private String buildEnrichedContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("Service: ").append(serviceName).append("\n");
        sb.append("File: ").append(filePath).append("\n");
        sb.append("Type: ").append(chunkType.name()).append("\n");

        if (elementName != null && !elementName.isBlank()) {
            sb.append("Element: ").append(elementName).append("\n");
        }

        if (extraMetadata != null) {
            extraMetadata.forEach((k, v) ->
                    sb.append(k).append(": ").append(v).append("\n")
            );
        }

        sb.append("\n").append(content);
        return sb.toString();
    }
}
