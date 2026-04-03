package com.servicelens.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed repository for the {@code conversation_sessions} table.
 *
 * <p>Uses {@link JdbcTemplate} directly (not JPA) to avoid transaction-manager
 * conflicts with the Neo4j {@code @Primary} transaction manager.  Session history
 * is stored as a {@code JSONB} column and serialised / deserialised with Jackson.</p>
 *
 * <p>The table is created on startup by
 * {@link com.servicelens.config.PostgresSchemaInitializer}.</p>
 */
@Repository
public class ConversationSessionRepository {

    /** Maximum number of turns kept per session. */
    static final int MAX_TURNS = 5;

    private static final TypeReference<List<ConversationTurn>> TURN_LIST_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ConversationSessionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Find a session by its UUID.
     *
     * @param sessionId the session to look up
     * @return an {@link Optional} containing the session, or empty if not found
     */
    public Optional<ConversationSession> findById(UUID sessionId) {
        List<ConversationSession> rows = jdbc.query(
                "SELECT * FROM conversation_sessions WHERE session_id = ?::uuid",
                (rs, rowNum) -> new ConversationSession(
                        UUID.fromString(rs.getString("session_id")),
                        rs.getString("service_name"),
                        parseHistory(rs.getString("history")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_active_at").toInstant()),
                sessionId.toString());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Create a new session with an empty history.
     *
     * @param serviceName the service this session is scoped to
     * @return the newly created session
     */
    public ConversationSession create(String serviceName) {
        UUID    sessionId = UUID.randomUUID();
        Instant now       = Instant.now();
        jdbc.update("""
                INSERT INTO conversation_sessions
                    (session_id, service_name, history, created_at, last_active_at)
                VALUES (?::uuid, ?, '[]'::jsonb, ?, ?)
                """,
                sessionId.toString(), serviceName,
                Timestamp.from(now), Timestamp.from(now));
        return new ConversationSession(sessionId, serviceName, List.of(), now, now);
    }

    /**
     * Append a turn to the session history, capping at {@link #MAX_TURNS}.
     *
     * <p>If the session does not exist (e.g. has been deleted or never created),
     * this is a no-op and {@code -1} is returned.</p>
     *
     * @param sessionId the target session
     * @param turn      the turn to append
     * @return the 0-based index of the newly appended turn, or {@code -1} if the
     *         session was not found
     */
    public int appendTurn(UUID sessionId, ConversationTurn turn) {
        Optional<ConversationSession> existing = findById(sessionId);
        if (existing.isEmpty()) return -1;

        List<ConversationTurn> history = new ArrayList<>(existing.get().history());
        int turnIndex = history.size();   // capture BEFORE adding — this is the new turn's index
        history.add(turn);
        if (history.size() > MAX_TURNS) {
            history = history.subList(history.size() - MAX_TURNS, history.size());
        }

        jdbc.update("""
                UPDATE conversation_sessions
                   SET history = ?::jsonb, last_active_at = ?
                 WHERE session_id = ?::uuid
                """,
                toJson(history), Timestamp.from(Instant.now()), sessionId.toString());

        return turnIndex;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<ConversationTurn> parseHistory(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, TURN_LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<ConversationTurn> turns) {
        try {
            return objectMapper.writeValueAsString(turns);
        } catch (Exception e) {
            return "[]";
        }
    }
}
