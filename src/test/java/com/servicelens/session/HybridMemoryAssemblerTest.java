package com.servicelens.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HybridMemoryAssembler}.
 *
 * <p>Verifies the sliding window + RAG deduplication logic without hitting any
 * real database or embedding model.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HybridMemoryAssembler — unit")
class HybridMemoryAssemblerTest {

    @Mock private ConversationTurnEmbeddingStore embeddingStore;

    private HybridMemoryAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new HybridMemoryAssembler(embeddingStore);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ConversationTurn turn(String query) {
        return new ConversationTurn(query, "FIND_IMPLEMENTATION", "answer for " + query, "DETAILED");
    }

    private static ConversationTurn ragTurn(String query) {
        // RAG-reconstructed turns have null intent + verbosity
        return new ConversationTurn(query, null, "answer for " + query, null);
    }

    // ── empty history ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty history")
    class EmptyHistory {

        @Test
        @DisplayName("Returns empty list and does not call embedding store")
        void emptyHistory_returnsEmptyWithoutEmbeddingLookup() {
            List<ConversationTurn> result = assembler.assemble(UUID.randomUUID(), "query", List.of());

            assertThat(result).isEmpty();
            verifyNoInteractions(embeddingStore);
        }
    }

    // ── sliding window only (no RAG results) ──────────────────────────────────

    @Nested
    @DisplayName("Sliding window behaviour")
    class SlidingWindowTests {

        @Test
        @DisplayName("Returns last 2 turns when history has exactly 2 entries")
        void twoTurns_returnsBoth() {
            UUID id = UUID.randomUUID();
            List<ConversationTurn> history = List.of(turn("q1"), turn("q2"));
            when(embeddingStore.findRelevant(eq(id), anyString(), anyInt())).thenReturn(List.of());

            List<ConversationTurn> result = assembler.assemble(id, "current", history);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).query()).isEqualTo("q1");
            assertThat(result.get(1).query()).isEqualTo("q2");
        }

        @Test
        @DisplayName("Returns only last 2 turns when history has more than 2 entries")
        void manyTurns_returnsOnlyLastTwo() {
            UUID id = UUID.randomUUID();
            List<ConversationTurn> history = List.of(
                    turn("q1"), turn("q2"), turn("q3"), turn("q4"), turn("q5"));
            when(embeddingStore.findRelevant(eq(id), anyString(), anyInt())).thenReturn(List.of());

            List<ConversationTurn> result = assembler.assemble(id, "current", history);

            // RAG returns empty → result is only sliding window (q4, q5)
            assertThat(result).hasSize(2);
            assertThat(result.get(0).query()).isEqualTo("q4");
            assertThat(result.get(1).query()).isEqualTo("q5");
        }
    }

    // ── hybrid (RAG + sliding window) ─────────────────────────────────────────

    @Nested
    @DisplayName("Hybrid assembly")
    class HybridTests {

        @Test
        @DisplayName("Combines RAG turns before sliding window turns")
        void ragAndSliding_ragComesFirst() {
            UUID id = UUID.randomUUID();
            List<ConversationTurn> history = List.of(
                    turn("q1"), turn("q2"), turn("q3"), turn("q4"));
            // RAG retrieves q1 (an older, semantically relevant turn)
            when(embeddingStore.findRelevant(eq(id), anyString(), anyInt()))
                    .thenReturn(List.of(ragTurn("q1")));

            List<ConversationTurn> result = assembler.assemble(id, "current", history);

            // Expected: [q1 from RAG] + [q3, q4 from sliding window]
            assertThat(result).hasSize(3);
            assertThat(result.get(0).query()).isEqualTo("q1"); // RAG turn first
            assertThat(result.get(1).query()).isEqualTo("q3"); // sliding window
            assertThat(result.get(2).query()).isEqualTo("q4"); // sliding window
        }

        @Test
        @DisplayName("Deduplicates: RAG turn already in sliding window is excluded")
        void ragTurnInSlidingWindow_deduped() {
            UUID id = UUID.randomUUID();
            List<ConversationTurn> history = List.of(turn("q1"), turn("q2"));
            // RAG returns q2, which is also the most recent sliding window turn
            when(embeddingStore.findRelevant(eq(id), anyString(), anyInt()))
                    .thenReturn(List.of(ragTurn("q2")));

            List<ConversationTurn> result = assembler.assemble(id, "current", history);

            // q2 should appear only once (from sliding window, not duplicated by RAG)
            assertThat(result).hasSize(2);
            assertThat(result.stream().filter(t -> t.query().equals("q2")).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Multiple RAG results with partial overlap are correctly merged")
        void partialOverlap_mergedCorrectly() {
            UUID id = UUID.randomUUID();
            // history: q1, q2, q3, q4, q5
            List<ConversationTurn> history = List.of(
                    turn("q1"), turn("q2"), turn("q3"), turn("q4"), turn("q5"));
            // RAG: q1 (old, relevant), q4 (in sliding window — duplicate), q2 (old, relevant)
            when(embeddingStore.findRelevant(eq(id), anyString(), anyInt()))
                    .thenReturn(List.of(ragTurn("q1"), ragTurn("q4"), ragTurn("q2")));

            List<ConversationTurn> result = assembler.assemble(id, "current", history);

            // Sliding window = q4, q5
            // RAG after dedup = q1, q2 (q4 removed as duplicate)
            // Combined = [q1, q2] + [q4, q5] = 4 turns
            assertThat(result).hasSize(4);
            assertThat(result.get(0).query()).isEqualTo("q1");
            assertThat(result.get(1).query()).isEqualTo("q2");
            assertThat(result.get(2).query()).isEqualTo("q4");
            assertThat(result.get(3).query()).isEqualTo("q5");
        }

        @Test
        @DisplayName("Uses RAG_TOP_K constant when calling embedding store")
        void callsEmbeddingStoreWithCorrectTopK() {
            UUID id = UUID.randomUUID();
            when(embeddingStore.findRelevant(eq(id), anyString(), anyInt())).thenReturn(List.of());

            assembler.assemble(id, "current", List.of(turn("q1")));

            verify(embeddingStore).findRelevant(eq(id), eq("current"), eq(HybridMemoryAssembler.RAG_TOP_K));
        }
    }
}
