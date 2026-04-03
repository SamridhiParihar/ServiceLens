package com.servicelens.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConversationSessionService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationSessionService — unit")
class ConversationSessionServiceTest {

    @Mock
    private ConversationSessionRepository  repository;

    @Mock
    private ConversationTurnEmbeddingStore embeddingStore;

    private ConversationSessionService service;

    @BeforeEach
    void setUp() {
        service = new ConversationSessionService(repository, embeddingStore);
    }

    // ── getOrCreate ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreate")
    class GetOrCreate {

        @Test
        @DisplayName("Creates new session when sessionId is null")
        void nullSessionId_createsNewSession() {
            ConversationSession newSession = freshSession();
            when(repository.create("svc")).thenReturn(newSession);

            ConversationSession result = service.getOrCreate(null, "svc");

            verify(repository).create("svc");
            verify(repository, never()).findById(any());
            assertThat(result).isEqualTo(newSession);
        }

        @Test
        @DisplayName("Creates new session when sessionId is blank")
        void blankSessionId_createsNewSession() {
            ConversationSession newSession = freshSession();
            when(repository.create("svc")).thenReturn(newSession);

            ConversationSession result = service.getOrCreate("  ", "svc");

            verify(repository).create("svc");
            assertThat(result).isEqualTo(newSession);
        }

        @Test
        @DisplayName("Creates new session when sessionId is not a valid UUID")
        void invalidUuid_createsNewSession() {
            ConversationSession newSession = freshSession();
            when(repository.create("svc")).thenReturn(newSession);

            ConversationSession result = service.getOrCreate("not-a-uuid", "svc");

            verify(repository).create("svc");
            assertThat(result).isEqualTo(newSession);
        }

        @Test
        @DisplayName("Returns existing session when found and not expired")
        void validNonExpiredSession_returnsExisting() {
            UUID sessionId = UUID.randomUUID();
            ConversationSession existing = sessionWithLastActive(sessionId, Instant.now().minusSeconds(60));
            when(repository.findById(sessionId)).thenReturn(Optional.of(existing));

            ConversationSession result = service.getOrCreate(sessionId.toString(), "svc");

            verify(repository, never()).create(anyString());
            assertThat(result).isEqualTo(existing);
        }

        @Test
        @DisplayName("Creates new session when existing session is expired (>30 min)")
        void expiredSession_createsNewSession() {
            UUID sessionId = UUID.randomUUID();
            ConversationSession expired = sessionWithLastActive(
                    sessionId, Instant.now().minus(ConversationSessionService.SESSION_TTL).minusSeconds(1));
            ConversationSession newSession = freshSession();

            when(repository.findById(sessionId)).thenReturn(Optional.of(expired));
            when(repository.create("svc")).thenReturn(newSession);

            ConversationSession result = service.getOrCreate(sessionId.toString(), "svc");

            verify(repository).create("svc");
            assertThat(result).isEqualTo(newSession);
        }

        @Test
        @DisplayName("Creates new session when session belongs to a different service")
        void serviceMismatch_createsNewSession() {
            UUID sessionId = UUID.randomUUID();
            // Session was created for "svc-a" but request is for "svc-b"
            ConversationSession mismatchedSession = new ConversationSession(
                    sessionId, "svc-a", List.of(),
                    Instant.now().minusSeconds(10), Instant.now().minusSeconds(5));
            ConversationSession newSession = freshSession();

            when(repository.findById(sessionId)).thenReturn(Optional.of(mismatchedSession));
            when(repository.create("svc-b")).thenReturn(newSession);

            ConversationSession result = service.getOrCreate(sessionId.toString(), "svc-b");

            verify(repository).create("svc-b");
            verify(repository, never()).create("svc-a");
            assertThat(result).isEqualTo(newSession);
        }

        @Test
        @DisplayName("Creates new session when sessionId not found in DB")
        void sessionNotFound_createsNewSession() {
            UUID sessionId = UUID.randomUUID();
            ConversationSession newSession = freshSession();

            when(repository.findById(sessionId)).thenReturn(Optional.empty());
            when(repository.create("svc")).thenReturn(newSession);

            ConversationSession result = service.getOrCreate(sessionId.toString(), "svc");

            verify(repository).create("svc");
            assertThat(result).isEqualTo(newSession);
        }
    }

    // ── addTurn ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addTurn")
    class AddTurn {

