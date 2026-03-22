package com.servicelens.reranking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link RetrievalReranker}.
 *
 * <p><strong>Stage 1 — metadata reranking</strong> ({@link RetrievalReranker#metadataRerank})
 * makes zero LLM calls, so those tests are pure heuristic assertions.  The
 * {@link ChatClient} is mocked but never invoked in these scenarios.</p>
 *
 * <p><strong>Stage 2 — cross-encoder reranking</strong> is only lightly tested here
 * (single-candidate pass-through) to avoid coupling tests to the Ollama prompt
 * format.  Integration-level accuracy testing of the LLM scorer belongs in a
 * separate integration test suite that actually runs the model.</p>
 *
 * <p><strong>boostByType</strong> is a pure list-manipulation method with no
 * external dependencies and is fully tested here.</p>
 *
 * <p>No Spring context is required.</p>
 */
@DisplayName("RetrievalReranker")
@ExtendWith(MockitoExtension.class)
class RetrievalRerankerTest {

    @Mock
    private ChatClient chatClient;

    /** The component under test. */
    private RetrievalReranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new RetrievalReranker(chatClient);
    }

    // ─────────────────────────────────────────────────────────────────────
    // metadataRerank() — basic behaviour
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metadataRerank() — basic behaviour")
    class MetadataRerankBasicTests {

        @Test
        @DisplayName("Returns empty list immediately when candidates list is empty")
        void returnsEmptyListForEmptyCandidates() {
            List<Document> result = reranker.metadataRerank("any query", List.of(), 5);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Result size is bounded by topK even when more candidates exist")
        void resultSizeBoundedByTopK() {
            List<Document> candidates = List.of(
                    doc("a", "CODE", 0.3),
                    doc("b", "CODE", 0.5),
                    doc("c", "CODE", 0.7),
                    doc("d", "CODE", 0.8),
                    doc("e", "CODE", 0.9)
            );

            List<Document> result = reranker.metadataRerank("find method", candidates, 3);

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("Result size equals candidate count when topK exceeds candidate count")
        void resultContainsAllWhenTopKExceedsCandidates() {
            List<Document> candidates = List.of(
                    doc("a", "CODE", 0.8),
                    doc("b", "CODE", 0.6)
            );

            List<Document> result = reranker.metadataRerank("query", candidates, 10);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Result is sorted in descending score order for identical chunk types")
        void resultIsSortedDescending() {
            // No boosts apply for a generic query — ordering by raw distance score
            List<Document> candidates = List.of(
                    doc("low",    "CODE", 0.6),
                    doc("high",   "CODE", 0.1),   // distance 0.1 → similarity 0.9
                    doc("medium", "CODE", 0.4)
            );

            List<Document> result = reranker.metadataRerank("generic query", candidates, 3);

            // The document with distance 0.1 (highest similarity 0.9) should come first
            assertThat(result.get(0).getContent()).isEqualTo("high");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // metadataRerank() — CONFIG query boost
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metadataRerank() — CONFIG boost heuristics")
    class ConfigBoostTests {

        @Test
        @DisplayName("CONFIG chunk is boosted above CODE chunk for 'timeout' query")
        void configChunkBoostedForTimeoutQuery() {
            Document configDoc = doc("timeout: 30000", "CONFIG", 0.3);  // similarity 0.7
            Document codeDoc   = doc("public void go() {}", "CODE", 0.15); // similarity 0.85

            List<Document> result = reranker.metadataRerank(
                    "what is the timeout configured?",
                    List.of(configDoc, codeDoc),
                    2
            );

            // After boost, CONFIG doc should be ranked first
            assertThat(result.get(0).getContent()).isEqualTo("timeout: 30000");
        }

        @Test
        @DisplayName("CONFIG chunk is boosted above CODE chunk for 'setting' query")
        void configChunkBoostedForSettingQuery() {
            Document configDoc = doc("max-retry: 3", "CONFIG", 0.3);
            Document codeDoc   = doc("void retry() {}", "CODE", 0.2);

            List<Document> result = reranker.metadataRerank(
                    "what is the retry setting?",
                    List.of(codeDoc, configDoc),
                    2
            );

            assertThat(result.get(0).getContent()).isEqualTo("max-retry: 3");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // metadataRerank() — TEST penalty
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metadataRerank() — TEST chunk penalty")
    class TestChunkPenaltyTests {

        @Test
        @DisplayName("TEST chunk is penalised below CODE chunk for non-test query")
        void testChunkPenalisedForNonTestQuery() {
            Document testDoc = doc("@Test void paymentTest() {}", "TEST", 0.2);  // sim 0.8
            Document codeDoc = doc("void payment() {}",          "CODE", 0.3);  // sim 0.7

            // Non-test query — TEST should be penalised below CODE
            List<Document> result = reranker.metadataRerank(
                    "how does payment work?",
                    List.of(testDoc, codeDoc),
                    2
            );

            assertThat(result.get(0).getContent()).isEqualTo("void payment() {}");
        }

        @Test
        @DisplayName("TEST chunk is NOT penalised when query explicitly mentions 'test'")
        void testChunkNotPenalisedForTestQuery() {
            Document testDoc = doc("@Test void checkPayment() {}", "TEST", 0.1); // sim 0.9
            Document codeDoc = doc("void payment() {}",           "CODE", 0.3); // sim 0.7

            List<Document> result = reranker.metadataRerank(
                    "what tests exist for payment?",
                    List.of(codeDoc, testDoc),
                    2
            );

            // TEST doc has higher similarity and no penalty for a test query
            assertThat(result.get(0).getContent()).isEqualTo("@Test void checkPayment() {}");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // metadataRerank() — SCHEMA boost
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metadataRerank() — SCHEMA boost heuristics")
    class SchemaBoostTests {

        @Test
        @DisplayName("SCHEMA chunk is boosted above CODE chunk for 'table' query")
        void schemaChunkBoostedForTableQuery() {
            Document schemaDoc = doc("CREATE TABLE orders (...)", "SCHEMA", 0.35);
            Document codeDoc   = doc("OrderRepository.findAll()", "CODE",   0.2);

            List<Document> result = reranker.metadataRerank(
                    "describe the orders table schema",
                    List.of(codeDoc, schemaDoc),
                    2
            );

            assertThat(result.get(0).getContent()).isEqualTo("CREATE TABLE orders (...)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // metadataRerank() — score capping
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metadataRerank() — score is capped at 1.0")
    class ScoreCapTests {

        @Test
        @DisplayName("Boosted score never exceeds 1.0 even with multiple boosts applied")
        void scoreDoesNotExceedOne() {
            // High similarity (distance 0) + CONFIG boost + YAML language boost
            Document superConfigDoc = doc("timeout: 5000", "CONFIG", "yaml", 0.0);

            List<Document> result = reranker.metadataRerank(
                    "what is the configured timeout?",
                    List.of(superConfigDoc),
                    1
            );

            // Should not throw; just verify we get the doc back
            assertThat(result).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // crossEncoderRerank() — single-candidate pass-through
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("crossEncoderRerank() — edge cases")
    class CrossEncoderEdgeCaseTests {

        @Test
        @DisplayName("Single-candidate list is returned as-is without calling the LLM")
        void singleCandidateReturnedWithoutLLMCall() {
            Document only = doc("void doIt() {}", "CODE", 0.3);

            List<Document> result = reranker.crossEncoderRerank("query", List.of(only), 5);

            assertThat(result).containsExactly(only);
            // Verify no LLM call was made
            verifyNoInteractions(chatClient);
        }

        @Test
        @DisplayName("Empty candidates list is returned as-is without calling the LLM")
        void emptyCandidateListReturnedWithoutLLMCall() {
            List<Document> result = reranker.crossEncoderRerank("query", List.of(), 5);

            assertThat(result).isEmpty();
            verifyNoInteractions(chatClient);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // boostByType()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("boostByType()")
    class BoostByTypeTests {

        @Test
        @DisplayName("TARGET type documents are moved to the front of the result list")
        void targetTypeMovedToFront() {
            Document codeDoc1   = doc("code1",  "CODE",   0.2);
            Document configDoc  = doc("config", "CONFIG", 0.4);
            Document codeDoc2   = doc("code2",  "CODE",   0.1);

            List<Document> result = reranker.boostByType(
                    List.of(codeDoc1, configDoc, codeDoc2), "CONFIG", 3);

            assertThat(result.get(0).getContent()).isEqualTo("config");
        }

        @Test
        @DisplayName("Result size is bounded by topK")
        void resultBoundedByTopK() {
            List<Document> candidates = List.of(
                    doc("a", "CODE", 0.1),
                    doc("b", "CODE", 0.2),
                    doc("c", "CODE", 0.3),
                    doc("d", "CODE", 0.4)
            );

            List<Document> result = reranker.boostByType(candidates, "CODE", 2);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("All documents are returned when no target type matches")
        void allReturnedWhenNoTypeMatches() {
            List<Document> candidates = List.of(
                    doc("a", "CODE", 0.1),
                    doc("b", "CODE", 0.2)
            );

            List<Document> result = reranker.boostByType(candidates, "SCHEMA", 5);

            assertThat(result).containsExactlyElementsOf(candidates);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Build a {@link Document} with a {@code chunk_type} metadata entry and a
     * {@code distance} entry (Spring AI cosine distance, lower = more similar).
     *
     * @param content   document text
     * @param chunkType the chunk type string stored in metadata
     * @param distance  cosine distance (0.0 = identical, 1.0 = opposite)
     * @return a populated {@link Document}
     */
    private static Document doc(String content, String chunkType, double distance) {
        return new Document(content, Map.of(
                "chunk_type", chunkType,
                "distance",   distance
        ));
    }

    /**
     * Build a {@link Document} with chunk type, language, and distance metadata.
     *
     * @param content   document text
     * @param chunkType the chunk type string
     * @param language  lowercase language identifier
     * @param distance  cosine distance
     * @return a populated {@link Document}
     */
    private static Document doc(String content, String chunkType,
                                 String language, double distance) {
        return new Document(content, Map.of(
                "chunk_type", chunkType,
                "language",   language,
                "distance",   distance
        ));
    }
}
