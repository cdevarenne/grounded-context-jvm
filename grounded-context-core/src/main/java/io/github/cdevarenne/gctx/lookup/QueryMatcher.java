package io.github.cdevarenne.gctx.lookup;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.bundle.Concept;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Map a natural-language question onto a concept id and a canonical field name.
 *
 * <p>Substring matching, deliberately. This is the cheap half of routing: it decides what an
 * exact lookup would be *for*, and the lookup itself is what decides whether the fact exists.
 * Guessing here is safe because a wrong guess produces a miss, never a wrong answer.
 */
public final class QueryMatcher {

    /**
     * Phrasings that do not contain the field name. Matched longest-phrase-first so
     * "max output" wins over a bare "output" substring.
     */
    public static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("context window", "context_window_tokens"),
            Map.entry("max output", "max_output_tokens"),
            Map.entry("maximum output", "max_output_tokens"),
            Map.entry("output tokens", "max_output_tokens"),
            Map.entry("output limit", "max_output_tokens"),
            Map.entry("model id", "model_string"),
            Map.entry("model string", "model_string"),
            Map.entry("alias", "api_alias"),
            Map.entry("endpoint", "default_endpoint"),
            Map.entry("input price", "input_price_per_mtok_usd"),
            Map.entry("output price", "output_price_per_mtok_usd"),
            Map.entry("input cost", "input_price_per_mtok_usd"),
            Map.entry("output cost", "output_price_per_mtok_usd"),
            Map.entry("adaptive thinking", "adaptive_thinking"),
            Map.entry("extended thinking", "extended_thinking"),
            Map.entry("vision", "vision"),
            Map.entry("images", "vision"));

    /** Canonical keys that hold an id-like string a question might quote verbatim. */
    private static final Set<String> ID_FIELDS = Set.of("model_string", "api_alias");

    private QueryMatcher() {
    }

    /**
     * Match a query against concept ids, aliases, and canonical id strings.
     *
     * <p>Longest match wins, so {@code claude-haiku-4-5-20251001} is not shadowed by
     * {@code claude-haiku-4-5}.
     */
    public static Optional<String> findEntity(Bundle bundle, String text) {
        String lowered = text.toLowerCase();
        String best = null;
        int bestLength = -1;

        for (Concept concept : bundle) {
            Set<String> needles = new LinkedHashSet<>();
            needles.add(concept.id());
            needles.addAll(concept.aliases());
            ID_FIELDS.stream()
                    .filter(concept.canonical()::containsKey)
                    .map(key -> String.valueOf(concept.canonical().get(key)))
                    .forEach(needles::add);

            for (String needle : needles) {
                if (lowered.contains(needle.toLowerCase()) && needle.length() > bestLength) {
                    bestLength = needle.length();
                    best = concept.id();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Match a query against canonical field names, directly or by synonym. */
    public static Optional<String> findField(Bundle bundle, String text, String entityId) {
        String lowered = text.toLowerCase();

        Set<String> fields = new LinkedHashSet<>();
        if (entityId == null) {
            bundle.forEach(concept -> fields.addAll(concept.canonical().keySet()));
        } else {
            bundle.get(entityId).ifPresent(c -> fields.addAll(c.canonical().keySet()));
            bundle.linked(entityId).forEach(c -> fields.addAll(c.canonical().keySet()));
        }

        String best = null;
        int bestLength = -1;
        for (String name : fields) {
            for (String phrase : new String[] {name, name.replace('_', ' ')}) {
                if (lowered.contains(phrase.toLowerCase()) && phrase.length() > bestLength) {
                    bestLength = phrase.length();
                    best = name;
                }
            }
        }
        if (best != null) {
            return Optional.of(best);
        }

        // Longest synonym first, so a longer phrase is never pre-empted by a substring of it.
        Map<String, String> byLengthDescending = new TreeMap<>(
                Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
        byLengthDescending.putAll(new LinkedHashMap<>(SYNONYMS));
        for (Map.Entry<String, String> entry : byLengthDescending.entrySet()) {
            if (lowered.contains(entry.getKey()) && fields.contains(entry.getValue())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }
}
