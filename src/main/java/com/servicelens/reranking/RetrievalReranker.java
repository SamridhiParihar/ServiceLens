package com.servicelens.reranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Two-stage retrieval reranker.
 *
 * STAGE 1 — Metadata Reranker (fast, always runs):
 *   Adjusts vector similarity scores based on chunk metadata.
 *   Uses heuristic rules about which chunk types best answer
 *   which query patterns. Zero LLM calls. < 1ms.
 *
 * STAGE 2 — Cross-Encoder Reranker (accurate, runs when needed):
 *   Calls Ollama to score each (query, chunk) pair for relevance.
 *   Much more accurate than cosine similarity alone.
 *   Used for high-stakes queries (debug sessions).
 *   ~100ms per chunk.
 *
 * WHY RERANKING MATTERS:
 * ──────────────────────
 * Vector similarity measures "are these texts semantically similar?"
 * What we actually want: "does this chunk ANSWER the query?"
 *
 * These are different questions.
 *
 * Example:
 *   Query: "where is payment timeout configured?"
 *   application.yml:  { external-api: { timeout: 30000 } }
 *   → Similarity: 0.79 (YAML vs Java-oriented query = structural mismatch)
 *   → After metadata boost for CONFIG type: 0.94 (correct answer surfaces)
 *
 * Example 2:
 *   Query: "what handles NPE in payment flow?"
 *   logPaymentEvent() → mentions "error" → similarity 0.84
 *   → After LLM reranking: score 3/10 (logging ≠ handling NPE)
 *   handlePaymentException() → similarity 0.79
 *   → After LLM reranking: score 9/10 (directly handles exceptions)
 */
@Component
public class RetrievalReranker {

    private static final Logger log = LoggerFactory.getLogger(RetrievalReranker.class);

    private final ChatClient chatClient;

    // Pattern to extract numeric score from LLM response
    private static final Pattern SCORE_PATTERN = Pattern.compile("\\b([0-9]|10)\\b");

