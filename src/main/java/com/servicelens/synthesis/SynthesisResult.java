package com.servicelens.synthesis;

import com.servicelens.retrieval.intent.QueryIntent;

/**
 * Immutable result of LLM answer synthesis.
 *
 * <p>Wraps the generated natural-language answer together with provenance
 * metadata so callers can display attribution, debug prompt quality, and
 * budget future calls.
 *
 * @param answer             synthesized natural-language answer from the LLM
 * @param intent             query intent that was detected for this request
 * @param intentConfidence   classifier confidence in {@code [0.0, 1.0]}
 * @param modelUsed          name of the Ollama model that generated the answer
 * @param contextChunksUsed  number of retrieved items fed to the LLM as context
 * @param synthesized        {@code true} when an LLM answer was generated;
 *                           {@code false} for the no-context fallback path
 */
public record SynthesisResult(
        String      answer,
        QueryIntent intent,
        float       intentConfidence,
        String      modelUsed,
        int         contextChunksUsed,
        boolean     synthesized
) {

    /**
     * Fallback result returned when no relevant context was retrieved.
     *
     * <p>Rather than calling the LLM with an empty context (which produces
     * hallucinated answers), the synthesizer short-circuits and returns this
     * canned response so the caller always gets a usable {@link SynthesisResult}.
     *
     * @param intent     the classified intent (for routing metadata)
     * @param confidence the classifier confidence
     * @return a non-synthesized fallback result
     */
    public static SynthesisResult noContext(QueryIntent intent, float confidence) {
        return new SynthesisResult(
                "No relevant code found for this query. " +
                "Ensure the service has been ingested and try rephrasing your question.",
                intent, confidence, "none", 0, false);
    }
}
