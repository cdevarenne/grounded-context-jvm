package io.github.cdevarenne.gctx.app.es;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.cdevarenne.gctx.provenance.Citation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Live cluster tests for the hybrid path.
 *
 * <p>Skipped without credentials so a fresh clone still builds. What they pin is the claim that
 * makes this port worth doing: Java and Python query the same index with the same constants, so
 * the ranks and scores must agree. A divergence here means one implementation is wrong and the
 * published findings are in question — which is a much better failure than noticing in a demo.
 */
@EnabledIf("indexReachable")
class HybridSemanticSearchTest {

    static final String TOKEN = "rank_constant";
    static final String SENTENCE = "What does the rank_constant parameter do?";
    static final String DEFINING_SOURCE = "elastic-rrf";
    static final String DEFINING_CHUNK = "chunk:1";

    static HybridSemanticSearch search;

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean indexReachable() {
        Optional<ElasticsearchSettings> settings = ElasticsearchSettings.discover();
        if (settings.isEmpty()) {
            return false;
        }
        try {
            ElasticsearchClient client = ElasticsearchConfiguration.connect(settings.get());
            return client.indices().exists(e -> e.index(ElasticsearchSettings.INDEX)).value();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void connect() {
        search = new HybridSemanticSearch(
                ElasticsearchConfiguration.connect(ElasticsearchSettings.discover().orElseThrow()));
    }

    /** Rank of the chunk that defines the term, or -1 when it is outside the window. */
    private static int rankOfDefiningChunk(List<Citation> results) {
        for (int position = 0; position < results.size(); position++) {
            Citation cite = results.get(position);
            if (DEFINING_SOURCE.equals(cite.sourceId()) && DEFINING_CHUNK.equals(cite.locator())) {
                return position + 1;
            }
        }
        return -1;
    }

    @Test
    void hybrid_returns_grounded_citations() {
        List<Citation> results = search.search("How should I chunk documents for retrieval?", 3);
        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(cite -> {
            assertThat(cite.sourceUrl()).isNotBlank();
            assertThat(cite.method()).isEqualTo(HybridSemanticSearch.METHOD);
            assertThat(cite.path()).isEqualTo(Citation.SEMANTIC);
            // A fetched page has no OKF trust tier; claiming one would be a lie.
            assertThat(cite.trustTier()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {TOKEN, SENTENCE})
    void hybrid_ranks_the_defining_chunk_first_for_both_phrasings(String query) {
        assertThat(rankOfDefiningChunk(search.search(query, 20))).isEqualTo(1);
    }

    @Test
    void the_published_arm_ranks_reproduce_exactly() {
        // docs/findings.md in the Python repo publishes these. Same index, same constants,
        // so the same numbers must come out of this implementation.
        assertThat(rankOfDefiningChunk(search.semanticOnly(TOKEN, 20))).isEqualTo(5);
        assertThat(rankOfDefiningChunk(search.lexicalOnly(TOKEN, 20))).isEqualTo(1);

        assertThat(rankOfDefiningChunk(search.semanticOnly(SENTENCE, 20))).isEqualTo(2);
        assertThat(rankOfDefiningChunk(search.lexicalOnly(SENTENCE, 20))).isEqualTo(3);
    }

    @Test
    void neither_single_arm_wins_both_phrasings() {
        // The actual finding: which arm degrades depends on how the question is phrased.
        assertThat(rankOfDefiningChunk(search.lexicalOnly(SENTENCE, 20))).isGreaterThan(1);
        assertThat(rankOfDefiningChunk(search.semanticOnly(TOKEN, 20))).isGreaterThan(1);
    }

    @Test
    void fusion_loses_to_bm25_on_rank_window_size() {
        // The counter-example that narrows the claim from "hybrid wins" to "hybrid is never
        // worse than the weaker arm". If this stops holding, the finding must be widened.
        for (String query : List.of("rank_window_size", "What does the rank_window_size parameter do?")) {
            int bm25 = rankOfDefiningChunk(search.lexicalOnly(query, 20));
            int hybrid = rankOfDefiningChunk(search.search(query, 20));
            assertThat(bm25).isLessThan(hybrid);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"How do I bake sourdough bread?", "What is the capital of France?"})
    void out_of_domain_questions_return_nothing(String query) {
        // Citing an irrelevant passage is worse than admitting there is no grounded answer.
        assertThat(search.search(query, 5)).isEmpty();
    }

    @Test
    void the_floor_is_what_rejects_them_not_the_absence_of_hits() {
        List<Citation> ungated = search.search("How do I bake sourdough bread?", 5, Double.NaN);
        assertThat(ungated).isNotEmpty();
        assertThat(ungated).allSatisfy(cite -> assertThat(cite.score()).isNotNull());
    }

    @Test
    void rrf_score_cannot_separate_relevant_from_irrelevant() {
        // Why the floor reads a pre-fusion score: RRF encodes rank, not match quality.
        Citation onTopic = search.search("How do I stream responses from the API?", 1).getFirst();
        Citation offTopic = search.search("What is the capital of France?", 1, Double.NaN).getFirst();
        assertThat(Math.abs(onTopic.score() - offTopic.score())).isLessThan(0.02);
    }

    @Test
    void the_pre_fusion_score_is_what_separates_them() {
        assertThat(search.isRelevant("What is reciprocal rank fusion?",
                HybridSemanticSearch.RELEVANCE_FLOOR)).isTrue();
        assertThat(search.isRelevant("How do I bake sourdough bread?",
                HybridSemanticSearch.RELEVANCE_FLOOR)).isFalse();
    }
}
