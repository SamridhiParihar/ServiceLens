package com.servicelens.synthesis;

import com.servicelens.retrieval.intent.QueryIntent;
import com.servicelens.retrieval.intent.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AnswerSynthesizer}.
 *
 * <p>The Ollama {@link ChatClient} fluent chain is fully mocked so no live
 * model is required.  {@link ContextAssembler} is also mocked to isolate
 * synthesis logic from context-formatting logic.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnswerSynthesizer — unit")
class AnswerSynthesizerTest {

    // ── ChatClient fluent chain mocks ─────────────────────────────────────────

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec       callSpec;

    @Mock private ContextAssembler contextAssembler;

    private AnswerSynthesizer synthesizer;

    @BeforeEach
    void setUp() {
        synthesizer = new AnswerSynthesizer(chatClient, contextAssembler);

        // Wire up the full fluent chain with lenient stubs to avoid
        // UnnecessaryStubbingException in tests that take the no-context path.
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callSpec);
        lenient().when(callSpec.content()).thenReturn("The executionLoop runs up to 3 times.");

        lenient().when(contextAssembler.assemble(any())).thenReturn("assembled context");
        lenient().when(contextAssembler.assembleWithHistory(any(), any())).thenReturn("assembled context");
    }

    // ── No context ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Returns no-context fallback when RetrievalResult is empty")
    void emptyRetrieval_returnsNoContextFallback() {
        RetrievalResult retrieval = RetrievalResult.semantic(
                QueryIntent.FIND_IMPLEMENTATION, 0.7f, List.of());

        SynthesisResult result = synthesizer.synthesize("how does X work?", retrieval);

        assertThat(result.synthesized()).isFalse();
        assertThat(result.answer()).contains("No relevant code found");
        assertThat(result.contextChunksUsed()).isZero();
        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("No-context fallback preserves intent and confidence")
    void emptyRetrieval_fallbackPreservesIntentAndConfidence() {
        RetrievalResult retrieval = RetrievalResult.semantic(
                QueryIntent.DEBUG_ERROR, 0.55f, List.of());

        SynthesisResult result = synthesizer.synthesize("why is X failing?", retrieval);

        assertThat(result.intent()).isEqualTo(QueryIntent.DEBUG_ERROR);
        assertThat(result.intentConfidence()).isEqualTo(0.55f);
    }

    // ── Successful synthesis ──────────────────────────────────────────────────

    @Test
    @DisplayName("Calls ContextAssembler and LLM when context is available")
    void withContext_callsAssemblerAndLlm() {
        RetrievalResult retrieval = retrievalWithOneChunk(QueryIntent.FIND_IMPLEMENTATION, 0.9f);

        SynthesisResult result = synthesizer.synthesize("how does executionLoop work?", retrieval);

        verify(contextAssembler).assembleWithHistory(eq(retrieval), any());
        verify(chatClient).prompt();
        verify(callSpec).content();
        assertThat(result.synthesized()).isTrue();
        assertThat(result.answer()).isEqualTo("The executionLoop runs up to 3 times.");
    }

    @Test
    @DisplayName("SynthesisResult carries correct contextChunksUsed count")
    void synthesisResult_correctContextChunksUsed() {
        RetrievalResult retrieval = retrievalWithOneChunk(QueryIntent.FIND_IMPLEMENTATION, 0.9f);

        SynthesisResult result = synthesizer.synthesize("query", retrieval);

        assertThat(result.contextChunksUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("SynthesisResult intent and confidence match the retrieval result")
    void synthesisResult_intentAndConfidenceMatchRetrieval() {
        RetrievalResult retrieval = retrievalWithOneChunk(QueryIntent.TRACE_CALLERS, 0.82f);

        SynthesisResult result = synthesizer.synthesize("who calls X?", retrieval);

        assertThat(result.intent()).isEqualTo(QueryIntent.TRACE_CALLERS);
        assertThat(result.intentConfidence()).isEqualTo(0.82f);
    }

    // ── LLM failure ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Returns fallback when the LLM call throws")
    void llmThrows_returnsFallback() {
        // Override only what differs from the lenient setUp stubs
        when(callSpec.content()).thenThrow(new RuntimeException("Ollama timeout"));

        RetrievalResult retrieval = retrievalWithOneChunk(QueryIntent.DEBUG_ERROR, 0.75f);

        SynthesisResult result = synthesizer.synthesize("why does X fail?", retrieval);

        assertThat(result.synthesized()).isFalse();
        assertThat(result.answer()).contains("No relevant code found");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RetrievalResult retrievalWithOneChunk(QueryIntent intent, float confidence) {
        Document doc = new Document("void executionLoop() {}",
                Map.of("chunk_type", "CODE", "element_name", "executionLoop",
                       "class_name", "Orchestrator", "service_name", "demo2"));
        return RetrievalResult.semantic(intent, confidence, List.of(doc));
    }
}
