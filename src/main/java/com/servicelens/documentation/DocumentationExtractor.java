package com.servicelens.documentation;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.*;
import com.github.javaparser.javadoc.JavadocBlockTag;
import lombok.Data;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Extracts all documentation from Java source elements and produces
 * enriched content for embedding.
 *
 * WHY DOCUMENTATION MATTERS FOR RETRIEVAL:
 * ─────────────────────────────────────────
 * Code tells you WHAT. Documentation tells you WHY.
 *
 * The method body:
 *   retry(order, 3, Duration.ofSeconds(5));
 *
 * Tells you: "retries 3 times with 5 second intervals"
 *
 * The Javadoc:
 *   "Retries failed payments due to transient gateway errors.
 *    The 3-retry limit is set by business rule BR-PAY-042.
 *    Do NOT increase — gateway SLA requires < 3 retries."
 *
 * Tells you: WHY 3, what business rule governs this,
 *             and the constraint that can't be changed.
 *
 * If a developer asks "why does payment retry exactly 3 times?"
 * Without Javadoc embedding: similarity score ~0.55
 *   (code has "3" and "retry" but not "why")
 * With Javadoc embedding: similarity score ~0.93
 *   (Javadoc directly answers "why" questions)
 *
 * LAYERS WE EXTRACT:
 * ───────────────────
 * 1. Method Javadoc   → description, @param, @return, @throws
 * 2. Class Javadoc    → class purpose, ownership, constraints
 * 3. Inline comments  → // and /* inside method bodies
 * 4. TODO/FIXME       → known issues, tech debt markers
 */
@Component
public class DocumentationExtractor {

    // ═══════════════════════════════════════════════════════════════════════
    // METHOD DOCUMENTATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Extract full documentation for a method.
     * Returns a DocumentationResult containing all text found.
     */
    public MethodDocumentation extractMethodDoc(MethodDeclaration method) {
        MethodDocumentation doc = new MethodDocumentation();
        doc.setMethodName(method.getNameAsString());

        // ── Javadoc ───────────────────────────────────────────────────────
        method.getJavadoc().ifPresent(javadoc -> {
            // Main description
            String mainDesc = javadoc.getDescription().toText().trim();
            if (!mainDesc.isBlank()) {
                doc.setDescription(mainDesc);
            }

            // @param tags
            javadoc.getBlockTags().stream()
                    .filter(tag -> tag.getType() == JavadocBlockTag.Type.PARAM)
                    .forEach(tag -> {
                        String paramName = tag.getName().orElse("?");
                        String paramDesc = tag.getContent().toText().trim();
                        doc.getParamDocs().put(paramName, paramDesc);
                    });

            // @return tag
            javadoc.getBlockTags().stream()
                    .filter(tag -> tag.getType() == JavadocBlockTag.Type.RETURN)
                    .findFirst()
                    .ifPresent(tag -> doc.setReturnDoc(tag.getContent().toText().trim()));

            // @throws tags
            javadoc.getBlockTags().stream()
                    .filter(tag -> tag.getType() == JavadocBlockTag.Type.THROWS
                            || tag.getType() == JavadocBlockTag.Type.EXCEPTION)
                    .forEach(tag -> {
                        String exType = tag.getName().orElse("Exception");
                        String exDesc = tag.getContent().toText().trim();
                        doc.getThrowsDocs().put(exType, exDesc);
                    });

            // @see, @since, @deprecated, @author
            javadoc.getBlockTags().stream()
                    .filter(tag -> tag.getType() == JavadocBlockTag.Type.SEE
                            || tag.getType() == JavadocBlockTag.Type.SINCE
                            || tag.getType() == JavadocBlockTag.Type.DEPRECATED
                            || tag.getType() == JavadocBlockTag.Type.AUTHOR)
                    .forEach(tag -> doc.getOtherTags().add(
                            tag.getType().name() + ": " + tag.getContent().toText().trim()
                    ));
        });

        // ── Inline comments inside method body ────────────────────────────
        method.getAllContainedComments().forEach(comment -> {
            String text = comment.getContent().trim();

            // Skip auto-generated or trivially short comments
            if (text.length() < 10) return;

            // Categorize by type
            if (comment instanceof LineComment) {
                if (text.toUpperCase().startsWith("TODO")
                        || text.toUpperCase().startsWith("FIXME")
                        || text.toUpperCase().startsWith("HACK")
                        || text.toUpperCase().startsWith("NOTE")) {
                    doc.getTodosAndNotes().add(text);
                } else {
                    doc.getInlineComments().add(text);
                }
            } else if (comment instanceof BlockComment) {
                doc.getInlineComments().add(text);
            }
        });

        return doc;
    }

