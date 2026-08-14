package io.github.cdevarenne.gctx.app.es;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for the corpus indexer.
 *
 * <p>The chunking rule is a contract, not an implementation detail: an index built with
 * different boundaries returns different ranks for the same corpus, and every published number
 * stops meaning anything. These pin the rule, and then check the whole pipeline against the
 * reference index the Python tooling built.
 *
 * <p>That last check is the only part of the parity story that is not a shared oracle. Ranks and
 * scores are computed by Elasticsearch and read by both clients, so agreeing on them is expected.
 * Chunk boundaries are computed <em>by each implementation</em>, so agreeing on those is a real
 * result about the index build.
 */
class CorpusIndexerTest {

    /** The corpus lives in the Python repo; it is fetched, not committed. */
    static final Path DEFAULT_CORPUS =
            Path.of("..", "..", "grounded-context", "corpus", "raw");

    static Path corpus() {
        String configured = System.getenv("GCTX_CORPUS");
        return configured == null || configured.isBlank()
                ? DEFAULT_CORPUS : Path.of(configured);
    }

    // --- the chunking rule, no cluster needed ----------------------------------------

    @Test
    void blank_lines_are_dropped_and_lines_are_stripped() {
        String text = "  alpha  \n\n\n  beta  \n";
        assertThat(CorpusIndexer.chunk(text.repeat(30)).getFirst())
                .startsWith("alpha\nbeta\nalpha")
                .doesNotContain("  ");
    }

    @Test
    void a_passage_shorter_than_the_minimum_is_dropped() {
        // Below MIN_CHUNK_CHARS the text is not quotable as a citation snippet.
        assertThat(CorpusIndexer.chunk("too short to cite")).isEmpty();
    }

    @Test
    void a_new_chunk_starts_before_the_target_is_exceeded() {
        String line = "x".repeat(500) + "\n";
        List<String> chunks = CorpusIndexer.chunk(line.repeat(5));
        assertThat(chunks).hasSize(3);
        // Two 500-char lines plus their separators stay under 1200; a third would exceed it.
        assertThat(chunks.getFirst().length()).isEqualTo(1001);
    }

    @Test
    void every_chunk_respects_the_bounds() {
        String text = ("lorem ipsum dolor sit amet consectetur adipiscing elit\n").repeat(200);
        assertThat(CorpusIndexer.chunk(text)).allSatisfy(c ->
                assertThat(c.length())
                        .isGreaterThanOrEqualTo(CorpusIndexer.MIN_CHUNK_CHARS)
                        .isLessThanOrEqualTo(CorpusIndexer.TARGET_CHUNK_CHARS));
    }

    // --- against the reference corpus and index --------------------------------------

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean referenceAvailable() {
        if (!Files.isDirectory(corpus())) {
            return false;
        }
        try {
            return ElasticsearchConfiguration.client()
                    .map(c -> {
                        try {
                            return c.indices()
                                    .exists(e -> e.index(ElasticsearchSettings.INDEX)).value();
                        } catch (Exception e) {
                            return false;
                        }
                    }).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    static List<CorpusIndexer.Document> computed;
    static Map<String, String> reference;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        if (!referenceAvailable()) {
            return;
        }
        computed = CorpusIndexer.documents(corpus());

        ElasticsearchClient client = ElasticsearchConfiguration.client().orElseThrow();
        SearchResponse<Map> response = client.search(s -> s
                .index(ElasticsearchSettings.INDEX)
                .query(q -> q.matchAll(m -> m))
                .size(1000)
                .source(src -> src.filter(f -> f.includes("content"))), Map.class);

        reference = new HashMap<>();
        response.hits().hits().forEach(hit ->
                reference.put(hit.id(), String.valueOf(hit.source().get("content"))));
    }

    @Test
    @EnabledIf("referenceAvailable")
    void the_indexer_produces_the_same_documents_as_the_reference_index() {
        assertThat(computed).hasSize(reference.size());
        assertThat(computed).extracting(CorpusIndexer.Document::id)
                .containsExactlyInAnyOrderElementsOf(reference.keySet());
    }

    @Test
    @EnabledIf("referenceAvailable")
    void every_chunk_is_byte_identical_to_the_reference() {
        // This is the check that makes the index build independently verified rather than
        // shared: both implementations computed these boundaries from the same source text.
        for (CorpusIndexer.Document document : computed) {
            assertThat(String.valueOf(document.source().get("content")))
                    .as(document.id())
                    .isEqualTo(reference.get(document.id()));
        }
    }

    @Test
    @EnabledIf("referenceAvailable")
    void every_document_carries_the_fields_retrieval_and_provenance_need() {
        assertThat(computed).allSatisfy(document -> assertThat(document.source())
                .containsOnlyKeys("source_id", "title", "url", "provider", "topic",
                        "chunk_index", "fetched_at", "content"));
    }

    /**
     * The gate must reject a corpus that is not the reference one.
     *
     * <p>Without this, a team that indexes its own documents meets a red build on first use:
     * the tests that pin 44 of 149 and the eight compare rows would run against their corpus
     * and fail. Verified by building a small index and asking the gate about it.
     */
    @Test
    @EnabledIf("referenceAvailable")
    void the_reference_gate_rejects_an_adopters_own_corpus() throws Exception {
        ElasticsearchClient client = ElasticsearchConfiguration.client().orElseThrow();
        String scratch = "gctx-gate-check";
        CorpusIndexer indexer = new CorpusIndexer(client);
        try {
            if (indexer.exists(scratch)) {
                indexer.delete(scratch);
            }
            indexer.createIndex(scratch);
            indexer.index(scratch, computed.subList(0, 5));

            assertThat(ReferenceCorpus.matches(client, scratch))
                    .as("a five-document corpus must not pass as the reference")
                    .isFalse();
            assertThat(ReferenceCorpus.matches(client, ElasticsearchSettings.INDEX))
                    .as("the real reference corpus must pass")
                    .isTrue();
        } finally {
            if (indexer.exists(scratch)) {
                indexer.delete(scratch);
            }
        }
    }
}
