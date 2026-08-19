package io.github.cdevarenne.gctx.app.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.RRFRetrieverEntry;
import co.elastic.clients.elasticsearch._types.Retriever;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.github.cdevarenne.gctx.provenance.Citation;
import io.github.cdevarenne.gctx.service.SemanticResult;
import io.github.cdevarenne.gctx.service.SemanticSearch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * The semantic path: BM25 and ELSER, fused with reciprocal rank fusion.
 *
 * <p>Two retrievers run independently over the same text — one lexical, one learned-sparse — and
 * RRF merges their rankings. Neither alone is enough: ELSER has no notion of a literal string and
 * degrades on a bare identifier, while BM25 degrades on a natural-language sentence where the
 * identifier is diluted by common words.
 *
 * <p>Constants match the Python implementation exactly. They are not tuning knobs here — the two
 * implementations query the same index, so a divergence in ranks means one of them is wrong.
 */
public final class HybridSemanticSearch implements SemanticSearch {

    /** How much influence lower-ranked documents keep; a higher value flattens the curve. */
    public static final int RANK_CONSTANT = 20;

    /** How deep each retriever is read before fusion. */
    public static final int RANK_WINDOW_SIZE = 50;

    public static final int DEFAULT_SIZE = 5;
    public static final int SNIPPET_CHARS = 320;

    /**
     * Minimum pre-fusion sparse score for a query to count as answerable at all.
     *
     * <p>Chosen from probes against this corpus, not tuned on a labeled set: a guardrail, not a
     * classifier. The number is a property of this index — re-chunk, re-index, or change the
     * inference model and it means nothing. What ports is the method, never the constant.
     */
    public static final double RELEVANCE_FLOOR = 8.0;

    public static final double EXACT_TOKEN_BOOST = 3.0;

    public static final String METHOD = "hybrid(bm25+elser,rrf)";
    public static final String METHOD_LEXICAL = "bm25";
    public static final String METHOD_SEMANTIC = "elser";

    private final ElasticsearchClient client;
    private final double floor;

    public HybridSemanticSearch(ElasticsearchClient client) {
        this(client, RELEVANCE_FLOOR);
    }

    public HybridSemanticSearch(ElasticsearchClient client, double floor) {
        this.client = client;
        this.floor = floor;
    }

    // --- retriever construction ------------------------------------------------------

    /**
     * BM25 over the analyzed text, plus the whitespace-tokenized subfield.
     *
     * <p>The two clauses fail in opposite directions, which is why both are queried. The standard
     * analyzer strips punctuation and splits on hyphens (it keeps underscores), so a code
     * sample's {@code "rank_constant":} collapses onto a prose mention and the chunk that
     * *defines* a term competes with every chunk that merely uses it. The exact subfield keeps
     * punctuation and hyphens, which tells those apart — and which also blinds it to any
     * identifier the corpus only ever writes inside quotes.
     */
    public static Retriever lexical(String query) {
        Query bool = Query.of(q -> q.bool(b -> b
                .should(s -> s.match(m -> m.field("content").query(query)))
                .should(s -> s.match(m -> m.field("content.exact")
                        .query(query)
                        .boost((float) EXACT_TOKEN_BOOST)))));
        return new Retriever(co.elastic.clients.elasticsearch._types.StandardRetriever
                .of(r -> r.query(bool)));
    }

    /** ELSER over the {@code semantic_text} field, via the preconfigured inference endpoint. */
    public static Retriever sparse(String query) {
        Query semantic = Query.of(q -> q.semantic(s -> s.field("semantic").query(query)));
        return new Retriever(co.elastic.clients.elasticsearch._types.StandardRetriever
                .of(r -> r.query(semantic)));
    }

    /** The RRF retriever body: BM25 and ELSER fused. */
    public static Retriever hybrid(String query) {
        return new Retriever(co.elastic.clients.elasticsearch._types.RRFRetriever.of(r -> r
                .retrievers(
                        RRFRetrieverEntry.of(e -> e.retriever(lexical(query))),
                        RRFRetrieverEntry.of(e -> e.retriever(sparse(query))))
                .rankConstant(RANK_CONSTANT)
                .rankWindowSize(RANK_WINDOW_SIZE)));
    }

    // --- searching -------------------------------------------------------------------

    @Override
    public List<Citation> search(String query, int size) {
        return search(query, size, floor);
    }

