package com.servicelens.retrieval.intent;

/**
 * Result of intent classification — the detected intent and a confidence score.
 *
 * <p>Confidence tiers used by {@link IntentBasedRetriever} for routing decisions:
 * <ul>
 *   <li><b>HIGH</b> (&gt;0.75)  — proceed with the classified intent as normal</li>
 *   <li><b>MEDIUM</b> (0.5–0.75) — proceed but append a clarification footer to the answer</li>
 *   <li><b>LOW</b> (&lt;0.5)    — override to {@link QueryIntent#GENERAL_UNDERSTANDING} fallback</li>
 * </ul>
 *
 * @param intent     the best-guess {@link QueryIntent} from pattern matching
 * @param confidence classifier certainty in [0.0, 1.0]
 */
public record IntentClassificationResult(QueryIntent intent, float confidence) {

    /** Confidence threshold above which classification is considered HIGH confidence. */
    public static final float HIGH_THRESHOLD   = 0.75f;

    /** Confidence threshold below which classification is considered LOW confidence. */
    public static final float LOW_THRESHOLD    = 0.50f;

    public boolean isHigh()   { return confidence > HIGH_THRESHOLD; }
    public boolean isMedium() { return confidence >= LOW_THRESHOLD && confidence <= HIGH_THRESHOLD; }
    public boolean isLow()    { return confidence < LOW_THRESHOLD; }
}
