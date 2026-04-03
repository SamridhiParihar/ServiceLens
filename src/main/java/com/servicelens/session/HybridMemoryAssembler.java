package com.servicelens.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles conversation history for LLM context injection using a two-layer
 * hybrid memory strategy: a RAG layer for long-distance recall and a sliding
 * window layer for immediate follow-up resolution.
 *
 * <h3>Why two layers?</h3>
 * <ul>
 *   <li><b>Sliding window</b> — always includes the last 2 turns. Handles
 *       pronoun resolution ("it", "that", "the third one") and immediate
 *       follow-ups that reference the most recent exchange.</li>
 *   <li><b>RAG layer</b> — semantically searches all stored turns for the
 *       session and retrieves the top-K most relevant to the current query.
 *       Handles long-distance recall: "remember the auth issue we discussed?"
 *       or "compare all the endpoints we found" from 10+ turns ago.</li>
 * </ul>
 *
 * <h3>Assembly order</h3>
 * <p>Combined history = [RAG turns, deduplicated] + [sliding window turns],
 * oldest-relevant first, most-recent last.  This ordering ensures the LLM
 * sees distant relevant context before the recent context, matching the
 * natural reading order of a conversation thread.</p>
 *
 * <h3>Deduplication</h3>
 * <p>If a RAG-retrieved turn is also present in the sliding window (matched by
 * query text), it is excluded from the RAG layer — the sliding window copy is
 * sufficient and avoids sending the same exchange twice.</p>
 */
@Component
public class HybridMemoryAssembler {

    private static final Logger log = LoggerFactory.getLogger(HybridMemoryAssembler.class);

    /** Number of semantically relevant past turns to retrieve via RAG. */
    static final int RAG_TOP_K = 3;

    /** Number of most-recent turns always injected via the sliding window. */
    static final int SLIDING_WINDOW_SIZE = 2;

    private final ConversationTurnEmbeddingStore embeddingStore;

    public HybridMemoryAssembler(ConversationTurnEmbeddingStore embeddingStore) {
        this.embeddingStore = embeddingStore;
    }

    /**
     * Assembles the combined conversation history for a given query.
     *
     * <p>If the session has no history yet (first query), an empty list is
     * returned immediately — no embedding lookups are performed.</p>
     *
     * @param sessionId    the active session
     * @param currentQuery the user's current query (used as the RAG search vector)
     * @param fullHistory  the complete ordered history from the session (oldest first)
     * @return deduplicated, ordered list of turns to inject into the LLM prompt
     */
    public List<ConversationTurn> assemble(UUID sessionId, String currentQuery,
                                           List<ConversationTurn> fullHistory) {
        if (fullHistory.isEmpty()) {
            return List.of();
        }

        // ── Sliding window: last N turns (always included) ────────────────────
        int histSize = fullHistory.size();
        List<ConversationTurn> slidingWindow = histSize > SLIDING_WINDOW_SIZE
                ? fullHistory.subList(histSize - SLIDING_WINDOW_SIZE, histSize)
                : new ArrayList<>(fullHistory);

        // ── RAG layer: semantically relevant past turns ───────────────────────
        List<ConversationTurn> ragTurns = embeddingStore.findRelevant(sessionId, currentQuery, RAG_TOP_K);

        // ── Deduplication: skip RAG turns already in the sliding window ───────
        Set<String> slidingQueries = slidingWindow.stream()
                .map(ConversationTurn::query)
                .collect(Collectors.toSet());

        List<ConversationTurn> dedupedRag = ragTurns.stream()
                .filter(t -> !slidingQueries.contains(t.query()))
                .collect(Collectors.toList());

        // ── Combine: RAG (older relevant) first, sliding window (recent) last ─
        List<ConversationTurn> combined = new ArrayList<>(dedupedRag);
        combined.addAll(slidingWindow);

        log.debug("Hybrid memory: rag={} (after dedup={}) + sliding={} → total={}",
                ragTurns.size(), dedupedRag.size(), slidingWindow.size(), combined.size());

        return combined;
    }
}