    /**
     * Run the hybrid search and return citations, best first.
     *
     * <p>Returns nothing when the floor is not cleared — an empty list becomes the refusal, and
     * citing an irrelevant passage would be worse than admitting there is no grounded answer.
     * Pass a NaN floor to disable the gate, which is what the arm-comparison tools do.
     */
    public List<Citation> search(String query, int size, double relevanceFloor) {
        if (!Double.isNaN(relevanceFloor) && !isRelevant(query, relevanceFloor)) {
            return List.of();
        }
        return run(hybrid(query), query, size, METHOD);
    }

    /**
     * Probe whether anything in the index genuinely matches, before fusing.
     *
     * <p>RRF scores cannot answer this. They are summed reciprocal ranks, so the top hit scores
     * about the same whether it is a perfect match or the least bad of hundreds of irrelevant
     * chunks. The pre-fusion sparse score keeps the magnitude, so that is what the floor reads.
     */
    public boolean isRelevant(String query, double relevanceFloor) {
        return floorVerdict(query, relevanceFloor).cleared();
    }

    /** The floor verdict and the score behind it. A null score means nothing came back at all. */
    record FloorVerdict(boolean cleared, Double score) {
    }

    private FloorVerdict floorVerdict(String query, double relevanceFloor) {
        List<Citation> top = semanticOnly(query, 1);
        if (top.isEmpty()) {
            return new FloorVerdict(false, null);
        }
        double score = top.getFirst().score() == null ? 0.0 : top.getFirst().score();
        return new FloorVerdict(score >= relevanceFloor, score);
    }

    /**
     * Citations plus what the floor did — the signal the answer envelope cannot carry.
     *
     * <p>The probe runs here rather than inside {@link #search} because its verdict is telemetry,
     * not retrieval. Both calls together are the same two round trips {@code search} already makes
     * on its own: the probe is not a new cost, only a visible one.
     *
     * <p>A query at 7.9 against a floor of 8.0 is a corpus gap — in domain and not yet answerable.
     * One at 1.7 is off topic and always will be. Both refuse, and only the score tells them apart
     * afterwards, which is why it is reported alongside the boolean.
     */
    @Override
    public SemanticResult probe(String query, int size) {
        if (Double.isNaN(floor)) {
            // The gate is disabled, so there is no verdict to report — absent, not blocked.
            return new SemanticResult(run(hybrid(query), query, size, METHOD), null, null);
        }
        FloorVerdict verdict = floorVerdict(query, floor);
        if (!verdict.cleared()) {
            return new SemanticResult(List.of(), false, verdict.score());
        }
        return new SemanticResult(
                run(hybrid(query), query, size, METHOD), true, verdict.score());
    }

    /** BM25 alone — one comparison arm for the retrieval-arm table. */
    public List<Citation> lexicalOnly(String query, int size) {
        return run(lexical(query), query, size, METHOD_LEXICAL);
    }

    /** ELSER alone — the arm that plausibly-but-wrongly answers an exact-token question. */
    public List<Citation> semanticOnly(String query, int size) {
        return run(sparse(query), query, size, METHOD_SEMANTIC);
    }

    private List<Citation> run(Retriever retriever, String query, int size, String method) {
        try {
            SearchResponse<Map> response = client.search(s -> s
                    .index(ElasticsearchSettings.INDEX)
                    .retriever(retriever)
                    .size(size), Map.class);
            return response.hits().hits().stream()
                    .map(hit -> citation(hit, method))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Build a citation from one hit, key-for-key identical to a deterministic one. */
    @SuppressWarnings("unchecked")
    static Citation citation(Hit<Map> hit, String method) {
        Map<String, Object> source = hit.source() == null ? Map.of() : hit.source();
        String content = String.valueOf(source.getOrDefault("content", ""));
        String snippet = content.substring(0, Math.min(SNIPPET_CHARS, content.length())).strip();

        return new Citation(
                Citation.SEMANTIC,
                String.valueOf(source.get("source_id")),
                source.get("url") == null ? null : String.valueOf(source.get("url")),
                "chunk:" + source.get("chunk_index"),
                method,
                hit.score(),
                // OKF lifecycle belongs to the canonical layer; a fetched page carries only the
                // date it was retrieved, which is what provenance.md means by <index_time>.
                source.get("fetched_at") == null ? null : String.valueOf(source.get("fetched_at")),
                null,
                null,
                null,
                false,
                List.of(),
                snippet);
    }
}