    /**
     * Extract class-level documentation.
     */
    public ClassDocumentation extractClassDoc(ClassOrInterfaceDeclaration classDecl) {
        ClassDocumentation doc = new ClassDocumentation();
        doc.setClassName(classDecl.getNameAsString());

        classDecl.getJavadoc().ifPresent(javadoc -> {
            doc.setDescription(javadoc.getDescription().toText().trim());

            javadoc.getBlockTags().forEach(tag -> {
                switch (tag.getType()) {
                    case AUTHOR ->
                            doc.setAuthor(tag.getContent().toText().trim());
                    case SINCE ->
                            doc.setSince(tag.getContent().toText().trim());
                    case DEPRECATED ->
                            doc.setDeprecationNote(tag.getContent().toText().trim());
                    default ->
                            doc.getOtherTags().add(
                                    tag.getType().name() + ": " + tag.getContent().toText().trim());
                }
            });
        });

        return doc;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ENRICHED CONTENT BUILDER
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build enriched content that prepends documentation to code.
     * This is what gets embedded — documentation + code together.
     *
     * Structure:
     *   [CLASS CONTEXT]
     *   [METHOD JAVADOC]
     *   [KNOWN ISSUES / NOTES]
     *   [METHOD SIGNATURE + BODY]
     *
     * The embedding model reads ALL of this and creates a vector
     * that captures both the semantic meaning of the docs AND the code.
     */
    public String buildEnrichedMethodContent(
            MethodDeclaration method,
            MethodDocumentation methodDoc,
            ClassDocumentation classDoc,
            String methodCode) {

        StringBuilder sb = new StringBuilder();

        // ── Class context (brief) ─────────────────────────────────────────
        if (classDoc != null && !classDoc.getDescription().isBlank()) {
            sb.append("CLASS CONTEXT: ")
                    .append(classDoc.getClassName()).append(" — ")
                    .append(truncate(classDoc.getDescription(), 150))
                    .append("\n\n");
        }

        // ── Method Javadoc ────────────────────────────────────────────────
        if (methodDoc.getDescription() != null && !methodDoc.getDescription().isBlank()) {
            sb.append("PURPOSE: ").append(methodDoc.getDescription()).append("\n");
        }

        // @param docs — extremely useful for retrieval
        // "what method takes an Order and returns a PaymentResult?"
        if (!methodDoc.getParamDocs().isEmpty()) {
            sb.append("PARAMETERS:\n");
            methodDoc.getParamDocs().forEach((name, desc) ->
                    sb.append("  ").append(name).append(": ").append(desc).append("\n"));
        }

        if (methodDoc.getReturnDoc() != null && !methodDoc.getReturnDoc().isBlank()) {
            sb.append("RETURNS: ").append(methodDoc.getReturnDoc()).append("\n");
        }

        // @throws — crucial for exception flow debugging
        if (!methodDoc.getThrowsDocs().isEmpty()) {
            sb.append("THROWS:\n");
            methodDoc.getThrowsDocs().forEach((type, desc) ->
                    sb.append("  ").append(type).append(": ").append(desc).append("\n"));
        }

        // Other tags
        methodDoc.getOtherTags().forEach(tag ->
                sb.append(tag).append("\n"));

        // ── TODOs and known issues ────────────────────────────────────────
        if (!methodDoc.getTodosAndNotes().isEmpty()) {
            sb.append("KNOWN ISSUES / NOTES:\n");
            methodDoc.getTodosAndNotes().forEach(note ->
                    sb.append("  ").append(note).append("\n"));
        }

        // ── Significant inline comments ───────────────────────────────────
        // Don't add all of them — only the most significant
        List<String> significantComments = methodDoc.getInlineComments().stream()
                .filter(c -> c.length() > 30)  // skip trivial ones
                .limit(5)                        // cap at 5
                .toList();

        if (!significantComments.isEmpty()) {
            sb.append("INLINE NOTES:\n");
            significantComments.forEach(c ->
                    sb.append("  // ").append(c).append("\n"));
        }

        // ── The actual code ───────────────────────────────────────────────
        if (!sb.isEmpty()) sb.append("\nCODE:\n");
        sb.append(methodCode);

        return sb.toString();
    }

    /**
     * Produce a standalone documentation Document for separate indexing.
     *
     * WHY SEPARATE:
     * When an agent asks "why does this method throw PaymentException?",
     * we want to retrieve the DOCUMENTATION chunk, not the code chunk.
     * The documentation chunk has higher similarity to "why" questions.
     * The code chunk has higher similarity to "where/how" questions.
     *
     * By having both, we always find the best answer regardless of
     * whether the question is about intent or implementation.
     */
    public Optional<Document> buildDocumentationChunk(
            MethodDocumentation methodDoc,
            String methodQualifiedName,
            String filePath,
            String serviceName) {

        // Only create a separate doc chunk if there's meaningful documentation
        if (methodDoc.getDescription() == null
                && methodDoc.getParamDocs().isEmpty()
                && methodDoc.getThrowsDocs().isEmpty()
                && methodDoc.getTodosAndNotes().isEmpty()) {
            return Optional.empty();
        }

        StringBuilder content = new StringBuilder();
        content.append("Documentation for: ").append(methodQualifiedName).append("\n\n");

        if (methodDoc.getDescription() != null) {
            content.append(methodDoc.getDescription()).append("\n\n");
        }

        methodDoc.getParamDocs().forEach((k, v) ->
                content.append("@param ").append(k).append(": ").append(v).append("\n"));

        if (methodDoc.getReturnDoc() != null) {
            content.append("@return ").append(methodDoc.getReturnDoc()).append("\n");
        }

        methodDoc.getThrowsDocs().forEach((k, v) ->
                content.append("@throws ").append(k).append(": ").append(v).append("\n"));

        methodDoc.getTodosAndNotes().forEach(note ->
                content.append("NOTE: ").append(note).append("\n"));

        methodDoc.getInlineComments().stream()
                .filter(c -> c.length() > 30)
                .limit(5)
                .forEach(c -> content.append("// ").append(c).append("\n"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunk_type", "DOCUMENTATION");
        metadata.put("element_name", methodQualifiedName.contains(".")
                ? methodQualifiedName.substring(methodQualifiedName.lastIndexOf('.') + 1)
                : methodQualifiedName);
        metadata.put("file_path", filePath);
        metadata.put("service_name", serviceName);
        metadata.put("is_doc_chunk", "true");
        metadata.put("has_throws_doc", String.valueOf(!methodDoc.getThrowsDocs().isEmpty()));
        metadata.put("has_todos", String.valueOf(!methodDoc.getTodosAndNotes().isEmpty()));

        return Optional.of(new Document(content.toString(), metadata));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    // ── Documentation result types ────────────────────────────────────────

    @Data
    public static class MethodDocumentation {
        private String methodName;
        private String description;
        private Map<String, String> paramDocs = new LinkedHashMap<>();
        private String returnDoc;
        private Map<String, String> throwsDocs = new LinkedHashMap<>();
        private List<String> otherTags = new ArrayList<>();
        private List<String> inlineComments = new ArrayList<>();
        private List<String> todosAndNotes = new ArrayList<>();

        public boolean hasAnyDoc() {
            return (description != null && !description.isBlank())
                    || !paramDocs.isEmpty()
                    || !throwsDocs.isEmpty()
                    || !todosAndNotes.isEmpty();
        }
    }

    @Data
    public static class ClassDocumentation {
        private String className;
        private String description = "";
        private String author;
        private String since;
        private String deprecationNote;
        private List<String> otherTags = new ArrayList<>();
    }
}