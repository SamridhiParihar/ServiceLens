package com.servicelens.synthesis;

import com.servicelens.retrieval.intent.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LLM-backed answer synthesis layer.
 *
 * <p>This service sits on top of the retrieval pipeline: it takes a
 * {@link RetrievalResult} that has already been populated by
 * {@link com.servicelens.retrieval.intent.IntentBasedRetriever} and generates
 * a natural-language answer by:
 * <ol>
 *   <li>Short-circuiting with a canned fallback when the retrieval result is
 *       empty (avoids sending an empty context to the LLM which would produce
 *       hallucinated answers).</li>
 *   <li>Delegating to {@link ContextAssembler} to format the retrieval result
 *       into a token-budget-respecting context string.</li>
 *   <li>Selecting the intent-aware system prompt from {@link PromptTemplates}.</li>
 *   <li>Calling Ollama via Spring AI's {@link ChatClient} with synthesis-specific
 *       options (temperature {@code 0.1}, larger context window) to produce a
 *       grounded answer.</li>
 *   <li>Wrapping the answer in a {@link SynthesisResult} with full provenance
 *       metadata.</li>
 * </ol>
 *
 * <h3>Configuration properties</h3>
 * <pre>
 *   spring.ai.ollama.chat.model          # Ollama model name (default: phi3)
 *   servicelens.synthesis.temperature    # LLM temperature for synthesis (default: 0.1)
 *   servicelens.synthesis.num-ctx        # Context window tokens (default: 4096)
 * </pre>
 *
 * <h3>Error handling</h3>
 * <p>If the LLM call throws for any reason (network failure, model overload, etc.)
 * the exception is caught, logged at WARN level, and a {@link SynthesisResult#noContext}
 * fallback is returned so the caller always receives a usable response.
 */
@Service
public class AnswerSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(AnswerSynthesizer.class);

    private final ChatClient chatClient;
    private final ContextAssembler contextAssembler;

    @Value("${servicelens.groq.model:llama-3.3-70b-versatile}")
    private String modelName;

    public AnswerSynthesizer(@Qualifier("groqChatClient") ChatClient chatClient,
                             ContextAssembler contextAssembler) {
        this.chatClient       = chatClient;
        this.contextAssembler = contextAssembler;
    }

    /**
     * Synthesize a natural-language answer from the retrieved code context.
     *
     * @param query     the original user question
     * @param retrieval the populated retrieval result from the retrieval pipeline
     * @return a {@link SynthesisResult} containing the answer and provenance metadata;
     *         never {@code null}
     */
    public SynthesisResult synthesize(String query, RetrievalResult retrieval) {
        if (retrieval.totalContextSize() == 0) {
            log.debug("No context available — returning fallback for intent={}", retrieval.intent());
            return SynthesisResult.noContext(retrieval.intent(), retrieval.intentConfidence());
        }

        String context      = contextAssembler.assemble(retrieval);
        String systemPrompt = PromptTemplates.systemPrompt(retrieval.intent());
        String userPrompt   = PromptTemplates.userPrompt(query, context);

        log.debug("Synthesizing: intent={} context_chars={} model={}",
                retrieval.intent(), context.length(), modelName);

        try {
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

            log.debug("Synthesis complete: answer_chars={}", answer != null ? answer.length() : 0);

            return new SynthesisResult(
                    answer,
                    retrieval.intent(),
                    retrieval.intentConfidence(),
                    modelName,
                    retrieval.totalContextSize(),
                    true);

        } catch (Exception e) {
            log.warn("LLM synthesis failed (intent={}): {}", retrieval.intent(), e.getMessage());
            return SynthesisResult.noContext(retrieval.intent(), retrieval.intentConfidence());
        }
    }
}
