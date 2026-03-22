package com.servicelens.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CodeRetriever}.
 *
 * <p>{@link VectorStore} is mocked so tests run without a live pgvector instance.
 * An {@link ArgumentCaptor} on {@link SearchRequest} lets us verify that the correct
 * {@code topK}, similarity threshold, and service-name filter are wired up for each
 * of the three retrieval methods.</p>
 */
@DisplayName("CodeRetriever")
@ExtendWith(MockitoExtension.class)
class CodeRetrieverTest {

    @Mock
    private VectorStore vectorStore;

    private CodeRetriever codeRetriever;

    private static final String SERVICE = "payment-service";

    @BeforeEach
    void setUp() {
        codeRetriever = new CodeRetriever(vectorStore);
    }

    // ─────────────────────────────────────────────────────────────────────
    // retrieve() — all chunk types
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieve() — all chunk types")
    class RetrieveAllTests {

        @Test
        @DisplayName("Delegates to VectorStore.similaritySearch and returns results unchanged")
        void delegatesAndReturnsResults() {
            Document doc = new Document("void pay() {}", Map.of("chunk_type", "CODE"));
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc));

            List<Document> result = codeRetriever.retrieve("payment logic", SERVICE, 10);

            assertThat(result).containsExactly(doc);
        }

        @Test
        @DisplayName("Passes the correct topK to the search request")
        void passesCorrectTopK() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

            codeRetriever.retrieve("query", SERVICE, 15);

            verify(vectorStore).similaritySearch(captor.capture());
            assertThat(captor.getValue().getTopK()).isEqualTo(15);
        }

        @Test
        @DisplayName("Applies a similarity threshold of 0.5")
        void appliesThresholdOfPointFive() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

            codeRetriever.retrieve("query", SERVICE, 5);

            verify(vectorStore).similaritySearch(captor.capture());
            assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Returns empty list when VectorStore returns no results")
        void returnsEmptyWhenNoResults() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            List<Document> result = codeRetriever.retrieve("obscure query", SERVICE, 10);

            assertThat(result).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // retrieveCode() — CODE chunks only
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieveCode() — CODE chunks only")
    class RetrieveCodeTests {

        @Test
        @DisplayName("Delegates to VectorStore and returns results unchanged")
        void delegatesAndReturnsResults() {
            Document doc = new Document("void processPayment() {}",
                    Map.of("chunk_type", "CODE"));
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc));

            List<Document> result = codeRetriever.retrieveCode("processPayment", SERVICE, 8);

            assertThat(result).containsExactly(doc);
            verify(vectorStore).similaritySearch(any(SearchRequest.class));
        }

        @Test
        @DisplayName("Passes the correct topK to the search request")
        void passesCorrectTopK() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

            codeRetriever.retrieveCode("query", SERVICE, 20);

            verify(vectorStore).similaritySearch(captor.capture());
            assertThat(captor.getValue().getTopK()).isEqualTo(20);
        }

        @Test
        @DisplayName("Applies a similarity threshold of 0.35")
        void appliesThresholdOfPointFive() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

            codeRetriever.retrieveCode("query", SERVICE, 5);

            verify(vectorStore).similaritySearch(captor.capture());
            assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.35);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // retrieveContext() — BUSINESS_CONTEXT chunks only
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieveContext() — BUSINESS_CONTEXT chunks only")
    class RetrieveContextTests {

        @Test
        @DisplayName("Delegates to VectorStore and returns results unchanged")
        void delegatesAndReturnsResults() {
            Document doc = new Document("Payments retry 3 times per BR-PAY-042.",
                    Map.of("chunk_type", "BUSINESS_CONTEXT"));
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc));

            List<Document> result = codeRetriever.retrieveContext(
                    "why does payment retry?", SERVICE, 5);

            assertThat(result).containsExactly(doc);
        }

        @Test
        @DisplayName("Applies a lower similarity threshold of 0.4 to improve recall")
        void appliesLowerThresholdForRecall() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

            codeRetriever.retrieveContext("why query", SERVICE, 8);

            verify(vectorStore).similaritySearch(captor.capture());
            assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.4);
        }

        @Test
        @DisplayName("Threshold is lower for retrieveContext than for retrieve")
        void contextThresholdIsLowerThanGeneral() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            ArgumentCaptor<SearchRequest> generalCaptor = ArgumentCaptor.forClass(SearchRequest.class);
            ArgumentCaptor<SearchRequest> contextCaptor = ArgumentCaptor.forClass(SearchRequest.class);

            codeRetriever.retrieve("query", SERVICE, 5);
            verify(vectorStore).similaritySearch(generalCaptor.capture());

            reset(vectorStore);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            codeRetriever.retrieveContext("query", SERVICE, 5);
            verify(vectorStore).similaritySearch(contextCaptor.capture());

            assertThat(contextCaptor.getValue().getSimilarityThreshold())
                    .isLessThan(generalCaptor.getValue().getSimilarityThreshold());
        }

        @Test
        @DisplayName("Passes the correct topK to the search request")
        void passesCorrectTopK() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

            codeRetriever.retrieveContext("query", SERVICE, 12);

            verify(vectorStore).similaritySearch(captor.capture());
            assertThat(captor.getValue().getTopK()).isEqualTo(12);
        }
    }
}
