package com.servicelens.session;

/**
 * A single exchange in a conversation session.
 *
 * @param query         the user's original question for this turn
 * @param intent        the detected {@link com.servicelens.retrieval.intent.QueryIntent} name
 * @param answerSummary first 400 characters of the synthesized answer — kept compact to
 *                      preserve token budget when injected as conversation history
 * @param verbosity     the {@link com.servicelens.synthesis.VerbosityLevel} name used for
 *                      this turn (e.g. {@code "SHORT"}, {@code "DETAILED"}, {@code "DEEP_DIVE"});
 *                      may be {@code null} for turns recorded before verbosity was introduced
 */
public record ConversationTurn(String query, String intent, String answerSummary, String verbosity) {}
