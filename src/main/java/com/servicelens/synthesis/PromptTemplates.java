package com.servicelens.synthesis;

import com.servicelens.retrieval.intent.QueryIntent;
import com.servicelens.synthesis.VerbosityLevel;

/**
 * Intent-aware prompt templates for LLM answer synthesis.
 *
 * <p>Each {@link QueryIntent} maps to a tailored system-prompt fragment that
 * focuses the LLM on the reasoning style most appropriate for that query type
 * (e.g. flow tracing for {@code TRACE_CALL_CHAIN}, diagnosis for
 * {@code DEBUG_ERROR}).  A shared base instruction establishes the ground rules
 * that apply to every intent: stay grounded in the provided context, use plain
 * text, and admit uncertainty rather than hallucinate.
 *
 * <p>This class is intentionally {@code final} and has no instances — all
 * methods are static so they can be used without Spring wiring in tests.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    // ── Base instruction — applies to every intent ────────────────────────────

    /**
     * Minimal system prompt used exclusively for SHORT verbosity.
     * Contains only the grounding rules — no Response Format section so the
     * LLM has no structural template to fall back to.
     */
    private static final String SHORT_SYSTEM = """
            You are ServiceLens, an AI code intelligence assistant for Java/Spring Boot codebases.

            ## Rules
            - Answer based ONLY on the provided code context. Never invent code or classes not shown.
            - Reference class and method names using inline code formatting (backticks).
            - Answer in 3-5 sentences maximum.
            - No headings. No bullet points. No numbered lists. No "Key Takeaway" section.
            - If the answer is a list (e.g. "where is X used?"), write it as a single comma-separated sentence.
            - Stop writing as soon as the question is fully answered.
            """;

    private static final String BASE_SYSTEM = """
            You are ServiceLens, an expert AI code intelligence assistant that helps \
            developers deeply understand Java/Spring Boot codebases.

            ## Core Rules
            - Answer based ONLY on the provided code context. Never invent code, classes, \
            or methods that are not shown.
            - If the context is insufficient, state what is missing and what additional \
            context would be needed.
            - Reference class names, method names, and annotations using \
            inline code formatting (backticks).
            - Use **bold** for key terms and class names on first mention.
            - No filler phrases like "Great question!" or "Let me explain".
            - Use technical language appropriate for a senior developer audience.
            - If the developer requests a specific tone or style (e.g. "explain like a story", \
            "ELI5", "keep it short", "use analogies"), honour that style while keeping all \
            facts strictly grounded in the provided code context.
            """;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the system prompt for the given intent using default (DETAILED) verbosity.
     *
     * @param intent the classified query intent
     * @return fully composed system prompt string
     */
    public static String systemPrompt(QueryIntent intent) {
        return systemPrompt(intent, VerbosityLevel.DETAILED);
    }

    /**
     * Returns the system prompt for the given intent and verbosity level.
     *
     * <p>{@code BASE_SYSTEM} contains only universal grounding rules — no Response Format
     * section.  Format is entirely owned by each {@link #intentGuidance(QueryIntent)} block
     * so every intent produces output that is natural for its query type.</p>
     *
     * <ul>
     *   <li>{@link VerbosityLevel#SHORT} — uses {@code SHORT_SYSTEM} only; no base, no intent guidance.</li>
     *   <li>{@link VerbosityLevel#DETAILED} — {@code BASE_SYSTEM} + intent guidance (default).</li>
     *   <li>{@link VerbosityLevel#DEEP_DIVE} — {@code BASE_SYSTEM} + intent guidance + depth extension block.</li>
     * </ul>
     *
     * @param intent    the classified query intent
     * @param verbosity the desired answer verbosity
     * @return fully composed system prompt string
     */
    public static String systemPrompt(QueryIntent intent, VerbosityLevel verbosity) {
        return switch (verbosity) {
            // SHORT: skip intentGuidance entirely — its structural instructions
            // (Overview / Step-by-step / Key design decisions) override any "be brief"
            // instruction that comes after it. Without intentGuidance the LLM sees only
            // the base rules + the SHORT constraint, which it reliably honours.
            case SHORT -> SHORT_SYSTEM;

            // DETAILED: current behaviour — no verbosity override needed
            case DETAILED -> BASE_SYSTEM + "\n" + intentGuidance(intent);

            // DEEP_DIVE: full structure + extended depth instruction
            case DEEP_DIVE -> BASE_SYSTEM + "\n" + intentGuidance(intent)
                    + "\n" + verbosityOverride(VerbosityLevel.DEEP_DIVE);
        };
    }

    /**
     * Returns the user-turn prompt, embedding the assembled context and query.
     *
     * <p>The context section is delimited with clear markers so the LLM can
     * distinguish retrieved code from the question itself.
     *
     * @param query   the original user question
     * @param context the assembled context string from {@link ContextAssembler}
     * @return formatted user prompt string
     */
    public static String userPrompt(String query, String context) {
        return """
                Below is the retrieved code context from the codebase. Use ONLY this \
                information to answer the developer's question.

                <code_context>
                %s
                </code_context>

                **Developer's Question:** %s

                Provide a detailed, well-structured answer following the format guidelines \
                from your system instructions.""".formatted(context, query);
    }

    // ── Verbosity overrides ───────────────────────────────────────────────────

    private static String verbosityOverride(VerbosityLevel verbosity) {
        return switch (verbosity) {
            case SHORT -> """
                    ## Verbosity Override — SHORT (HIGHEST PRIORITY — overrides all format rules above)
                    Answer in 3-5 sentences maximum. No headings. No bullet points. No numbered lists.
                    No "Key Takeaway" section. If the answer is inherently a list (e.g. "where is X used?"), \
                    express it as a single inline comma-separated sentence.
                    Be direct. Stop as soon as the question is answered.
                    """;
            case DETAILED -> "";  // no override — BASE_SYSTEM + intentGuidance is already DETAILED
            case DEEP_DIVE -> """
                    ## Verbosity Override — DEEP DIVE (extend beyond the direct answer)
                    After answering the direct question, also cover:
                    - Related classes and methods not directly asked about but architecturally relevant
                    - Edge cases, null paths, failure scenarios, and exception handling
                    - Performance or scalability observations visible in the code
                    - Architectural trade-offs and design pattern observations
                    - Any surprising or non-obvious behaviour worth highlighting
                    No length limit — be as thorough as the provided context allows.
                    """;
        };
    }

    // ── Intent-specific guidance ──────────────────────────────────────────────

    private static String intentGuidance(QueryIntent intent) {
        return switch (intent) {
            case FIND_IMPLEMENTATION -> """
                    ## Intent: Explain Implementation
                    Focus on HOW the code works. Structure your answer as:
                    1. **Overview** — what this code does in one sentence
                    2. **Step-by-step breakdown** — walk through the logic in execution order, \
                    referencing `className.methodName()` at each step
                    3. **Key design decisions** — patterns used (e.g. Builder, Strategy), \
                    Spring annotations (`@Transactional`, `@Async`), validation logic
                    4. **Data flow** — what data enters, how it transforms, what comes out
                    """;

            case TRACE_CALL_CHAIN -> """
                    ## Intent: Trace Call Chain
                    Trace the execution path from entry point to final destination:
                    1. **Entry point** — identify the HTTP endpoint or trigger method
                    2. **Each hop** — for every method call in the chain, explain: \
                    which class owns it, what it does, what it passes downstream
                    3. **Terminal operation** — where does the chain end (database, external API, event)?
                    4. **Data transformation** — how does the data shape change at each step?
                    Use a numbered flow like: `Controller.method()` → `Service.method()` → `Repository.method()`
                    """;

            case TRACE_CALLERS -> """
                    ## Intent: Find Callers
                    For each caller found in the context, write one entry:
                    - **`ClassName.methodName()`** — when/why it calls this (e.g. on HTTP request, \
                    scheduled job, event listener); what arguments it passes; what it does with \
                    the return value.
                    List every distinct caller. No numbered sections. If multiple callers belong \
                    to the same class, group them under a **`ClassName`** heading.
                    """;

            case IMPACT_ANALYSIS -> """
                    ## Intent: Impact Analysis
                    Analyse what would break or need changes if this code is modified:
                    1. **Direct dependents** — classes/methods that directly call or reference this code
                    2. **Transitive impacts** — downstream effects through the dependency chain
                    3. **Layer breakdown** — group impacts by layer (Controller → Service → Repository)
                    4. **Risk assessment** — highlight high-risk areas (e.g. shared utilities, \
                    public API contracts, database schema dependencies)
                    """;

            case FIND_CONFIGURATION -> """
                    ## Intent: Explain Configuration
                    For each configuration property shown:
                    - **Property name** and where it is defined
                    - **Purpose** — what behaviour it controls
                    - **Default value** and valid range/options
                    - **Runtime effect** — how changing it affects the application
                    Flag any missing required properties or potentially risky defaults.
                    """;

            case UNDERSTAND_CONTRACT -> """
                    ## Intent: Understand API Contract
                    Explain the contract this code establishes:
                    1. **Inputs** — parameters, their types, and validation constraints
                    2. **Outputs** — return type, response structure, status codes
                    3. **Preconditions** — what must be true before calling this
                    4. **Postconditions** — what is guaranteed after successful execution
                    5. **Error cases** — exceptions thrown, error responses, edge cases
                    6. **Side effects** — database writes, events published, external calls made
                    """;

            case DEBUG_ERROR -> """
                    ## Intent: Debug Error
                    Start immediately with the root cause — no introductory summary or preamble.
                    1. **Root cause** — the most likely reason for the error, stated directly
                    2. **Why it happens** — trace the code path that leads to the failure
                    3. **Evidence** — specific lines or conditions in the context that confirm the diagnosis
                    4. **Fix** — concrete code-level fix(es), ordered by likelihood of success
                    5. **Prevention** — guard or pattern to prevent recurrence
                    """;

            case NULL_SAFETY -> """
                    ## Intent: Null Safety Analysis
                    Analyse null-pointer risks in the shown code:
                    1. **Null sources** — where can null values originate? (method returns, \
                    optional fields, external inputs)
                    2. **Unchecked paths** — which code paths use these values without null checks?
                    3. **Risk severity** — rate each risk (high/medium/low) based on likelihood and impact
                    4. **Fixes** — suggest specific defensive guards (`Optional`, null checks, \
                    `@NonNull` annotations, default values)
                    """;

            case UNDERSTAND_BUSINESS_RULE -> """
                    ## Intent: Explain Business Rule
                    Write in plain English paragraphs, as if explaining in a Slack message \
                    to a product manager. No headings. No numbered lists. No bullet points.
                    Cover: what the rule does in plain language, what conditions trigger it, \
                    what happens when it passes vs. fails, and concrete examples visible in the code.
                    Keep technical jargon minimal — use class and method names as supporting \
                    references only, never as the lead.
                    """;

            case FIND_ENDPOINTS -> """
                    ## Intent: List HTTP Endpoints
                    Always present endpoints as a markdown table — no bullet points or prose.

                    | Method | Path | Auth | Request Body / Params | Response |
                    |---|---|---|---|---|

                    Fill every column from the context. Use `—` if a detail is not shown. \
                    If more than one controller is involved, add a **`ControllerName`** heading \
                    above each table.
                    """;

            case FIND_TESTS -> """
                    ## Intent: Analyse Test Coverage
                    Evaluate the test suite:
                    1. **Covered scenarios** — what happy paths and business rules are tested?
                    2. **Edge cases** — what boundary conditions and error paths are verified?
                    3. **Gaps** — what important scenarios are NOT tested?
                    4. **Quality assessment** — are the tests well-structured? Do they test \
                    behaviour or implementation details?
                    5. **Recommendations** — suggest specific test cases that should be added
                    """;
        };
    }
}
