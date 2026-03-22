package com.servicelens.synthesis;

import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.retrieval.intent.RetrievalResult;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Assembles a token-budget-aware context string from a {@link RetrievalResult}
 * for use in LLM answer synthesis.
 *
 * <h3>Design goals</h3>
 * <ul>
 *   <li><b>Budget enforcement</b> — the assembled context is capped at
 *       {@link #MAX_CHARS} characters (≈ 3 000 tokens at 4 chars/token) so that
 *       the combined prompt always fits inside the synthesis model's context
 *       window.</li>
 *   <li><b>Intent awareness</b> — graph results (call chains, callers, impacted
 *       classes, endpoints) are rendered with their own labelled sections so the
 *       LLM receives structured, easy-to-parse context regardless of intent.</li>
 *   <li><b>Graceful truncation</b> — when the raw context exceeds the budget,
 *       the string is hard-cut and a {@code ...[truncated]} marker is appended
 *       so the LLM knows the context was trimmed rather than assuming it is
 *       complete.</li>
 * </ul>
 */
@Component
public class ContextAssembler {

    /**
     * Maximum characters allowed in the assembled context.
     *
     * <p>At roughly 4 characters per token this is ≈ 3 000 tokens, leaving
     * ample headroom inside a 4 096-token context window for the system prompt,
     * user question, and generated answer.
     */
    static final int MAX_CHARS = 32_000;

    /**
     * Build a formatted context string from retrieval results, respecting the
     * character budget.
     *
     * <p>Sections are appended in priority order: semantic code chunks first
     * (highest information density), then graph structural results.  Once the
     * running length exceeds {@link #MAX_CHARS} each append method returns
     * early so later sections are not even formatted.
     *
     * @param result the populated retrieval result
     * @return assembled context string, truncated to {@link #MAX_CHARS} if necessary
     */
    public String assemble(RetrievalResult result) {
        StringBuilder sb = new StringBuilder();

        appendSemanticMatches(sb, result.semanticMatches());
        appendCallChain(sb, result.callChain());
        appendCallers(sb, result.callers());
        appendImpactedClasses(sb, result.impactedClasses());
        appendEndpoints(sb, result.endpointMethods());

        String raw = sb.toString().trim();
        if (raw.isEmpty()) return "";
        return raw.length() > MAX_CHARS
                ? raw.substring(0, MAX_CHARS) + "\n...[truncated]"
                : raw;
    }

    // ── Section renderers ─────────────────────────────────────────────────────

    private void appendSemanticMatches(StringBuilder sb, List<Document> matches) {
        if (matches.isEmpty()) return;
        sb.append("=== RELEVANT CODE CHUNKS ===\n\n");
        int rank = 1;
        for (Document doc : matches) {
            if (sb.length() > MAX_CHARS) return;
            sb.append(String.format("--- Chunk %d ", rank++));
            appendChunkHeader(sb, doc.getMetadata());
            sb.append(" ---\n");
            sb.append(doc.getContent()).append("\n\n");
        }
    }

    private void appendChunkHeader(StringBuilder sb, Map<String, Object> meta) {
        String cls  = (String) meta.get("class_name");
        String elem = (String) meta.get("element_name");
        String type = (String) meta.get("chunk_type");
        if (cls != null && elem != null) {
            sb.append(String.format("[%s.%s / %s]", cls, elem, type));
        } else if (cls != null) {
            sb.append(String.format("[%s / %s]", cls, type));
        } else if (type != null) {
            sb.append(String.format("[%s]", type));
        }
    }

    private void appendCallChain(StringBuilder sb, List<MethodNode> chain) {
        if (chain.isEmpty()) return;
        if (sb.length() > MAX_CHARS) return;
        sb.append("=== CALL CHAIN ===\n");
        for (int i = 0; i < chain.size(); i++) {
            MethodNode m = chain.get(i);
            sb.append(String.format("%d. %s.%s", i + 1, m.getClassName(), m.getSimpleName()));
            if (m.isEndpoint()) {
                sb.append(String.format(" [%s %s]", m.getHttpMethod(), m.getEndpointPath()));
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void appendCallers(StringBuilder sb, List<MethodNode> callers) {
        if (callers.isEmpty()) return;
        if (sb.length() > MAX_CHARS) return;
        sb.append("=== CALLERS ===\n");
        for (MethodNode m : callers) {
            sb.append(String.format("- %s.%s\n", m.getClassName(), m.getSimpleName()));
        }
        sb.append("\n");
    }

    private void appendImpactedClasses(StringBuilder sb, List<ClassNode> classes) {
        if (classes.isEmpty()) return;
        if (sb.length() > MAX_CHARS) return;
        sb.append("=== IMPACTED CLASSES ===\n");
        for (ClassNode c : classes) {
            sb.append(String.format("- %s (%s)\n",
                    c.getSimpleName(),
                    c.getNodeType() != null ? c.getNodeType().name() : "CLASS"));
        }
        sb.append("\n");
    }

    private void appendEndpoints(StringBuilder sb, List<MethodNode> endpoints) {
        if (endpoints.isEmpty()) return;
        if (sb.length() > MAX_CHARS) return;
        sb.append("=== HTTP ENDPOINTS ===\n");
        for (MethodNode m : endpoints) {
            sb.append(String.format("- [%s] %s  →  %s.%s\n",
                    m.getHttpMethod(), m.getEndpointPath(),
                    m.getClassName(), m.getSimpleName()));
        }
        sb.append("\n");
    }
}
