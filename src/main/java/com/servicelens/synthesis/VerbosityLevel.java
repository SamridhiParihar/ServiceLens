package com.servicelens.synthesis;

/**
 * Controls how verbose the LLM's answer should be for a given query.
 *
 * <p>Passed as part of the {@code POST /api/ask} request body.  The selected
 * level is injected into the system prompt by {@link PromptTemplates} as a
 * high-priority override that takes precedence over the intent-specific format
 * guidelines.</p>
 *
 * <ul>
 *   <li>{@link #SHORT}     — 3-5 sentences, no headings or lists.  Best for quick lookups.</li>
 *   <li>{@link #DETAILED}  — Current default behaviour: numbered steps, sections, Key Takeaway.</li>
 *   <li>{@link #DEEP_DIVE} — Everything in DETAILED plus related classes, edge cases, caveats,
 *                            architectural observations.  No length limit.</li>
 * </ul>
 */
public enum VerbosityLevel {
    SHORT,
    DETAILED,
    DEEP_DIVE
}
