package com.servicelens.retrieval.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Classifies a natural language query into one of the defined QueryIntents.
 *
 * WHY INTENT CLASSIFICATION MATTERS:
 * ────────────────────────────────────
 * Different questions require completely different retrieval strategies.
 *
 * "where is timeout configured?"
 * → FIND_CONFIGURATION intent
 * → Strategy: vector search with CONFIG boost
 * → Graph: not needed
 *
 * "what calls processPayment?"
 * → TRACE_CALLERS intent
 * → Strategy: graph CALLS reverse traversal
 * → Vector: minimal (just find the entry point)
 *
 * "what breaks if I change UserService?"
 * → IMPACT_ANALYSIS intent
 * → Strategy: graph DEPENDS_ON reverse traversal
 * → Vector: minimal
 *
 * If you use the same retrieval strategy for all three, you get:
 * - Question 1: correct (vector finds config)
 * - Question 2: wrong (vector finds methods mentioning payment, not callers)
 * - Question 3: wrong (vector finds UserService code, not its dependents)
 *
 * Intent classification routes each query to the optimal strategy,
 * making answers consistently better without any other changes.
 *
 * CLASSIFICATION APPROACH:
 * ─────────────────────────
 * We use pattern matching on the query text.
 * This is intentionally simple — the patterns cover 90% of real queries.
 * LLM-based classification would be more accurate but adds 500ms latency.
 * Pattern matching adds < 1ms.
 *
 * Patterns are evaluated in priority order — first match wins.
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /**
     * Priority-ordered list of (intent, patterns) pairs.
     * More specific intents are listed first.
     */
    private static final List<Map.Entry<QueryIntent, List<String>>> INTENT_PATTERNS =
            List.of(
                    // ── IMPACT_ANALYSIS — "what breaks if..." ────────────────────
                    Map.entry(QueryIntent.IMPACT_ANALYSIS, List.of(
                            "what breaks", "what will break", "impact of",
                            "depends on", "dependent",
                            "if i change", "if i modify", "if i remove",
                            "who uses", "what uses", "which classes use"
                    )),

                    // ── TRACE_CALL_CHAIN — "what does X trigger" ─────────────────
                    Map.entry(QueryIntent.TRACE_CALL_CHAIN, List.of(
                            "what happens", "what does.*call", "what does.*trigger",
                            "what is triggered", "call chain", "call flow",
                            "execution flow", "what is called by", "sequence of calls",
                            "downstream", "what does.*invoke",
                            "flow", "walk.*through.*(flow|process|chain|execution|steps)", "execution path"
                    )),

                    // ── TRACE_CALLERS — "who calls X" ─────────────────────────────
                    Map.entry(QueryIntent.TRACE_CALLERS, List.of(
                            "who calls", "what calls", "which.*call",
                            "callers of", "where is.*called", "where is.*invoked",
                            "called from", "invoked from", "upstream callers",
                            "where does.*call", "who invokes"
                    )),

                    // ── FIND_CONFIGURATION — "how is X configured" ───────────────
                    Map.entry(QueryIntent.FIND_CONFIGURATION, List.of(
                            "configured", "configuration", "config",
                            "how is.*set", "what is the.*timeout", "what is the.*limit",
                            "what is the.*value", "property", "properties", "setting",
                            "application.yml", "application.properties",
                            "what port", "what url", "what host"
                    )),

                    // ── UNDERSTAND_CONTRACT — "what interface" ────────────────────
                    Map.entry(QueryIntent.UNDERSTAND_CONTRACT, List.of(
                            "what interface", "implements", "override",
                            "what contract", "interface contract", "extend",
                            "abstract method", "inherited from",
                            "what is the contract"
                    )),

                    // ── DEBUG_ERROR — exception/error analysis ────────────────────
                    Map.entry(QueryIntent.DEBUG_ERROR, List.of(
                            "exception", "error", "failing", "broken",
                            "stacktrace", "stack trace", "throws", "thrown",
                            "nullpointer", "npe", "null pointer",
                            "why is.*failing", "why does.*fail",
                            "timeout error", "connection refused",
                            "404", "500", "400", "503",
                            "not working", "isn't working", "doesn't work"
                    )),

                    // ── NULL_SAFETY — null checks ─────────────────────────────────
                    Map.entry(QueryIntent.NULL_SAFETY, List.of(
                            "null check", "null safety", "nullpointer",
                            "could.*be null", "can.*be null", "be null",
                            "is.*null", "null before",
                            "where is null checked", "missing null check"
                    )),

                    // ── UNDERSTAND_BUSINESS_RULE — why questions ─────────────────
                    Map.entry(QueryIntent.UNDERSTAND_BUSINESS_RULE, List.of(
                            "why does", "why is", "business rule", "business logic",
                            "what is the rule", "the.*rule", "policy", "what should",
                            "allowed to", "requirement", "constraint",
                            "sla", "service level", "why.*retry", "why.*limit"
                    )),

                    // ── FIND_TESTS — test-related ──────────────────────────────────
                    Map.entry(QueryIntent.FIND_TESTS, List.of(
                            "test", "unit test", "integration test",
                            "test case", "mock", "spec", "coverage"
                    )),

                    // ── FIND_ENDPOINTS — REST API discovery ──────────────────────
                    Map.entry(QueryIntent.FIND_ENDPOINTS, List.of(
                            "endpoint", "rest", "api endpoint", "controller",
                            "http method", "get mapping", "post mapping",
                            "what routes", "what urls", "what apis", "routes",
                            "apis"
                    )),

                    // ── FIND_IMPLEMENTATION — default "where is X" ───────────────
                    Map.entry(QueryIntent.FIND_IMPLEMENTATION, List.of(
                            "where is", "which class", "which method",
                            "find.*method", "find.*class", "show me",
                            "where.*defined", "where.*implemented",
                            "how.*implemented", "how does.*work"
                    ))
            );

    /**
     * Classify a query into a QueryIntent.
     * Returns the best-guess intent with no confidence information.
     * Use {@link #classifyWithConfidence(String)} when routing decisions depend on certainty.
     */
    public QueryIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryIntent.FIND_IMPLEMENTATION;
        }

        String queryLower = query.toLowerCase().trim();

        for (Map.Entry<QueryIntent, List<String>> entry : INTENT_PATTERNS) {
            QueryIntent intent = entry.getKey();
            List<String> patterns = entry.getValue();

            for (String pattern : patterns) {
                // Use regex matching for patterns with .* wildcards
                if (pattern.contains(".*")) {
                    if (queryLower.matches(".*" + pattern + ".*")) {
                        log.debug("Query '{}' → intent {} (regex: '{}')",
                                truncate(query, 40), intent, pattern);
                        return intent;
                    }
                } else {
                    // Simple substring match
                    if (queryLower.contains(pattern)) {
                        log.debug("Query '{}' → intent {} (pattern: '{}')",
                                truncate(query, 40), intent, pattern);
                        return intent;
                    }
                }
            }
        }

        // Default fallback
        log.debug("Query '{}' → default intent FIND_IMPLEMENTATION", truncate(query, 40));
        return QueryIntent.FIND_IMPLEMENTATION;
    }

    /**
     * Classify with confidence — returns the intent plus a score indicating
     * how confident we are in the classification.
     *
     * <p>Confidence tiers:
     * <ul>
     *   <li>0 pattern matches → 0.30 (LOW  — no signal, pure default)</li>
     *   <li>1 pattern match   → 0.65 (MEDIUM — single signal, probably right)</li>
     *   <li>2+ pattern matches → 0.80+ (HIGH — multiple signals, confident)</li>
     * </ul>
     *
     * <p>{@link IntentBasedRetriever} uses the confidence tier to decide routing:
     * LOW routes to {@link QueryIntent#GENERAL_UNDERSTANDING}; MEDIUM appends a
     * clarification footer to the answer.
     */
    public IntentClassificationResult classifyWithConfidence(String query) {
        if (query == null || query.isBlank()) {
            // Empty query — no signal at all, LOW confidence
            return new IntentClassificationResult(QueryIntent.FIND_IMPLEMENTATION, 0.3f);
        }

        String queryLower = query.toLowerCase().trim();
        int matchCount = 0;
        QueryIntent firstMatch = QueryIntent.FIND_IMPLEMENTATION;
        boolean foundFirst = false;

        for (Map.Entry<QueryIntent, List<String>> entry : INTENT_PATTERNS) {
            QueryIntent intent = entry.getKey();
            List<String> patterns = entry.getValue();

            for (String pattern : patterns) {
                boolean matches = pattern.contains(".*")
                        ? queryLower.matches(".*" + pattern + ".*")
                        : queryLower.contains(pattern);

                if (matches) {
                    matchCount++;
                    if (!foundFirst) {
                        firstMatch = intent;
                        foundFirst = true;
                    }
                }
            }
        }

        // 0 matches → LOW (0.30); otherwise scale from MEDIUM upward
        float confidence = (matchCount == 0)
                ? 0.30f
                : Math.min(0.50f + (matchCount * 0.15f), 1.0f);

        return new IntentClassificationResult(firstMatch, confidence);
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}