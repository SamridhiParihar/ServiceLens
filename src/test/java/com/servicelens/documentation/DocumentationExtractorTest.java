package com.servicelens.documentation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DocumentationExtractor}.
 *
 * <p>Uses {@link StaticJavaParser} to build real AST nodes from inline source
 * strings — no I/O, no Spring context, no mocks required. The tests verify
 * Javadoc tag extraction, enriched content preamble structure, and
 * documentation chunk production.</p>
 */
@DisplayName("DocumentationExtractor")
class DocumentationExtractorTest {

    private DocumentationExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new DocumentationExtractor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // extractMethodDoc()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractMethodDoc()")
    class ExtractMethodDocTests {

        @Test
        @DisplayName("Extracts main Javadoc description")
        void extractsDescription() {
            MethodDeclaration method = parseMethod("""
                /**
                 * Processes a payment for the given order.
                 */
                public void processPayment(String orderId) {}
                """);

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.getDescription())
                    .isEqualTo("Processes a payment for the given order.");
        }

        @Test
        @DisplayName("Extracts @param tags into the param map")
        void extractsParamTags() {
            MethodDeclaration method = parseMethod("""
                /**
                 * Creates an order.
                 * @param orderId  the unique order identifier
                 * @param amount   the payment amount in cents
                 */
                public void createOrder(String orderId, int amount) {}
                """);

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.getParamDocs())
                    .containsKey("orderId")
                    .containsKey("amount");
            assertThat(doc.getParamDocs().get("orderId"))
                    .contains("unique order identifier");
        }

        @Test
        @DisplayName("Extracts @return tag")
        void extractsReturnTag() {
            MethodDeclaration method = parseMethod("""
                /**
                 * Calculates the discount.
                 * @return discount amount in cents, never negative
                 */
                public int calculateDiscount(int price) { return 0; }
                """);

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.getReturnDoc()).contains("discount amount in cents");
        }

        @Test
        @DisplayName("Extracts @throws tags into the throws map")
        void extractsThrowsTags() {
            MethodDeclaration method = parseMethod("""
                /**
                 * Validates the order.
                 * @throws IllegalArgumentException if orderId is blank
                 * @throws RuntimeException if payment gateway is unreachable
                 */
                public void validate(String orderId) {}
                """);

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.getThrowsDocs())
                    .containsKey("IllegalArgumentException")
                    .containsKey("RuntimeException");
        }

        @Test
        @DisplayName("Extracts TODO / FIXME inline comments into todosAndNotes")
        void extractsTodosAndNotes() {
            MethodDeclaration method = parseMethod("""
                public void pay(String id) {
                    // TODO: add retry logic here for transient failures
                    process(id);
                }
                """);

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.getTodosAndNotes())
                    .anyMatch(n -> n.contains("retry logic"));
        }

        @Test
        @DisplayName("Returns empty collections when method has no Javadoc")
        void returnsEmptyCollectionsForUndocumentedMethod() {
            MethodDeclaration method = parseMethod(
                    "public void undocumented() {}");

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.getDescription()).isNull();
            assertThat(doc.getParamDocs()).isEmpty();
            assertThat(doc.getThrowsDocs()).isEmpty();
            assertThat(doc.getTodosAndNotes()).isEmpty();
        }

        @Test
        @DisplayName("hasAnyDoc() returns true when description is present")
        void hasAnyDocTrueWhenDescriptionPresent() {
            MethodDeclaration method = parseMethod("""
                /** Does something useful. */
                public void doIt() {}
                """);

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.hasAnyDoc()).isTrue();
        }

        @Test
        @DisplayName("hasAnyDoc() returns false when no documentation at all")
        void hasAnyDocFalseWhenNoDoc() {
            MethodDeclaration method = parseMethod("public void empty() {}");

            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            assertThat(doc.hasAnyDoc()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // extractClassDoc()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractClassDoc()")
    class ExtractClassDocTests {

        @Test
        @DisplayName("Extracts class-level Javadoc description")
        void extractsClassDescription() {
            ClassOrInterfaceDeclaration classDecl = parseClass("""
                /**
                 * Handles all payment operations for the order flow.
                 */
                public class PaymentService {}
                """);

            DocumentationExtractor.ClassDocumentation doc =
                    extractor.extractClassDoc(classDecl);

            assertThat(doc.getDescription())
                    .contains("Handles all payment operations");
        }

        @Test
        @DisplayName("Extracts @author tag")
        void extractsAuthorTag() {
            ClassOrInterfaceDeclaration classDecl = parseClass("""
                /**
                 * Payment processor.
                 * @author Sam
                 */
                public class PaymentService {}
                """);

            DocumentationExtractor.ClassDocumentation doc =
                    extractor.extractClassDoc(classDecl);

            assertThat(doc.getAuthor()).isEqualTo("Sam");
        }

        @Test
        @DisplayName("Extracts @since tag")
        void extractsSinceTag() {
            ClassOrInterfaceDeclaration classDecl = parseClass("""
                /**
                 * Legacy processor.
                 * @since 1.0
                 */
                public class LegacyService {}
                """);

            DocumentationExtractor.ClassDocumentation doc =
                    extractor.extractClassDoc(classDecl);

            assertThat(doc.getSince()).isEqualTo("1.0");
        }

        @Test
        @DisplayName("Returns empty description when no Javadoc on class")
        void returnsEmptyDescriptionForUndocumentedClass() {
            ClassOrInterfaceDeclaration classDecl = parseClass(
                    "public class Plain {}");

            DocumentationExtractor.ClassDocumentation doc =
                    extractor.extractClassDoc(classDecl);

            assertThat(doc.getDescription()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildEnrichedMethodContent()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildEnrichedMethodContent()")
    class BuildEnrichedContentTests {

        @Test
        @DisplayName("Includes PURPOSE section when description is present")
        void includesPurposeSection() {
            MethodDeclaration method = parseMethod("""
                /** Processes refunds for cancelled orders. */
                public void refund(String orderId) {}
                """);
            DocumentationExtractor.MethodDocumentation methodDoc =
                    extractor.extractMethodDoc(method);

            String enriched = extractor.buildEnrichedMethodContent(
                    method, methodDoc, null, "public void refund(String orderId) {}");

            assertThat(enriched).contains("PURPOSE:");
            assertThat(enriched).contains("Processes refunds");
        }

        @Test
        @DisplayName("Includes PARAMETERS section when @param tags are present")
        void includesParametersSection() {
            MethodDeclaration method = parseMethod("""
                /**
                 * @param orderId the order to refund
                 */
                public void refund(String orderId) {}
                """);
            DocumentationExtractor.MethodDocumentation methodDoc =
                    extractor.extractMethodDoc(method);

            String enriched = extractor.buildEnrichedMethodContent(
                    method, methodDoc, null, "public void refund(String orderId) {}");

            assertThat(enriched).contains("PARAMETERS:");
            assertThat(enriched).contains("orderId");
        }

        @Test
        @DisplayName("Includes THROWS section when @throws tags are present")
        void includesThrowsSection() {
            MethodDeclaration method = parseMethod("""
                /**
                 * @throws IllegalArgumentException if id is null
                 */
                public void refund(String id) {}
                """);
            DocumentationExtractor.MethodDocumentation methodDoc =
                    extractor.extractMethodDoc(method);

            String enriched = extractor.buildEnrichedMethodContent(
                    method, methodDoc, null, "public void refund(String id) {}");

            assertThat(enriched).contains("THROWS:");
            assertThat(enriched).contains("IllegalArgumentException");
        }

        @Test
        @DisplayName("Includes CLASS CONTEXT section when classDoc description is present")
        void includesClassContextSection() {
            ClassOrInterfaceDeclaration classDecl = parseClass("""
                /**
                 * Handles all payment operations.
                 */
                public class PaymentService {}
                """);
            DocumentationExtractor.ClassDocumentation classDoc =
                    extractor.extractClassDoc(classDecl);

            MethodDeclaration method = parseMethod("public void pay() {}");
            DocumentationExtractor.MethodDocumentation methodDoc =
                    extractor.extractMethodDoc(method);

            String enriched = extractor.buildEnrichedMethodContent(
                    method, methodDoc, classDoc, "public void pay() {}");

            assertThat(enriched).contains("CLASS CONTEXT:");
            assertThat(enriched).contains("PaymentService");
        }

        @Test
        @DisplayName("Always includes the method CODE at the end")
        void alwaysIncludesCode() {
            MethodDeclaration method = parseMethod("public void run() {}");
            DocumentationExtractor.MethodDocumentation methodDoc =
                    extractor.extractMethodDoc(method);

            String code = "public void run() { System.out.println(); }";
            String enriched = extractor.buildEnrichedMethodContent(
                    method, methodDoc, null, code);

            assertThat(enriched).endsWith(code);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildDocumentationChunk()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildDocumentationChunk()")
    class BuildDocumentationChunkTests {

        @Test
        @DisplayName("Returns Optional.empty() when method has no meaningful documentation")
        void returnsEmptyWhenNoDoc() {
            MethodDeclaration method = parseMethod("public void bare() {}");
            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            Optional<Document> result = extractor.buildDocumentationChunk(
                    doc, "com.example.Service.bare", "/Service.java", "svc");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Returns a Document when description is present")
        void returnsDocumentWhenDescriptionPresent() {
            MethodDeclaration method = parseMethod("""
                /** Sends payment confirmation email. */
                public void sendConfirmation(String email) {}
                """);
            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            Optional<Document> result = extractor.buildDocumentationChunk(
                    doc,
                    "com.example.NotificationService.sendConfirmation",
                    "/NotificationService.java",
                    "notification-svc");

            assertThat(result).isPresent();
            assertThat(result.get().getContent())
                    .contains("sendConfirmation")
                    .contains("confirmation email");
        }

        @Test
        @DisplayName("Produced document has chunk_type = DOCUMENTATION")
        void producedDocumentHasDocumentationType() {
            MethodDeclaration method = parseMethod("""
                /** Does something. */
                public void doIt() {}
                """);
            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            Document chunk = extractor.buildDocumentationChunk(
                    doc, "com.example.Svc.doIt", "/Svc.java", "svc")
                    .orElseThrow();

            assertThat(chunk.getMetadata().get("chunk_type")).isEqualTo("DOCUMENTATION");
        }

        @Test
        @DisplayName("has_throws_doc metadata is true when @throws is present")
        void hasThrowsDocMetadataSetWhenThrowsPresent() {
            MethodDeclaration method = parseMethod("""
                /**
                 * Validates input.
                 * @throws IllegalArgumentException if blank
                 */
                public void validate(String s) {}
                """);
            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            Document chunk = extractor.buildDocumentationChunk(
                    doc, "com.example.Svc.validate", "/Svc.java", "svc")
                    .orElseThrow();

            assertThat(chunk.getMetadata().get("has_throws_doc")).isEqualTo("true");
        }

        @Test
        @DisplayName("is_doc_chunk metadata is always true")
        void isDocChunkAlwaysTrue() {
            MethodDeclaration method = parseMethod("""
                /** Some doc. */
                public void thing() {}
                """);
            DocumentationExtractor.MethodDocumentation doc =
                    extractor.extractMethodDoc(method);

            Document chunk = extractor.buildDocumentationChunk(
                    doc, "com.example.Svc.thing", "/Svc.java", "svc")
                    .orElseThrow();

            assertThat(chunk.getMetadata().get("is_doc_chunk")).isEqualTo("true");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Parse a method declaration from a source snippet by wrapping it in a class.
     */
    private static MethodDeclaration parseMethod(String methodSource) {
        CompilationUnit cu = StaticJavaParser.parse(
                "class T {\n" + methodSource + "\n}");
        return cu.findFirst(MethodDeclaration.class).orElseThrow();
    }

    /**
     * Parse a class declaration from a source snippet.
     */
    private static ClassOrInterfaceDeclaration parseClass(String classSource) {
        CompilationUnit cu = StaticJavaParser.parse(classSource);
        return cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }
}
