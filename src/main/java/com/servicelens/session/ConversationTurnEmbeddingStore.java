package com.servicelens.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Stores and retrieves conversation turn embeddings in pgvector for RAG-based
 * memory retrieval.
 *
 * <h3>Storage model</h3>
 * <p>Each call to {@link #store} embeds the text {@code "Q: {query}\nA: {answer}"}
 * using the configured Ollama embedding model and persists the resulting vector
 * in the {@code conversation_turn_embeddings} table alongside the raw query and
 * answer texts.  The {@code (session_id, turn_index)} pair is the primary key,
 * so re-storing the same turn is idempotent (upsert).</p>
 *
 * <h3>Retrieval model</h3>
 * <p>{@link #findRelevant} embeds the incoming query and runs a pgvector cosine
 * similarity search scoped to a single session, returning the top-K most
 * semantically similar past turns regardless of when they occurred.  This
 * complements the sliding-window layer (which handles recency) by surfacing
 * older, contextually relevant exchanges.</p>
 *
 * <h3>Fault isolation</h3>
 * <p>All database errors are caught and logged as warnings — never propagated.
 * A failed embedding write degrades gracefully to sliding-window-only memory;
 * a failed read returns an empty list so the answer pipeline continues normally.</p>
 */
@Component
public class ConversationTurnEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEmbeddingStore.class);

    private final JdbcTemplate    jdbc;
    private final EmbeddingModel  embeddingModel;

    public ConversationTurnEmbeddingStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc           = jdbc;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embeds a conversation turn and persists it for future RAG retrieval.
     *
     * <p>The embedded text is {@code "Q: {queryText}\nA: {answerText}"} so the
     * vector captures both the question intent and the answer content, improving
     * semantic match quality for follow-up queries on either side.</p>
     *
     * @param sessionId   the session this turn belongs to
     * @param turnIndex   0-based position of this turn in the session history
     * @param queryText   the user's original query
     * @param answerText  the answer summary (already truncated to SUMMARY_MAX_CHARS)
     */
    public void store(UUID sessionId, int turnIndex, String queryText, String answerText) {
        String textToEmbed = "Q: " + queryText + "\nA: " + answerText;
        try {
            float[] vector   = embeddingModel.embed(textToEmbed);
            String pgVector  = toPgVector(vector);

            jdbc.update("""
                    INSERT INTO conversation_turn_embeddings
                        (session_id, turn_index, query_text, answer_text, embedding)
                    VALUES (?::uuid, ?, ?, ?, ?::vector)
                    ON CONFLICT (session_id, turn_index) DO UPDATE SET
                        query_text  = EXCLUDED.query_text,
                        answer_text = EXCLUDED.answer_text,
                        embedding   = EXCLUDED.embedding
                    """,
                    sessionId.toString(), turnIndex, queryText, answerText, pgVector);

            log.debug("Stored turn embedding session={} turn={}", sessionId, turnIndex);
        } catch (Exception e) {
            log.warn("Failed to store turn embedding session={} turn={}: {}", sessionId, turnIndex, e.getMessage());
        }
    }

    /**
     * Returns the top-K past turns for a session that are semantically closest
     * to {@code currentQuery}, ordered by cosine similarity (most similar first).
     *
     * <p>Results are returned as {@link ConversationTurn} records with {@code intent}
     * and {@code verbosity} set to {@code null} — those fields are not needed for
     * context injection and are not stored in the embedding table.</p>
     *
     * @param sessionId    the session to search within
     * @param currentQuery the incoming user query used as the search vector
     * @param topK         maximum number of turns to return
     * @return list of matching turns, most relevant first; empty if none found or on error
     */
    public List<ConversationTurn> findRelevant(UUID sessionId, String currentQuery, int topK) {
        try {
            float[] queryVector = embeddingModel.embed(currentQuery);
            String  pgVector    = toPgVector(queryVector);

            return jdbc.query("""
                    SELECT query_text, answer_text
                    FROM   conversation_turn_embeddings
                    WHERE  session_id = ?::uuid
                    ORDER  BY embedding <=> ?::vector
                    LIMIT  ?
                    """,
                    (rs, rowNum) -> new ConversationTurn(
                            rs.getString("query_text"),
                            null,
                            rs.getString("answer_text"),
                            null),
                    sessionId.toString(), pgVector, topK);

        } catch (Exception e) {
            log.warn("Failed to retrieve turn embeddings session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
