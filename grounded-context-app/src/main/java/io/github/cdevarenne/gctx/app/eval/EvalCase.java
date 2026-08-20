package io.github.cdevarenne.gctx.app.eval;

import io.github.cdevarenne.gctx.provenance.Envelope;
import java.util.List;

/**
 * One question and the engine that ought to answer it.
 *
 * @param knownDeviation when non-empty, a documented reason this case does not behave as the
 *                       spec says. Declared rather than hidden: a harness that quietly expects
 *                       whatever the code currently does is a rubber stamp.
 */
public record EvalCase(String id, String question, String expected, String note,
                       String knownDeviation) {

    /** A question with no grounded answer anywhere; the guardrail against invention. */
    public static final String REFUSAL = "refusal";

    public EvalCase(String id, String question, String expected) {
        this(id, question, expected, "", "");
    }

    public EvalCase(String id, String question, String expected, String note) {
        this(id, question, expected, note, "");
    }

    public boolean hasKnownDeviation() {
        return knownDeviation != null && !knownDeviation.isBlank();
    }

    /**
     * The set from docs/specs/eval.md — small and illustrative, <b>not</b> a benchmark.
     *
     * <p>Identical to the Python set, question for question, so the two implementations are
     * judged against the same expectations rather than each against its own behaviour.
     */
    public static final List<EvalCase> CASES = List.of(
            new EvalCase("Q1", "What is the exact context window of claude-opus-5?",
                    Envelope.DETERMINISTIC, "canonical field lookup"),
            new EvalCase("Q2", "What is the endpoint path for Anthropic's Messages API?",
                    Envelope.DETERMINISTIC, "resolves through an alias, not the literal id"),
            new EvalCase("Q3", "Which of these models support vision?",
                    Envelope.SEMANTIC, "multi-entity rollup",
                    "eval.md expects a deterministic list. Lookup answers one entity at a time, "
                    + "so a cross-model rollup has no engine and falls through to semantic "
                    + "passages that do not really answer it. docs/compatibility-matrix.md is "
                    + "what answers this today."),
            new EvalCase("Q4", "What is the max output tokens for claude-haiku-4-5?",
                    Envelope.DETERMINISTIC),
            new EvalCase("Q5", "How do I stream responses from the API?", Envelope.SEMANTIC),
            new EvalCase("Q6", "What's the recommended way to do hybrid search in Elasticsearch?",
                    Envelope.SEMANTIC),
            new EvalCase("Q7", "How should I chunk documents for retrieval?", Envelope.SEMANTIC),
            new EvalCase("Q8", "What's the difference between BM25 and vector search?",
                    Envelope.SEMANTIC),
            new EvalCase("Q9", "What does the rank_constant parameter do?",
                    Envelope.SEMANTIC, "the planted proof — see ArmComparison"),
            new EvalCase("Q10", "Compare claude-opus-5 and claude-sonnet-5 on max output tokens.",
                    Envelope.MIXED, "cross-entity: exact hit leads, semantic context follows"),
            new EvalCase("Q11", "What is the price per million tokens of GPT-5?",
                    REFUSAL, "guardrail: absent from both the bundle and the corpus"),
            new EvalCase("Q12", "What is the exact context window of claude-haiku-4-5?",
                    Envelope.DETERMINISTIC, "shows OKF verified / stale_after on a governed fact"),
            // --- paraphrases: the same canonical facts, asked the way a person types them ---
            // Q1-Q12 all name the canonical identifier verbatim, which is how a bug that broke
            // every natural phrasing survived a green suite. These ask for facts the bundle
            // holds without using its vocabulary, so they fail when matching or routing
            // regresses.
            new EvalCase("Q13", "Whats the ctx window for opus",
                    Envelope.DETERMINISTIC, "abbreviated field, bare alias, no punctuation"),
            new EvalCase("Q14", "How big is the Opus 5 context window?",
                    Envelope.DETERMINISTIC, "natural interrogative, spaced alias"),
            new EvalCase("Q15", "What is the context length of Sonnet 5",
                    Envelope.DETERMINISTIC, "field synonym rather than the canonical field name"),
            new EvalCase("Q16", "Haiku 4.5 max output",
                    Envelope.DETERMINISTIC, "dotted version alias, no question form"),
            new EvalCase("Q17", "the Opus model endpoint",
                    Envelope.DETERMINISTIC, "alias plus a one-hop traversal to the endpoint concept"),
            new EvalCase("Q18", "Is Sonnet 5 cheaper than Opus 5?",
                    REFUSAL, "precision exception: a comparison the bundle cannot answer refuses "
                            + "rather than falling back to passages (router.md)"));
}
