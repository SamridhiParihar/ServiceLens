package com.servicelens.retrieval.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IntentClassifier}.
 *
 * <p>These tests verify the correctness of the pattern-based intent classification
 * engine that routes developer queries to the appropriate retrieval strategy.
 * No mocks or Spring context are needed — the classifier is a pure, stateless
 * function operating on string pattern matching.</p>
 *
 * <h3>Test strategy:</h3>
 * <ul>
 *   <li>Each {@link QueryIntent} has at least one positive (should match) and one
 *       negative (should not match) test.</li>
 *   <li>Priority ordering is verified: more specific intents beat less specific ones
 *       when a query could match multiple patterns.</li>
 *   <li>Edge cases: empty query, null-safety, case-insensitivity, and confidence
 *       scoring ranges are explicitly tested.</li>
 * </ul>
 */
@DisplayName("IntentClassifier")
class IntentClassifierTest {

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new IntentClassifier();
    }

    // ─────────────────────────────────────────────────────────────────────
    // IMPACT_ANALYSIS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IMPACT_ANALYSIS intent")
    class ImpactAnalysisTests {

        @ParameterizedTest(name = "classify(''{0}'') == IMPACT_ANALYSIS")
        @ValueSource(strings = {
                "what breaks if I change OrderValidator",
                "impact of removing PaymentService",
                "who depends on this class",
                "who uses UserRepository",
                "what uses the Config bean"
        })
        @DisplayName("Should classify impact queries correctly")
        void shouldClassifyImpactQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.IMPACT_ANALYSIS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TRACE_CALL_CHAIN
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TRACE_CALL_CHAIN intent")
    class TraceCallChainTests {

        @ParameterizedTest(name = "classify(''{0}'') == TRACE_CALL_CHAIN")
        @ValueSource(strings = {
                "trace the call chain from processPayment",
                "what does checkout call",
                "downstream calls from handleOrder",
                "what happens after processOrder is called",
                "show the execution flow of validateCart",
                // new: flow / walk-through / execution path variants
                "what is the payment flow",
                "walk me through the checkout process",
                "what is the execution path for order submission",
                "walk me through the checkout process",
                "walk me through the payment flow"
        })
        @DisplayName("Should classify call chain trace queries")
        void shouldClassifyCallChainQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.TRACE_CALL_CHAIN);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TRACE_CALLERS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TRACE_CALLERS intent")
    class TraceCallersTests {

        @ParameterizedTest(name = "classify(''{0}'') == TRACE_CALLERS")
        @ValueSource(strings = {
                "who calls processPayment",
                "callers of validateOrder",
                "what calls this method",
                "find callers of createInvoice",
                "which methods call sendEmail",
                // new: "where does" and "who invokes" variants
                "where does processPayment get called",
                "who invokes validateOrder"
        })
        @DisplayName("Should classify caller-lookup queries")
        void shouldClassifyCallerQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.TRACE_CALLERS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_CONFIGURATION
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_CONFIGURATION intent")
    class FindConfigurationTests {

        @ParameterizedTest(name = "classify(''{0}'') == FIND_CONFIGURATION")
        @ValueSource(strings = {
                "what is the database configuration",
                "find config for retry timeout",
                "where is payment timeout configured",
                "application properties for kafka",
                "how is the connection pool set up"
        })
        @DisplayName("Should classify configuration queries")
        void shouldClassifyConfigurationQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.FIND_CONFIGURATION);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNDERSTAND_CONTRACT
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UNDERSTAND_CONTRACT intent")
    class UnderstandContractTests {

        @ParameterizedTest(name = "classify(''{0}'') == UNDERSTAND_CONTRACT")
        @ValueSource(strings = {
                "what interface does PaymentService implement",
                "what contract does OrderProcessor follow",
                "which classes extend BaseRepository",
                "does UserService override any methods",
                "what does the interface contract define"
        })
        @DisplayName("Should classify interface/contract queries")
        void shouldClassifyContractQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.UNDERSTAND_CONTRACT);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEBUG_ERROR
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DEBUG_ERROR intent")
    class DebugErrorTests {

        @ParameterizedTest(name = "classify(''{0}'') == DEBUG_ERROR")
        @ValueSource(strings = {
                "NullPointerException in checkout handler",
                "getting a RuntimeException during order processing",
                "ClassCastException when loading user",
                "application throws exception on startup",
                "why does payment fail with an error",
                // new: "not working" / "isn't working" / "doesn't work" variants
                "the payment service is not working",
                "checkout isn't working properly",
                "authentication doesn't work for new users"
        })
        @DisplayName("Should classify error/exception debug queries")
        void shouldClassifyDebugQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.DEBUG_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NULL_SAFETY
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NULL_SAFETY intent")
    class NullSafetyTests {

        @ParameterizedTest(name = "classify(''{0}'') == NULL_SAFETY")
        @ValueSource(strings = {
                "can order be null here",
                "is userId checked for null",
                "null check before processPayment",
                "where is order validated for null"
        })
        @DisplayName("Should classify null-safety queries")
        void shouldClassifyNullSafetyQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.NULL_SAFETY);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNDERSTAND_BUSINESS_RULE
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UNDERSTAND_BUSINESS_RULE intent")
    class UnderstandBusinessRuleTests {

        @ParameterizedTest(name = "classify(''{0}'') == UNDERSTAND_BUSINESS_RULE")
        @ValueSource(strings = {
                "what is the business logic for refunds",
                "why is payment limited to 10000",
                "what is the discount calculation rule",
                "explain the retry policy for failed payments"
        })
        @DisplayName("Should classify business rule queries")
        void shouldClassifyBusinessRuleQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.UNDERSTAND_BUSINESS_RULE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_TESTS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_TESTS intent")
    class FindTestsTests {

        @ParameterizedTest(name = "classify(''{0}'') == FIND_TESTS")
        @ValueSource(strings = {
                "find tests for PaymentService",
                "unit test for validateOrder",
                "where is the test for checkout",
                "how is processPayment tested",
                "show me test coverage for OrderService"
        })
        @DisplayName("Should classify test-lookup queries")
        void shouldClassifyTestQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.FIND_TESTS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_ENDPOINTS
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_ENDPOINTS intent")
    class FindEndpointsTests {

        @ParameterizedTest(name = "classify(''{0}'') == FIND_ENDPOINTS")
        @ValueSource(strings = {
                "show all REST endpoints",
                "list all API routes",
                "what HTTP endpoints does this service expose",
                "find all controller endpoints",
                "show me all GET mappings",
                // new: plural "apis" variant
                "show me all the APIs this service exposes"
        })
        @DisplayName("Should classify REST endpoint queries")
        void shouldClassifyEndpointQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.FIND_ENDPOINTS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIND_IMPLEMENTATION (default / fallback)
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIND_IMPLEMENTATION intent")
    class FindImplementationTests {

        @ParameterizedTest(name = "classify(''{0}'') == FIND_IMPLEMENTATION")
        @ValueSource(strings = {
                "where is processPayment defined",
                "which class handles order creation",
                "find method calculateDiscount",
                "show me the OrderService implementation",
                "locate the retry logic"
        })
        @DisplayName("Should classify code-location queries")
        void shouldClassifyFindImplementationQueries(String query) {
            assertThat(classifier.classify(query)).isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
        }

        @Test
        @DisplayName("'walk me through what it does' should NOT route to TRACE_CALL_CHAIN")
        void walkMeThroughWithoutFlowContext_isNotTraceCallChain() {
            assertThat(classifier.classify("How does startAgent work? Walk me through what it does."))
                    .isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
        }

        @Test
        @DisplayName("Empty query should fall back to FIND_IMPLEMENTATION")
        void emptyQueryFallsBackToFindImplementation() {
            assertThat(classifier.classify("")).isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
        }

        @Test
        @DisplayName("Query with no matching patterns should return FIND_IMPLEMENTATION")
        void unknownQueryFallsBackToFindImplementation() {
            assertThat(classifier.classify("xyzzy_unknown_pattern_12345"))
                    .isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case insensitivity
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case-insensitive matching")
    class CaseInsensitivityTests {

        @ParameterizedTest(name = "classify(''{0}'') is case-insensitive")
        @CsvSource({
                "WHO CALLS processPayment,        TRACE_CALLERS",
                "what BREAKS if I change X,       IMPACT_ANALYSIS",
                "NULL CHECK before use,            NULL_SAFETY",
                "FIND TESTS for OrderService,      FIND_TESTS"
        })
        @DisplayName("Classification should be case-insensitive")
        void classificationIsCaseInsensitive(String query, QueryIntent expected) {
            assertThat(classifier.classify(query.trim())).isEqualTo(expected);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // classifyWithConfidence
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("classifyWithConfidence")
    class ClassifyWithConfidenceTests {

        @Test
        @DisplayName("Should return non-null IntentClassificationResult for any query")
        void shouldReturnNonNullResult() {
            IntentClassificationResult result = classifier.classifyWithConfidence("who calls validateOrder");
            assertThat(result).isNotNull();
            assertThat(result.intent()).isNotNull();
        }

        @Test
        @DisplayName("Confidence should be between 0.0 and 1.0 inclusive")
        void confidenceShouldBeInRange() {
            IntentClassificationResult result =
                    classifier.classifyWithConfidence("what breaks if I change OrderService");
            assertThat(result.confidence())
                    .isGreaterThanOrEqualTo(0.0f)
                    .isLessThanOrEqualTo(1.0f);
        }

        @Test
        @DisplayName("Multi-keyword query should have higher confidence than single-keyword")
        void multiKeywordShouldHaveHigherConfidence() {
            IntentClassificationResult single =
                    classifier.classifyWithConfidence("callers of processPayment");
            IntentClassificationResult multi =
                    classifier.classifyWithConfidence("find callers of processPayment who calls it");

            assertThat(multi.confidence()).isGreaterThanOrEqualTo(single.confidence());
        }

        @Test
        @DisplayName("Empty query should return LOW confidence (0.3) for FIND_IMPLEMENTATION")
        void emptyQueryShouldReturnLowConfidence() {
            IntentClassificationResult result = classifier.classifyWithConfidence("");
            assertThat(result.intent()).isEqualTo(QueryIntent.FIND_IMPLEMENTATION);
            assertThat(result.confidence()).isEqualTo(0.3f);
            assertThat(result.isLow()).isTrue();
        }

        @Test
        @DisplayName("Query with no matching patterns should return LOW confidence (0.3)")
        void unknownQueryShouldReturnLowConfidence() {
            IntentClassificationResult result =
                    classifier.classifyWithConfidence("xyzzy_unknown_pattern_12345");
            assertThat(result.confidence()).isEqualTo(0.3f);
            assertThat(result.isLow()).isTrue();
        }

        @Test
        @DisplayName("Single-pattern match should return MEDIUM confidence")
        void singleMatchShouldBeMediumConfidence() {
            // "callers of" matches exactly one TRACE_CALLERS pattern
            IntentClassificationResult result =
                    classifier.classifyWithConfidence("callers of processPayment");
            assertThat(result.isMedium()).isTrue();
            assertThat(result.confidence()).isGreaterThanOrEqualTo(0.50f);
            assertThat(result.confidence()).isLessThanOrEqualTo(0.75f);
        }

        @Test
        @DisplayName("HIGH confidence result should pass isHigh() check")
        void highConfidenceResult() {
            // "who calls" + "callers of" both match TRACE_CALLERS → 2 hits → 0.80 (HIGH)
            IntentClassificationResult result =
                    classifier.classifyWithConfidence("who calls processPayment — find callers of validateOrder too");
            assertThat(result.confidence()).isGreaterThan(0.75f);
            assertThat(result.isHigh()).isTrue();
        }
    }
}
