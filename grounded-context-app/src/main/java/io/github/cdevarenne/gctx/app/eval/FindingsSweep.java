package io.github.cdevarenne.gctx.app.eval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Retriever;
import co.elastic.clients.elasticsearch._types.StandardRetriever;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import io.github.cdevarenne.gctx.app.es.ElasticsearchSettings;
import io.github.cdevarenne.gctx.app.es.HybridSemanticSearch;
import io.github.cdevarenne.gctx.provenance.Citation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recompute every corpus-wide number quoted in the Python repo's docs/findings.md.
 *
 * <p>Per-query claims are reproducible with {@code gctx eval --compare}; these are the
 * aggregates. Constants, regexes and length filters match the Python sweep exactly, because the
 * point is not to measure this corpus twice but to check that two implementations reading the
 * same index arrive at the same figures.
 */
public final class FindingsSweep {

    /** Identifier shapes. Separated because the analyzer treats the two joiners differently. */
    static final Pattern HYPHENATED = Pattern.compile("\\b[a-z0-9]+(?:-[a-z0-9]+){1,}\\b");
    static final Pattern UNDERSCORED = Pattern.compile("\\b[a-z0-9]+(?:_[a-z0-9]+){1,}\\b");
    static final Pattern ANY_IDENTIFIER = Pattern.compile("\\b[a-z0-9]+(?:[_-][a-z0-9]+){1,}\\b");

    static final int MIN_UNIQUE_LEN = 10;
    static final int MIN_VISIBLE_LEN = 8;
    static final int TOP_N = 20;

    /** The identifier findings.md walks through, and its defining chunk. */
    static final String MECHANISM_TERM = "rank_constant";
    static final ArmComparison.Target MECHANISM_TARGET =
            new ArmComparison.Target("elastic-rrf", 1);

    /**
     * Tokens findings.md names as examples of the invisible-to-exact set. Reported with their
     * membership so the prose and this output cannot cite different things.
     */
    static final List<String> DOCUMENTED_EXAMPLES = List.of("batch_id", "claude-sonnet-4-6");

    static final List<String> OFF_TOPIC = List.of(
            "How do I bake sourdough bread?",
            "What is the capital of France?",
            "What is the best way to train for a marathon?",
            "Who won the 1998 World Cup?",
            "What is a good recipe for beef bourguignon?",
            "How do I change a flat tire on a bicycle?",
            "What are the symptoms of vitamin D deficiency?",
            "When did the Berlin Wall fall?",
            "How tall is Mount Kilimanjaro?",
            "What is the plot of Hamlet?");

    static final List<String> IN_DOMAIN = List.of(
            "How do I stream responses from the API?",
            "How should I chunk documents for retrieval?",
            "What is reciprocal rank fusion?",
            "How does prompt caching work?",
            "What are the rate limit headers?",
            "How do I use semantic_text?");

    static final List<String> WRONG_ENTITY =
            List.of("What is the price per million tokens of GPT-5?");

    /** One indexed chunk, reduced to what the sweep needs. */
    record Chunk(String sourceId, int chunkIndex, String content) {
        ArmComparison.Target target() {
            return new ArmComparison.Target(sourceId, chunkIndex);
        }
    }

    /** A probe and the two scores that decide Finding 3. */
    public record Probe(String kind, String query, double fused, double sparse) {
    }

    /** How the exact subfield affects one identifier shape. */
    public record ShapeEffect(int total, int improved, int regressed, List<String> examples) {
    }

    private final ElasticsearchClient client;
    private final HybridSemanticSearch search;

    public FindingsSweep(ElasticsearchClient client) {
        this.client = client;
        this.search = new HybridSemanticSearch(client);
    }

