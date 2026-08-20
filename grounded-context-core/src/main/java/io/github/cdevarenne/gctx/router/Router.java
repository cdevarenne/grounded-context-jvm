package io.github.cdevarenne.gctx.router;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The router: which engine should answer this question?
 *
 * <p>Rule-based on purpose. The interface is what matters — an LLM classifier can replace
 * {@link #route(String)} without any caller changing, and the rationale stays part of the audit
 * trail either way. When signals conflict or none fire, it returns BOTH rather than guessing;
 * over-querying is cheap, and answering an exact question from a ranker is not.
 */
public final class Router {

    /** Asks for an exact value that must never be ranked. */
    static final List<String> PRECISION_SIGNALS = List.of(
            "context window", "ctx window", "context length",
            "max tokens", "max output", "maximum output", "output limit",
            "endpoint", "rate limit", "model id", "model string", "alias", "version", "exact",
            "how many", "how much", "what is the value of", "price", "pricing", "cost per");

    /** Open-ended: judgement, explanation, or advice. */
    static final List<String> EXPLORATORY_SIGNALS = List.of(
            "how do i", "how should i", "how would i", "best way", "best practice", "explain",
            "why", "recommended", "should i", "trade-off", "tradeoff", "when should",
            "what's the point",
            // router.md lists "difference between" as SEMANTIC, not a comparison signal:
            // explaining how two techniques differ is exposition, not a field lookup.
            "difference between");

    /** Cross-entity: the same exact field asked of two entities. Worth asking both engines. */
    // Comparatives name a cross-entity question without using the word "compare".
    static final List<String> COMPARISON_SIGNALS = List.of(
            "compare", " vs ", " versus ",
            "cheaper", "more expensive", "less expensive", "compared to");

    /** A pinned or aliased Claude model id appearing verbatim in the query. */
    static final Pattern MODEL_ID = Pattern.compile("claude-[a-z0-9.-]+", Pattern.CASE_INSENSITIVE);

    private Router() {
    }

    public static Route route(String query) {
        String text = " " + query.toLowerCase().strip() + " ";

        List<String> precision = matching(PRECISION_SIGNALS, text);
        List<String> exploratory = matching(EXPLORATORY_SIGNALS, text);
        List<String> comparison = matching(COMPARISON_SIGNALS, text);
        Matcher model = MODEL_ID.matcher(text);
        String namedModel = model.find() ? model.group() : null;

        if (!comparison.isEmpty()) {
            return new Route(Route.BOTH, "cross-entity comparison (" + quote(comparison)
                    + ") — exact values are required, so a deterministic miss refuses rather"
                    + " than falling back to passages", true);
        }

        if (!precision.isEmpty() && !exploratory.isEmpty()) {
            return new Route(Route.BOTH, "mixed signals: precision (" + quote(precision)
                    + ") and exploratory (" + quote(exploratory) + ") — safer to query both");
        }

        if (!precision.isEmpty()) {
            String why = "precision phrasing (" + quote(precision) + ")";
            if (namedModel != null) {
                why += " plus a named model (" + namedModel + ")";
            }
            return new Route(Route.DETERMINISTIC, why + " — an exact fact must not be ranked");
        }

        if (!exploratory.isEmpty()) {
            return new Route(Route.SEMANTIC, "exploratory phrasing (" + quote(exploratory)
                    + "), no exact field requested");
        }

        if (namedModel != null) {
            // The router is decoupled from the bundle on purpose, so it cannot tell a query
            // that names a canonical field from one that names nothing. Say only what it
            // actually knows: no precision *signal* fired.
            return new Route(Route.BOTH, "names a model (" + namedModel
                    + ") but matched no precision signal — query both rather than guess the intent");
        }

        return new Route(Route.BOTH,
                "no decisive signal — defaulting to BOTH, which is the safe side");
    }

    private static List<String> matching(List<String> signals, String text) {
        return signals.stream().filter(text::contains).toList();
    }

    /** The two longest matches, quoted — enough to justify the decision without a wall of text. */
    private static String quote(List<String> signals) {
        return signals.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(2)
                .map(signal -> "\"" + signal.strip() + "\"")
                .collect(Collectors.joining(", "));
    }
}
