package io.github.cdevarenne.gctx.app.es;

/**
 * Tells a reference index apart from an adopter's own.
 *
 * <p>Several tests pin literals measured against the curated corpus: 44 of 149, all eight rows of
 * the arm comparison, seventeen probe scores. Those numbers describe <em>that</em> corpus. A team
 * that indexes its own documents and runs the suite would see them fail, and a red build on
 * first use is the worst possible greeting for a reference implementation.
 *
 * <p>So the corpus-specific tests are gated on this, and skip on any other index. Behavioural
 * tests — refusal, routing, provenance shape, the citation contract — are not gated, because
 * they must hold on any corpus.
 */
public final class ReferenceCorpus {

    /** The reference corpus is 25 curated pages, which chunk to exactly this many documents. */
    public static final long CHUNK_COUNT = 320;

    /** A chunk whose presence identifies the reference corpus rather than merely its size. */
    static final String MARKER_SOURCE = "elastic-rrf";
    static final int MARKER_CHUNK = 1;

    private ReferenceCorpus() {
    }

    /**
     * True when the reachable index looks like the reference corpus.
     *
     * <p>Checks the document count and one known chunk. Not a cryptographic identity — it only
     * has to be strong enough that an unrelated corpus does not pass by accident.
     */
    public static boolean isPresent() {
        try {
            return ElasticsearchConfiguration.client()
                    .map(client -> matches(client, ElasticsearchSettings.INDEX))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether one named index looks like the reference corpus. Exposed so the gate is testable. */
    static boolean matches(
            co.elastic.clients.elasticsearch.ElasticsearchClient client, String index) {
        try {
            if (!client.indices().exists(e -> e.index(index)).value()) {
                return false;
            }
            if (client.count(c -> c.index(index)).count() != CHUNK_COUNT) {
                return false;
            }
            long marker = client.count(c -> c
                    .index(index)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("source_id").value(MARKER_SOURCE)))
                            .must(m -> m.term(t -> t.field("chunk_index").value(MARKER_CHUNK)))
                    ))).count();
            return marker == 1;
        } catch (Exception e) {
            return false;
        }
    }
}