    // --- corpus ----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    List<Chunk> allChunks() {
        try {
            SearchResponse<Map> response = client.search(s -> s
                    .index(ElasticsearchSettings.INDEX)
                    .query(q -> q.matchAll(m -> m))
                    .size(1000)
                    .source(src -> src.filter(f -> f.includes("content", "source_id", "chunk_index"))),
                    Map.class);
            List<Chunk> chunks = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                Map<String, Object> source = hit.source();
                chunks.add(new Chunk(
                        String.valueOf(source.get("source_id")),
                        ((Number) source.get("chunk_index")).intValue(),
                        String.valueOf(source.getOrDefault("content", ""))));
            });
            return chunks;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The lexical arm as it would be without the exact-token subfield. */
    private static Retriever contentOnly(String query) {
        return new Retriever(StandardRetriever.of(r -> r.query(
                Query.of(q -> q.match(m -> m.field("content").query(query))))));
    }

    private Integer rankOf(Retriever retriever, ArmComparison.Target target) {
        try {
            @SuppressWarnings("unchecked")
            SearchResponse<Map> response = client.search(s -> s
                    .index(ElasticsearchSettings.INDEX)
                    .retriever(retriever)
                    .size(TOP_N)
                    .source(src -> src.filter(f -> f.includes("source_id", "chunk_index"))),
                    Map.class);
            List<?> hits = response.hits().hits();
            for (int position = 0; position < hits.size(); position++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> source =
                        (Map<String, Object>) ((co.elastic.clients.elasticsearch.core.search.Hit<Map>)
                                hits.get(position)).source();
                if (target.sourceId().equals(String.valueOf(source.get("source_id")))
                        && target.chunkIndex() == ((Number) source.get("chunk_index")).intValue()) {
                    return position + 1;
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long countMatching(String field, String term) {
        try {
            return client.count(c -> c
                    .index(ElasticsearchSettings.INDEX)
                    .query(q -> q.match(m -> m.field(field).query(term)))).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Identifiers appearing in exactly one chunk, mapped to that chunk.
     *
     * <p>Uniqueness is what makes the rank meaningful: there is one right answer to find.
     */
    static Map<String, ArmComparison.Target> uniqueToOneChunk(
            List<Chunk> chunks, Pattern pattern, int minLength) {

        Map<String, Integer> seen = new HashMap<>();
        Map<String, ArmComparison.Target> owner = new HashMap<>();
        for (Chunk chunk : chunks) {
            Set<String> tokens = new LinkedHashSet<>();
            Matcher matcher = pattern.matcher(chunk.content().toLowerCase());
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
            for (String token : tokens) {
                seen.merge(token, 1, Integer::sum);
                owner.put(token, chunk.target());
            }
        }
        Map<String, ArmComparison.Target> unique = new LinkedHashMap<>();
        seen.forEach((token, count) -> {
            if (count == 1 && token.length() > minLength) {
                unique.put(token, owner.get(token));
            }
        });
        return unique;
    }

    // --- the published aggregates ----------------------------------------------------

    /**
     * How many identifiers the {@code content.exact} subfield actually promotes, by shape.
     *
     * <p>"Improved" is a strict rank comparison rather than "the rank changed". For terms unique
     * to one chunk the two are equivalent — the exact clause can only match that chunk, so its
     * rank cannot fall — but asserting the comparison beats relying on that argument, and it
     * lets {@code regressed} stay in the report as a check rather than an assumption.
     */
    public Map<String, ShapeEffect> subfieldEffect(List<Chunk> chunks) {
        Map<String, ShapeEffect> results = new LinkedHashMap<>();
        results.put("hyphenated", effectFor(chunks, HYPHENATED));
        results.put("underscored", effectFor(chunks, UNDERSCORED));
        return results;
    }

    private ShapeEffect effectFor(List<Chunk> chunks, Pattern pattern) {
        Map<String, ArmComparison.Target> unique =
                uniqueToOneChunk(chunks, pattern, MIN_UNIQUE_LEN);
        List<String> improved = new ArrayList<>();
        int regressed = 0;
        for (Map.Entry<String, ArmComparison.Target> entry : unique.entrySet()) {
            double before = rankValue(rankOf(contentOnly(entry.getKey()), entry.getValue()));
            double after = rankValue(
                    rankOf(HybridSemanticSearch.lexical(entry.getKey()), entry.getValue()));
            if (after < before) {
                improved.add(entry.getKey());
            } else if (after > before) {
                regressed++;
            }
        }
        return new ShapeEffect(unique.size(), improved.size(), regressed,
                new TreeSet<>(improved).stream().limit(5).toList());
    }

    /** Absent from the top N sorts as worse than any rank, so comparisons stay total. */
    private static double rankValue(Integer rank) {
        return rank == null ? Double.POSITIVE_INFINITY : rank;
    }

    /** The four numbers findings.md quotes when explaining <em>why</em> the subfield helps. */
    public Map<String, Object> mechanismCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("term", MECHANISM_TERM);
        counts.put("target", MECHANISM_TARGET.sourceId() + ":chunk:" + MECHANISM_TARGET.chunkIndex());
        counts.put("content_matches", countMatching("content", MECHANISM_TERM));
        counts.put("exact_matches", countMatching("content.exact", MECHANISM_TERM));
        counts.put("rank_content_only", rankOf(contentOnly(MECHANISM_TERM), MECHANISM_TARGET));
        counts.put("rank_with_exact",
                rankOf(HybridSemanticSearch.lexical(MECHANISM_TERM), MECHANISM_TARGET));
        return counts;
    }

    /** Identifiers the strict subfield cannot see, because they only appear in punctuation. */
    public Map<String, Object> invisibleToExact(List<Chunk> chunks) {
        Set<String> tokens = new HashSet<>();
        for (Chunk chunk : chunks) {
            Matcher matcher = ANY_IDENTIFIER.matcher(chunk.content().toLowerCase());
            while (matcher.find()) {
                if (matcher.group().length() >= MIN_VISIBLE_LEN) {
                    tokens.add(matcher.group());
                }
            }
        }
        List<String> invisible = new ArrayList<>();
        for (String token : tokens) {
            if (countMatching("content", token) > 0 && countMatching("content.exact", token) == 0) {
                invisible.add(token);
            }
        }
        Map<String, Boolean> documented = new LinkedHashMap<>();
        DOCUMENTED_EXAMPLES.forEach(token -> documented.put(token, invisible.contains(token)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("total", tokens.size());
        report.put("invisible", invisible.size());
        report.put("examples", new TreeSet<>(invisible).stream().limit(5).toList());
        report.put("documented", documented);
        return report;
    }

    /** Fused RRF score against pre-fusion sparse score, for every probe. */
    public List<Probe> probeScores() {
        List<Probe> rows = new ArrayList<>();
        record Group(String kind, List<String> queries) {
        }
        for (Group group : List.of(
                new Group("off-topic", OFF_TOPIC),
                new Group("in-domain", IN_DOMAIN),
                new Group("wrong-entity", WRONG_ENTITY))) {
            for (String query : group.queries()) {
                // The floor is disabled: this measures what the scores are, not what the gate
                // does with them.
                List<Citation> fused = search.search(query, 1, Double.NaN);
                List<Citation> sparse = search.semanticOnly(query, 1);
                rows.add(new Probe(group.kind(), query,
                        round(fused.getFirst().score(), 4),
                        round(sparse.getFirst().score(), 2)));
            }
        }
        return rows;
    }

    private static double round(Double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    /** The whole report, in the shape the JSON output uses. */
    public Map<String, Object> report() {
        List<Chunk> chunks = allChunks();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("chunks", chunks.size());
        report.put("mechanism", mechanismCounts());
        report.put("subfield_effect", subfieldEffect(chunks));
        report.put("invisible_to_exact", invisibleToExact(chunks));
        report.put("floor", HybridSemanticSearch.RELEVANCE_FLOOR);
        report.put("probes", probeScores());
        return report;
    }
}