    public RetrievalReranker(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STAGE 1: METADATA RERANKER
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Rerank using metadata heuristics only. No LLM calls.
     * Always call this first — it's fast and improves precision significantly.
     */
    public List<Document> metadataRerank(String query, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;

        String queryLower = query.toLowerCase();

        List<ScoredDocument> scored = candidates.stream()
                .map(doc -> {
                    double score = extractSimilarityScore(doc);
                    score = applyMetadataBoosts(score, queryLower, doc);
                    return new ScoredDocument(doc, score);
                })
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .toList();

        log.debug("Metadata reranked {} candidates for query: '{}'",
                candidates.size(), truncate(query, 50));

        return scored.stream()
                .limit(topK)
                .map(ScoredDocument::document)
                .collect(Collectors.toList());
    }

    /**
     * Apply all metadata boosts to a document's score.
     */
    private double applyMetadataBoosts(double baseScore, String queryLower, Document doc) {
        double score = baseScore;
        String chunkType = getMetaString(doc, "chunk_type");
        String language  = getMetaString(doc, "language");

        // ── Query intent → chunk type alignment ──────────────────────────

        // Config queries → boost CONFIG chunks
        if (matchesAny(queryLower, "config", "configured", "timeout", "setting",
                "property", "value", "limit", "threshold", "port", "url")) {
            if ("CONFIG".equals(chunkType)) score += 0.18;
            if ("YAML".equals(language))    score += 0.10;
        }

        // API/endpoint queries → boost API_SPEC + endpoint-annotated CODE
        if (matchesAny(queryLower, "endpoint", "api", "rest", "http", "request",
                "response", "controller", "path", "url", "route")) {
            if ("API_SPEC".equals(chunkType)) score += 0.18;
            if ("true".equals(getMetaString(doc, "is_endpoint"))) score += 0.15;
        }

        // Schema/database queries → boost SCHEMA chunks
        if (matchesAny(queryLower, "table", "column", "database", "schema",
                "sql", "query", "index", "primary key", "foreign key")) {
            if ("SCHEMA".equals(chunkType)) score += 0.20;
        }

        // Business rule queries → boost BUSINESS_CONTEXT
        if (matchesAny(queryLower, "why", "business rule", "policy", "should",
                "allowed", "requirement", "constraint", "sla")) {
            if ("BUSINESS_CONTEXT".equals(chunkType)) score += 0.25;
            if ("DOCUMENTATION".equals(chunkType))   score += 0.15;
        }

        // Exception/error queries → boost methods with @throws in doc
        if (matchesAny(queryLower, "exception", "error", "throw", "fail",
                "catch", "handle", "npe", "null")) {
            if ("true".equals(getMetaString(doc, "has_throws_doc"))) score += 0.12;
        }

        // Transactional queries → boost @Transactional methods
        if (matchesAny(queryLower, "transaction", "rollback", "commit", "atomic")) {
            if ("true".equals(getMetaString(doc, "is_transactional"))) score += 0.20;
        }

        // Scheduled job queries
        if (matchesAny(queryLower, "schedule", "cron", "job", "batch", "periodic")) {
            if ("true".equals(getMetaString(doc, "is_scheduled"))) score += 0.20;
        }

        // Event/messaging queries
        if (matchesAny(queryLower, "event", "listener", "kafka", "message", "queue")) {
            if ("true".equals(getMetaString(doc, "is_event_handler"))) score += 0.20;
        }

        // ── Test file penalties ────────────────────────────────────────────
        // Unless query is explicitly about tests, penalise test chunks
        if (!matchesAny(queryLower, "test", "spec", "mock", "verify", "assert")) {
            if ("TEST".equals(chunkType)) score -= 0.12;
        }

        // ── Documentation type bonuses ─────────────────────────────────────
        // "Why" and "what" questions prefer doc chunks over raw code
        if (queryLower.startsWith("why") || queryLower.startsWith("what is")
                || queryLower.startsWith("what does")) {
            if ("DOCUMENTATION".equals(chunkType)) score += 0.10;
            if ("true".equals(getMetaString(doc, "is_doc_chunk"))) score += 0.08;
        }

        // ── Code implementation queries prefer CODE chunks ─────────────────
        if (matchesAny(queryLower, "where is", "how is", "how does", "implement",
                "written", "defined")) {
            if ("CODE".equals(chunkType)) score += 0.08;
        }

        return Math.min(score, 1.0);  // cap at 1.0
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STAGE 2: CROSS-ENCODER RERANKER (LLM-based)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Cross-encoder reranking using Ollama.
     * Each candidate is scored by asking the LLM how well it answers the query.
     *
     * Use this for:
     * - Debug sessions (accuracy > speed)
     * - Final answer synthesis (only top candidates after metadata rerank)
     * - Ambiguous queries where metadata heuristics are insufficient
     *
     * DO NOT use for: real-time autocomplete, large candidate sets (> 15)
     */
    public List<Document> crossEncoderRerank(String query, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;
        if (candidates.size() == 1) return candidates;

        log.debug("Cross-encoder reranking {} candidates", candidates.size());

        List<ScoredDocument> scored = new ArrayList<>();

        for (Document doc : candidates) {
            try {
                double llmScore = scorePairWithLLM(query, doc);
                // Blend LLM score (60%) with original similarity (40%)
                // Pure LLM score can be noisy — blending stabilises it
                double originalScore = extractSimilarityScore(doc);
                double blendedScore = (llmScore * 0.6) + (originalScore * 10 * 0.4);
                scored.add(new ScoredDocument(doc, blendedScore));

            } catch (Exception e) {
                log.warn("Cross-encoder scoring failed for chunk: {}", e.getMessage());
                // Fall back to original score
                scored.add(new ScoredDocument(doc, extractSimilarityScore(doc)));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        return scored.stream()
                .limit(topK)
                .map(ScoredDocument::document)
                .collect(Collectors.toList());
    }

    /**
     * Score a single (query, document) pair using Ollama.
     * Returns a score 0-10.
     */
    private double scorePairWithLLM(String query, Document doc) {
        String content = doc.getContent();
        String chunkType = getMetaString(doc, "chunk_type");
        String elementName = getMetaString(doc, "element_name");
        String filePath = getMetaString(doc, "file_path");

        // Build a concise context for the LLM — don't send full content
        String contentPreview = content.length() > 400
                ? content.substring(0, 400) + "..." : content;

        String prompt = """
            You are a code relevance scorer. Score how well the code chunk answers the query.
            
            QUERY: %s
            
            CODE CHUNK:
            Type: %s
            Element: %s
            File: %s
            Content:
            %s
            
            Score from 0-10 where:
            10 = directly and completely answers the query
            7-9 = highly relevant, answers most of the query
            4-6 = somewhat relevant, partial answer
            1-3 = tangentially related but not answering
            0 = completely irrelevant
            
            Respond with ONLY a single integer 0-10. Nothing else.
            """.formatted(query, chunkType, elementName,
                filePath != null ? extractFileName(filePath) : "unknown",
                contentPreview);

        String response = chatClient.prompt().user(prompt).call().content().trim();

        return parseScore(response);
    }

    /**
     * Parse score from LLM response.
     * Handles: "7", "Score: 8", "I'd give this a 6/10", etc.
     */
    private double parseScore(String response) {
        Matcher matcher = SCORE_PATTERN.matcher(response.trim());
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        log.warn("Could not parse score from: '{}'", response);
        return 5.0;  // neutral default
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMBINED PIPELINE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Full reranking pipeline: metadata → cross-encoder.
     *
     * For most queries: use metadataRerank only (fast).
     * For debug sessions: use fullRerank (accurate).
     *
     * Strategy:
     * 1. Metadata rerank: narrow 20 candidates → 10
     * 2. Cross-encoder:   narrow 10 candidates → topK
     */
    public List<Document> fullRerank(String query, List<Document> candidates, int topK) {
        if (candidates.size() <= topK) {
            return metadataRerank(query, candidates, topK);
        }

        // Stage 1: metadata rerank to narrow the field
        List<Document> stage1 = metadataRerank(query, candidates,
                Math.min(candidates.size(), 12));

        // Stage 2: cross-encoder on narrowed set
        return crossEncoderRerank(query, stage1, topK);
    }

    /**
     * Boost documents of a specific chunk type to the top.
     * Used for intent-specific retrieval (e.g., CONFIG for config queries).
     */
    public List<Document> boostByType(List<Document> candidates,
                                      String targetType, int topK) {
        List<Document> boosted = new ArrayList<>();
        List<Document> rest    = new ArrayList<>();

        candidates.forEach(doc -> {
            if (targetType.equals(getMetaString(doc, "chunk_type"))) {
                boosted.add(doc);
            } else {
                rest.add(doc);
            }
        });

        boosted.addAll(rest);
        return boosted.stream().limit(topK).collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Extract the Spring AI similarity score from document metadata.
     * Spring AI stores it as "distance" (lower = more similar for cosine).
     * We convert to similarity (higher = more similar).
     */
    private double extractSimilarityScore(Document doc) {
        Object distance = doc.getMetadata().get("distance");
        if (distance instanceof Number num) {
            return 1.0 - num.doubleValue();  // convert distance to similarity
        }
        // If not present, use a neutral score
        return 0.7;
    }

    private String getMetaString(Document doc, String key) {
        Object val = doc.getMetadata().get(key);
        return val != null ? val.toString() : null;
    }

    private boolean matchesAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private String extractFileName(String filePath) {
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    /** Internal scored document record */
    private record ScoredDocument(Document document, double score) {}
}