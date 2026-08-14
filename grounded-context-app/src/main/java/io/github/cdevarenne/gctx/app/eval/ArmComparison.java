package io.github.cdevarenne.gctx.app.eval;

import io.github.cdevarenne.gctx.app.es.HybridSemanticSearch;
import io.github.cdevarenne.gctx.provenance.Citation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rank of the chunk that defines a term, under each retrieval arm.
 *
 * <p>The point of Q9: neither single arm wins every phrasing of the same question, which is the
 * argument for fusing them rather than picking one. Fusion is a hedge, not a maximum — see
 * {@code rank_window_size}, where it lands between the two arms rather than above both.
 */
public final class ArmComparison {

    /**
     * The chunk that defines each identifier the compare table covers, found by reading the
     * passage rather than by trusting the top hit.
     *
     * <p>{@code rank_window_size} shares a chunk with {@code rank_constant}: both are defined in
     * the same parameter-reference block, so it is a second query against a shared target rather
     * than a fully independent case.
     */
    public static final Map<String, Target> DEFINING_CHUNKS = Map.of(
            "rank_constant", new Target("elastic-rrf", 1),
            "rank_window_size", new Target("elastic-rrf", 1),
            "num_candidates", new Target("elastic-knn", 7),
            "anthropic-ratelimit-tokens-reset", new Target("anthropic-rate-limits", 12));

    public static final Target DEFAULT_TARGET = DEFINING_CHUNKS.get("rank_constant");

    private static final int WINDOW = 20;

    /** A chunk identified the way a citation identifies it. */
    public record Target(String sourceId, int chunkIndex) {
        String locator() {
            return "chunk:" + chunkIndex;
        }
    }

    private final HybridSemanticSearch search;

    public ArmComparison(HybridSemanticSearch search) {
        this.search = search;
    }

    /** Pick the defining chunk to rank, by the identifier the query mentions. */
    public static Target targetFor(String query) {
        String lowered = query.toLowerCase();
        return DEFINING_CHUNKS.entrySet().stream()
                .filter(entry -> lowered.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DEFAULT_TARGET);
    }

    /** Rank under ELSER, BM25 and the hybrid. A null value means outside the top 20. */
    public Map<String, Integer> compare(String query) {
        return compare(query, targetFor(query));
    }

    public Map<String, Integer> compare(String query, Target target) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("elser", rank(search.semanticOnly(query, WINDOW), target));
        ranks.put("bm25", rank(search.lexicalOnly(query, WINDOW), target));
        // The floor is disabled here: this measures ranking, not answerability, and a gated
        // empty result would read as "not in the top 20" rather than "refused".
        ranks.put("hybrid", rank(search.search(query, WINDOW, Double.NaN), target));
        return ranks;
    }

    private static Integer rank(List<Citation> results, Target target) {
        for (int position = 0; position < results.size(); position++) {
            Citation cite = results.get(position);
            if (target.sourceId().equals(cite.sourceId())
                    && target.locator().equals(cite.locator())) {
                return position + 1;
            }
        }
        return null;
    }
}
