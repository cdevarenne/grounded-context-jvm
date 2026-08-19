package io.github.cdevarenne.gctx.app.telemetry;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Project the telemetry log into Elasticsearch.
 *
 * <p>The local log is the source of truth and this builds the queryable view over it — the same
 * relationship the Markdown bundle has to the corpus index, and never the reverse. Nothing here
 * runs on the answer path: recording an answer appends a line, and projecting those lines is a
 * separate, optional step that a missing cluster turns into a message rather than a failure.
 */
public final class TelemetryIndexer {

    public static final String TELEMETRY_INDEX = "grounded-context-telemetry";

    /**
     * Written data-stream-ready — a {@code @timestamp} field and no custom {@code _id} — so it
     * drops into a data-stream index template unchanged. A data stream is the production-correct
     * shape for append-only time-series telemetry; this slice stays a plain index on purpose, with
     * no template and no ILM. Say that in the room rather than building it.
     */
    static final String MAPPING = """
            {"properties":{
              "@timestamp":{"type":"date"},
              "schema_version":{"type":"integer"},
              "query":{"type":"text","fields":{"keyword":{"type":"keyword","ignore_above":512}}},
              "route":{"type":"keyword"},
              "rationale":{"type":"text"},
              "retrieval_path":{"type":"keyword"},
              "canonical_hit":{"type":"boolean"},
              "relevance_floor_passed":{"type":"boolean"},
              "relevance_score":{"type":"float"},
              "refused":{"type":"boolean"},
              "cites":{"type":"integer"},
              "latency_ms":{"properties":{
                "deterministic":{"type":"float"},
                "semantic":{"type":"float"},
                "total":{"type":"float"}
              }}
            }}
            """;

    public static final String UNAVAILABLE =
            "telemetry projection unavailable: no ES_URL / ES_API_KEY."
            + "\nThe log is still the source of truth — `gctx telemetry summary` reads it with no cluster.";

    private final ElasticsearchClient client;

    public TelemetryIndexer(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * Bulk-load every event in the log, returning how many landed.
     *
     * <p>Rebuildable by construction: the log is replayed in full, so {@code --recreate} restores
     * the index from its source rather than from a backup of itself.
     */
    public int project(Path path, String index, boolean recreate) throws IOException {
        List<Map<String, Object>> events = TelemetryLog.read(path);
        if (events.isEmpty()) {
            return 0;
        }
        if (recreate && exists(index)) {
            client.indices().delete(d -> d.index(index));
        }
        if (!exists(index)) {
            client.indices().create(c -> c
                    .index(index)
                    .withJson(new StringReader("{\"mappings\":" + MAPPING + "}")));
        }

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> event : events) {
            // No id: the events are append-only observations, and letting Elasticsearch assign
            // ids is what keeps the mapping promotable to a data stream.
            bulk.operations(op -> op.index(i -> i.index(index).document(event)));
        }
        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            String first = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.error().reason())
                    .findFirst().orElse("unknown");
            throw new IOException("bulk indexing reported errors, first: " + first);
        }
        client.indices().refresh(r -> r.index(index));
        return events.size();
    }

    private boolean exists(String index) throws IOException {
        return client.indices().exists(e -> e.index(index)).value();
    }
}
