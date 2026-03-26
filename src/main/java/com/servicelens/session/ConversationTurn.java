package com.servicelens.session;

/**
 * A single exchange in a conversation session.
 *
 * @param query         the user's original question for this turn
 * @param intent        the detected {@link com.servicelens.retrieval.intent.QueryIntent} name
 * @param answerSummary first 150 characters of the synthesized answer — kept short to
 *                      preserve token budget when injected as conversation history
 */
public record ConversationTurn(String query, String intent, String answerSummary) {}
