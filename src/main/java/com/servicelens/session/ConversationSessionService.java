package com.servicelens.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade for conversation session lifecycle management.
 *
 * <h3>Session lifecycle</h3>
 * <ol>
 *   <li>First request (no {@code sessionId}) → new session created, UUID returned to client.</li>
 *   <li>Client sends {@code sessionId} back on follow-up requests.</li>
 *   <li>Service looks up the session, checks TTL, and returns history.</li>
 *   <li>After synthesis, the completed turn (query + intent + answer summary) is appended.</li>
 *   <li>Sessions older than 30 minutes are treated as expired → new session created silently.</li>
 * </ol>
 *
 * <h3>Token budget</h3>
 * <p>Answer summaries are capped at {@link #SUMMARY_MAX_CHARS} characters so that
 * injecting the last 2 turns into the LLM context consumes at most ~300 characters —
 * a negligible fraction of the context window.</p>
 */
@Service
public class ConversationSessionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSessionService.class);

    /** Sessions inactive for longer than this are treated as expired. */
    static final Duration SESSION_TTL = Duration.ofMinutes(30);

    /** Max characters of an answer stored as the turn summary. */
    static final int SUMMARY_MAX_CHARS = 400;

    private final ConversationSessionRepository repository;

    public ConversationSessionService(ConversationSessionRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns an existing active session or creates a fresh one.
     *
     * <p>A new session is created when:
     * <ul>
     *   <li>{@code sessionIdStr} is {@code null} or blank (first request)</li>
     *   <li>the session does not exist in the database</li>
     *   <li>the session has been inactive for more than {@link #SESSION_TTL}</li>
     *   <li>{@code sessionIdStr} is not a valid UUID</li>
     *   <li>the session exists but was created for a different {@code serviceName}
     *       (switching services always starts a fresh session)</li>
     * </ul>
     *
     * @param sessionIdStr the session ID from the client request (may be null)
     * @param serviceName  the service being queried
     * @return an active {@link ConversationSession}
     */
    public ConversationSession getOrCreate(String sessionIdStr, String serviceName) {
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                UUID sessionId = UUID.fromString(sessionIdStr);
                Optional<ConversationSession> existing = repository.findById(sessionId);
                if (existing.isPresent()) {
                    ConversationSession session = existing.get();
                    if (!session.serviceName().equals(serviceName)) {
                        log.debug("Session {} belongs to service '{}', not '{}' — creating new session",
                                sessionId, session.serviceName(), serviceName);
                    } else if (isExpired(session)) {
                        log.debug("Session {} expired — creating new session for '{}'",
                                sessionId, serviceName);
                    } else {
                        log.debug("Resuming session {} for '{}' ({} prior turns)",
                                sessionId, serviceName, session.history().size());
                        return session;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.debug("Invalid sessionId '{}' — creating new session", sessionIdStr);
            }
        }

        ConversationSession session = repository.create(serviceName);
        log.debug("Created new session {} for '{}'", session.sessionId(), serviceName);
        return session;
    }

    /**
     * Appends a completed turn to the session history.
     *
     * <p>Truncates the answer to {@link #SUMMARY_MAX_CHARS} before storing so
     * the history column stays compact.  The full answer is returned to the client
     * in the API response and is never truncated there.</p>
     *
     * @param sessionId the session to update
     * @param query     the user's original question
     * @param intent    the detected intent name (e.g. {@code "FIND_IMPLEMENTATION"})
     * @param answer    the full synthesized answer (will be summarised for storage)
     */
    public void addTurn(UUID sessionId, String query, String intent, String answer, String verbosity) {
        String summary = (answer != null && answer.length() > SUMMARY_MAX_CHARS)
                ? answer.substring(0, SUMMARY_MAX_CHARS)
                : (answer != null ? answer : "");
        repository.appendTurn(sessionId, new ConversationTurn(query, intent, summary, verbosity));
    }

    /**
     * Returns the full history for a session.
     *
     * @param sessionId the session to query
     * @return ordered list of prior turns (oldest first), or empty list if not found
     */
    public List<ConversationTurn> getHistory(UUID sessionId) {
        return repository.findById(sessionId)
                .map(ConversationSession::history)
                .orElse(List.of());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private boolean isExpired(ConversationSession session) {
        return Duration.between(session.lastActiveAt(), Instant.now())
                       .compareTo(SESSION_TTL) > 0;
    }
}
