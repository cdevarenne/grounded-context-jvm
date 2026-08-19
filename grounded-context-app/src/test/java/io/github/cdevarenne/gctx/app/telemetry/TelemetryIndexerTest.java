package io.github.cdevarenne.gctx.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.cdevarenne.gctx.app.es.ElasticsearchConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * The projection: the log replayed into a queryable index.
 *
 * <p>Runs against a scratch index, never the one the dashboard reads, and deletes it afterwards.
 * Skips entirely without credentials — the projection is the optional half of the observability
 * slice, and a clone with no cluster must still go green.
 */
class TelemetryIndexerTest {

    static final String SCRATCH_INDEX = "grounded-context-telemetry-test";

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean clusterReachable() {
        return ElasticsearchConfiguration.client().isPresent();
    }

    private static ElasticsearchClient client() {
        return ElasticsearchConfiguration.client().orElseThrow();
    }

    @AfterEach
    void dropScratchIndex() throws IOException {
        if (!clusterReachable()) {
            return;
        }
        ElasticsearchClient client = client();
        if (client.indices().exists(e -> e.index(SCRATCH_INDEX)).value()) {
            client.indices().delete(d -> d.index(SCRATCH_INDEX));
        }
    }

    private static Path sampleLog(Path dir) throws IOException {
        Path log = dir.resolve("telemetry.ndjson");
        Files.writeString(log,
                TelemetryFixtures.read(TelemetryFixtures.SAMPLE), StandardCharsets.UTF_8);
        return log;
    }

    @Test
    @EnabledIf("clusterReachable")
    void every_event_in_the_log_reaches_the_index(@TempDir Path dir) throws IOException {
        Path log = sampleLog(dir);
        int projected = new TelemetryIndexer(client()).project(log, SCRATCH_INDEX, true);

        assertThat(projected).isEqualTo(TelemetryLog.read(log).size());
        assertThat(client().count(c -> c.index(SCRATCH_INDEX)).count()).isEqualTo(projected);
    }

    @Test
    @EnabledIf("clusterReachable")
    void a_projected_event_keeps_its_field_values(@TempDir Path dir) throws IOException {
        Path log = sampleLog(dir);
        new TelemetryIndexer(client()).project(log, SCRATCH_INDEX, true);

        var response = client().search(s -> s
                .index(SCRATCH_INDEX)
                .query(q -> q.term(t -> t.field("route").value("BOTH")))
                .size(1), Map.class);

        assertThat(response.hits().hits()).isNotEmpty();
        Map<?, ?> source = response.hits().hits().getFirst().source();
        assertThat(source).isNotNull();
        assertThat(source.get("schema_version"))
                .isEqualTo(io.github.cdevarenne.gctx.telemetry.TelemetryEvent.SCHEMA_VERSION);
        assertThat(source.get("latency_ms")).isInstanceOf(Map.class);
    }

    @Test
    @EnabledIf("clusterReachable")
    void an_empty_log_projects_nothing_rather_than_failing(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("telemetry.ndjson");
        Files.writeString(empty, "", StandardCharsets.UTF_8);

        assertThat(new TelemetryIndexer(client()).project(empty, SCRATCH_INDEX, false)).isZero();
    }

    @Test
    @EnabledIf("clusterReachable")
    void recreate_rebuilds_from_the_log_rather_than_appending(@TempDir Path dir) throws IOException {
        Path log = sampleLog(dir);
        TelemetryIndexer indexer = new TelemetryIndexer(client());
        indexer.project(log, SCRATCH_INDEX, true);
        indexer.project(log, SCRATCH_INDEX, true);

        assertThat(client().count(c -> c.index(SCRATCH_INDEX)).count())
                .as("the index is a projection over the log, not an accumulation of runs")
                .isEqualTo(TelemetryLog.read(log).size());
    }
}
