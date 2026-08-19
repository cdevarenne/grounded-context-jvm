package io.github.cdevarenne.gctx.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import io.github.cdevarenne.gctx.service.SemanticSearch;
import io.github.cdevarenne.gctx.telemetry.TelemetryEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local log: the sink that keeps the zero-cloud guarantee true.
 *
 * <p>With no Elasticsearch configured the event still lands on disk, the deterministic path still
 * makes no network call, and the readback still works — which is what makes the observability
 * story runnable in an offline demo rather than only against a cluster.
 */
class NdjsonTelemetrySinkTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 8, 20);
    static final Path BUNDLE = Path.of("..", "knowledge");

    private static TelemetryEvent event() {
        return TelemetryEvent.from(
                "a query", Envelope.refusal(Envelope.DETERMINISTIC, null), 1.0, null, 1.0,
                null, null);
    }

    @Test
    void it_appends_one_line_per_event(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("telemetry.ndjson");
        NdjsonTelemetrySink sink = new NdjsonTelemetrySink(log);
        sink.emit(event());
        sink.emit(event());

        assertThat(Files.readAllLines(log)).hasSize(2);
        assertThat(TelemetryLog.read(log)).hasSize(2);
    }

    @Test
    void it_creates_its_directory(@TempDir Path dir) {
        Path log = dir.resolve("var").resolve("telemetry.ndjson");
        new NdjsonTelemetrySink(log).emit(event());

        assertThat(log).exists();
    }

    @Test
    void what_it_writes_is_what_the_log_reads_back(@TempDir Path dir) {
        Path log = dir.resolve("telemetry.ndjson");
        new NdjsonTelemetrySink(log).emit(event());

        Map<String, Object> written = TelemetryLog.read(log).getFirst();
        assertThat(written.keySet()).containsExactlyElementsOf(event().asMap().keySet());
        assertThat(written).containsEntry("route", TelemetryEvent.DIRECT);
        assertThat(written).containsEntry("refused", true);
    }

    @Test
    void an_unwritable_path_raises_here_so_the_guard_is_the_only_guard(@TempDir Path dir)
            throws IOException {
        // The sink is deliberately not self-silencing: Telemetry.record swallows, and a second
        // guard here would hide a sink that had stopped working from its own tests.
        Path blocked = dir.resolve("occupied");
        Files.writeString(blocked, "not a directory");

        assertThatThrownBy(() -> new NdjsonTelemetrySink(blocked.resolve("telemetry.ndjson"))
                .emit(event()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void an_answer_still_lands_in_the_log_with_no_cluster(@TempDir Path dir) {
        // No Elasticsearch is wired at all: UNAVAILABLE is the engine, and the log still fills.
        Path log = dir.resolve("var").resolve("telemetry.ndjson");
        GroundedContextService service = new GroundedContextService(
                Bundle.load(BUNDLE), SemanticSearch.UNAVAILABLE, new NdjsonTelemetrySink(log));

        service.lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null);
        service.ask("How should I chunk documents for retrieval?", AS_OF);

        List<Map<String, Object>> events = TelemetryLog.read(log);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).containsEntry("route", TelemetryEvent.DIRECT);
        assertThat(events.getLast()).containsEntry("route", "SEMANTIC");
        assertThat(TelemetrySummary.render(log)).contains("events: 2");
    }

    @Test
    void an_explicit_path_wins_over_the_default() {
        assertThat(NdjsonTelemetrySink.resolve("/tmp/somewhere.ndjson"))
                .isEqualTo(Path.of("/tmp/somewhere.ndjson"));
    }

    @Test
    void the_default_does_not_depend_on_the_working_directory() {
        // Anchored to the repo, not to wherever an MCP client happened to spawn the server.
        // endsWithRaw, not endsWith: the latter resolves through the file system, and the point
        // here is where the path points, not whether a log happens to exist yet.
        assertThat(NdjsonTelemetrySink.resolve(null))
                .isAbsolute()
                .endsWithRaw(Path.of(NdjsonTelemetrySink.DIRECTORY, NdjsonTelemetrySink.FILE_NAME));
    }
}
