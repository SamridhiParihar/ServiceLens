package com.servicelens.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Represents an active conversation session between a user and ServiceLens.
 *
 * <p>A session is scoped to a single {@code serviceName} and retains the last
 * {@code N} turns of conversation history so follow-up queries like
 * "why is it failing?" or "what happens next?" can be answered in context.</p>
 *
 * <p>Sessions expire after 30 minutes of inactivity — checked at read time by
 * {@link ConversationSessionService}.</p>
 *
 * @param sessionId     unique identifier (UUID) for this session
 * @param serviceName   the ingested service this session is querying
 * @param history       ordered list of prior turns, oldest first, capped at 5
 * @param createdAt     when the session was first created
 * @param lastActiveAt  when the last query was processed — used for TTL expiry check
 */
public record ConversationSession(
        UUID sessionId,
        String serviceName,
        List<ConversationTurn> history,
        Instant createdAt,
        Instant lastActiveAt
) {}
