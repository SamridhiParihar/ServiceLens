package com.servicelens.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConversationTurnEmbeddingStore}.
 *
 * <p>All external dependencies (JdbcTemplate, EmbeddingModel) are mocked so these
 * tests run without a database or an Ollama instance.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationTurnEmbeddingStore — unit")
class ConversationTurnEmbeddingStoreTest {

    @Mock private JdbcTemplate   jdbc;
    @Mock private EmbeddingModel embeddingModel;

    private ConversationTurnEmbeddingStore store;

    private static final float[] DUMMY_VECTOR = new float[768]; // all zeros

    @BeforeEach
    void setUp() {
        store = new ConversationTurnEmbeddingStore(jdbc, embeddingModel);
    }

    // ── store() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("store()")
    class StoreTests {

        @Test
        @DisplayName("Calls EmbeddingModel with combined Q+A text")
        void embedsQaPairText() {
            when(embeddingModel.embed(anyString())).thenReturn(DUMMY_VECTOR);
            UUID sessionId = UUID.randomUUID();

            store.store(sessionId, 0, "how does checkout work?", "It calls PaymentService.");

            ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
            verify(embeddingModel).embed(textCaptor.capture());

            String embedded = textCaptor.getValue();
            assertThat(embedded).startsWith("Q: how does checkout work?");
            assertThat(embedded).contains("A: It calls PaymentService.");
        }

        @Test
        @DisplayName("Calls jdbc.update with correct session_id and turn_index")
        void persistsWithCorrectPrimaryKey() {
            when(embeddingModel.embed(anyString())).thenReturn(DUMMY_VECTOR);
            UUID sessionId = UUID.randomUUID();

            store.store(sessionId, 3, "query", "answer");

            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(anyString(), argsCaptor.capture());

            Object[] args = argsCaptor.getValue();
            assertThat(args[0]).isEqualTo(sessionId.toString()); // session_id
            assertThat(args[1]).isEqualTo(3);                    // turn_index
            assertThat(args[2]).isEqualTo("query");              // query_text
            assertThat(args[3]).isEqualTo("answer");             // answer_text
            // args[4] is the pgvector string — verified by format test below
        }

        @Test
        @DisplayName("Formats embedding as pgvector [v1,v2,...] string")
        void formatsPgVector() {
            float[] vector = {0.1f, 0.2f, 0.3f};
            when(embeddingModel.embed(anyString())).thenReturn(vector);

            store.store(UUID.randomUUID(), 0, "q", "a");

            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(anyString(), argsCaptor.capture());

            String pgVec = (String) argsCaptor.getValue()[4];
            assertThat(pgVec).startsWith("[").endsWith("]");
            assertThat(pgVec).contains("0.1").contains("0.2").contains("0.3");
        }

        @Test
        @DisplayName("Does not throw when EmbeddingModel fails — degrades gracefully")
        void embeddingFailure_doesNotPropagate() {
            when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Ollama unavailable"));

            // Should complete without throwing
            store.store(UUID.randomUUID(), 0, "query", "answer");

            // jdbc.update must NOT have been called
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("Does not throw when jdbc.update fails — degrades gracefully")
        void jdbcFailure_doesNotPropagate() {
            when(embeddingModel.embed(anyString())).thenReturn(DUMMY_VECTOR);
            when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("DB error"));

            // Should complete without throwing
            store.store(UUID.randomUUID(), 0, "query", "answer");
        }
    }

    // ── findRelevant() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findRelevant()")
    class FindRelevantTests {

        @Test
        @DisplayName("Embeds current query and calls jdbc.query with session_id and topK")
        void queriesWithCorrectParams() {
            when(embeddingModel.embed(anyString())).thenReturn(DUMMY_VECTOR);
            when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                    .thenReturn(List.of());

            UUID sessionId = UUID.randomUUID();
            store.findRelevant(sessionId, "what calls checkout?", 3);

            verify(embeddingModel).embed("what calls checkout?");

            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).query(anyString(), any(RowMapper.class), argsCaptor.capture());
            Object[] args = argsCaptor.getValue();
            assertThat(args[0]).isEqualTo(sessionId.toString()); // session_id
            assertThat(args[2]).isEqualTo(3);                    // LIMIT
        }

        @Test
        @DisplayName("Maps result rows to ConversationTurn with null intent and verbosity")
        void mapsResultsToConversationTurns() {
            when(embeddingModel.embed(anyString())).thenReturn(DUMMY_VECTOR);
            ConversationTurn expectedTurn = new ConversationTurn("past query", null, "past answer", null);
            when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                    .thenReturn(List.of(expectedTurn));

            List<ConversationTurn> results = store.findRelevant(UUID.randomUUID(), "current query", 3);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).query()).isEqualTo("past query");
            assertThat(results.get(0).answerSummary()).isEqualTo("past answer");
            assertThat(results.get(0).intent()).isNull();
            assertThat(results.get(0).verbosity()).isNull();
        }

        @Test
        @DisplayName("Returns empty list when EmbeddingModel fails — degrades gracefully")
        void embeddingFailure_returnsEmpty() {
            when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Ollama unavailable"));

            List<ConversationTurn> results = store.findRelevant(UUID.randomUUID(), "query", 3);

            assertThat(results).isEmpty();
            verify(jdbc, never()).query(anyString(), any(RowMapper.class), any());
        }

        @Test
        @DisplayName("Returns empty list when jdbc.query fails — degrades gracefully")
        void jdbcFailure_returnsEmpty() {
            when(embeddingModel.embed(anyString())).thenReturn(DUMMY_VECTOR);
            when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));

            List<ConversationTurn> results = store.findRelevant(UUID.randomUUID(), "query", 3);

            assertThat(results).isEmpty();
        }
    }
}