        @Test
        @DisplayName("Appends turn with truncated answer summary")
        void appendsTurnWithTruncatedSummary() {
            UUID sessionId = UUID.randomUUID();
            String longAnswer = "A".repeat(600);
            when(repository.appendTurn(eq(sessionId), any())).thenReturn(0);

            service.addTurn(sessionId, "how does X work?", "FIND_IMPLEMENTATION", longAnswer, "DETAILED");

            ArgumentCaptor<ConversationTurn> captor = ArgumentCaptor.forClass(ConversationTurn.class);
            verify(repository).appendTurn(eq(sessionId), captor.capture());

            ConversationTurn turn = captor.getValue();
            assertThat(turn.query()).isEqualTo("how does X work?");
            assertThat(turn.intent()).isEqualTo("FIND_IMPLEMENTATION");
            assertThat(turn.answerSummary()).hasSize(ConversationSessionService.SUMMARY_MAX_CHARS);
            assertThat(turn.verbosity()).isEqualTo("DETAILED");
        }

        @Test
        @DisplayName("Stores answer as-is when shorter than SUMMARY_MAX_CHARS")
        void shortAnswer_storedAsIs() {
            UUID sessionId = UUID.randomUUID();
            String shortAnswer = "The loop runs 3 times.";
            when(repository.appendTurn(eq(sessionId), any())).thenReturn(0);

            service.addTurn(sessionId, "query", "FIND_IMPLEMENTATION", shortAnswer, "SHORT");

            ArgumentCaptor<ConversationTurn> captor = ArgumentCaptor.forClass(ConversationTurn.class);
            verify(repository).appendTurn(eq(sessionId), captor.capture());

            assertThat(captor.getValue().answerSummary()).isEqualTo(shortAnswer);
            assertThat(captor.getValue().verbosity()).isEqualTo("SHORT");
        }

        @Test
        @DisplayName("Handles null answer gracefully")
        void nullAnswer_storesEmptySummary() {
            UUID sessionId = UUID.randomUUID();
            when(repository.appendTurn(eq(sessionId), any())).thenReturn(0);

            service.addTurn(sessionId, "query", "FIND_IMPLEMENTATION", null, "DEEP_DIVE");

            ArgumentCaptor<ConversationTurn> captor = ArgumentCaptor.forClass(ConversationTurn.class);
            verify(repository).appendTurn(eq(sessionId), captor.capture());

            assertThat(captor.getValue().answerSummary()).isEmpty();
            assertThat(captor.getValue().verbosity()).isEqualTo("DEEP_DIVE");
        }

        @Test
        @DisplayName("Calls embedding store with session_id, turn_index, query and summary")
        void callsEmbeddingStoreAfterAppend() {
            UUID sessionId = UUID.randomUUID();
            when(repository.appendTurn(eq(sessionId), any())).thenReturn(2); // turn index 2

            service.addTurn(sessionId, "what calls checkout?", "TRACE_CALLERS", "PaymentService calls it.", "DETAILED");

            verify(embeddingStore).store(sessionId, 2, "what calls checkout?", "PaymentService calls it.");
        }

        @Test
        @DisplayName("Does not call embedding store when session not found (appendTurn returns -1)")
        void sessionNotFound_skipsEmbeddingStore() {
            UUID sessionId = UUID.randomUUID();
            when(repository.appendTurn(eq(sessionId), any())).thenReturn(-1);

            service.addTurn(sessionId, "query", "FIND_IMPLEMENTATION", "answer", "DETAILED");

            verify(embeddingStore, never()).store(any(), anyInt(), anyString(), anyString());
        }
    }

    // ── getHistory ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getHistory")
    class GetHistory {

        @Test
        @DisplayName("Returns history from session when found")
        void sessionFound_returnsHistory() {
            UUID sessionId = UUID.randomUUID();
            List<ConversationTurn> turns = List.of(
                    new ConversationTurn("q1", "FIND_IMPLEMENTATION", "a1", "DETAILED"),
                    new ConversationTurn("q2", "TRACE_CALLERS", "a2", "SHORT")
            );
            ConversationSession session = new ConversationSession(
                    sessionId, "svc", turns, Instant.now(), Instant.now());
            when(repository.findById(sessionId)).thenReturn(Optional.of(session));

            List<ConversationTurn> result = service.getHistory(sessionId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).query()).isEqualTo("q1");
        }

        @Test
        @DisplayName("Returns empty list when session not found")
        void sessionNotFound_returnsEmptyList() {
            UUID sessionId = UUID.randomUUID();
            when(repository.findById(sessionId)).thenReturn(Optional.empty());

            List<ConversationTurn> result = service.getHistory(sessionId);

            assertThat(result).isEmpty();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ConversationSession freshSession() {
        return new ConversationSession(
                UUID.randomUUID(), "svc", List.of(), Instant.now(), Instant.now());
    }

    private static ConversationSession sessionWithLastActive(UUID sessionId, Instant lastActive) {
        return new ConversationSession(
                sessionId, "svc", List.of(), lastActive.minusSeconds(10), lastActive);
    }
}
