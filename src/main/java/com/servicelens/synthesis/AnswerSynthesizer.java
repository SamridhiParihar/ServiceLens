package com.servicelens.synthesis;

import com.servicelens.retrieval.intent.RetrievalResult;
import com.servicelens.session.ConversationTurn;
import com.servicelens.synthesis.VerbosityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

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
     * <p>Convenience overload with no conversation history — use for stateless queries
     * or when the caller manages history injection independently.
     *
     * @param query     the original user question
     * @param retrieval the populated retrieval result from the retrieval pipeline
     * @return a {@link SynthesisResult} containing the answer and provenance metadata;
     *         never {@code null}
     */
    public SynthesisResult synthesize(String query, RetrievalResult retrieval) {
        return synthesize(query, retrieval, List.of(), VerbosityLevel.DETAILED);
    }

    /**
     * Synthesize a natural-language answer, injecting prior conversation turns into
     * the context so the LLM can resolve follow-up references.
     *
     * <p>If {@code history} is non-empty, it is prepended to the code context via
     * {@link ContextAssembler#assembleWithHistory} so the LLM sees what was previously
     * asked and answered before it processes the new question.
     *
     * @param query     the original user question
     * @param retrieval the populated retrieval result from the retrieval pipeline
     * @param history   recent conversation turns (last 2 turns recommended); may be empty
     * @return a {@link SynthesisResult} containing the answer and provenance metadata;
     *         never {@code null}
     */
    public SynthesisResult synthesize(String query, RetrievalResult retrieval,
                                      List<ConversationTurn> history) {
        return synthesize(query, retrieval, history, VerbosityLevel.DETAILED);
    }

    /**
     * Synthesize a natural-language answer with conversation history and verbosity control.
     *
     * @param query     the original user question
     * @param retrieval the populated retrieval result
     * @param history   recent conversation turns (last 2 recommended); may be empty
     * @param verbosity controls answer length and depth
     * @return a {@link SynthesisResult}; never {@code null}
     */
    public SynthesisResult synthesize(String query, RetrievalResult retrieval,
                                      List<ConversationTurn> history, VerbosityLevel verbosity) {
        if (retrieval.totalContextSize() == 0 && history.isEmpty()) {
            log.debug("No context available — returning fallback for intent={}", retrieval.intent());
            return SynthesisResult.noContext(retrieval.intent(), retrieval.intentConfidence());
        }

        String context      = contextAssembler.assembleWithHistory(retrieval, history);
        String systemPrompt = PromptTemplates.systemPrompt(retrieval.intent(), verbosity);
        String userPrompt   = PromptTemplates.userPrompt(query, context);

        log.debug("Synthesizing: intent={} verbosity={} context_chars={} history_turns={} model={}",
                retrieval.intent(), verbosity, context.length(), history.size(), modelName);

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
