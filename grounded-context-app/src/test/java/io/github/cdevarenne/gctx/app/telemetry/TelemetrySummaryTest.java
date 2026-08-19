package io.github.cdevarenne.gctx.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The numbers behind the observability claims, checkable without a cluster.
 *
 * <p>The fixture and the expected output are the Python repo's, copied here so this repo stands
 * alone and drift-guarded by {@link TelemetryParityTest}. Reproducing them byte for byte is the
 * parity claim: two implementations reading the same log must print the same report.
 */
class TelemetrySummaryTest {

    /**
     * The header names the log the reader asked for, so it carries an absolute temporary path here
     * and {@code var/telemetry.ndjson} in the committed golden. Java cannot change its working
     * directory, so the header is asserted separately rather than by chdir — and asserted against
     * the same construction the golden's first line uses, not skipped.
     */
    @Test
    void the_summary_reproduces_the_committed_golden_output(@TempDir Path dir) throws IOException {
        Path log = TelemetryFixtures.sampleLog(dir);
        String rendered = TelemetrySummary.render(log);

        List<String> golden = TelemetryFixtures.read(TelemetryFixtures.GOLDEN).lines().toList();
        assertThat(rendered.lines().findFirst()).hasValue("gctx telemetry summary — " + log);
        assertThat(golden.getFirst()).isEqualTo("gctx telemetry summary — var/telemetry.ndjson");
        assertThat(rendered.lines().skip(1).toList())
                .as("every aggregate line must match the Python output exactly")
                .containsExactlyElementsOf(golden.subList(1, golden.size()));
    }

    @Test
    void percentages_truncate_rather_than_round() {
        // 12 of 26 is 46.15 and agrees either way; 8 of 26 is 30.77 and does not.
        assertThat(TelemetrySummary.pct(12, 26)).isEqualTo(46);
        assertThat(TelemetrySummary.pct(8, 26)).isEqualTo(30);
        assertThat(TelemetrySummary.pct(1, 0)).isZero();
    }

    @Test
    void percentiles_use_nearest_rank_without_interpolating() {
        List<Double> values = List.of(1.0, 2.0, 3.0, 4.0);
        // An interpolating p50 would give 2.5; nearest-rank takes ceil(0.5 * 4) = 2 -> xs[1].
        assertThat(TelemetrySummary.percentile(values, 50)).isEqualTo(2.0);
        assertThat(TelemetrySummary.percentile(values, 95)).isEqualTo(4.0);
        assertThat(TelemetrySummary.percentile(List.of(7.0), 50)).isEqualTo(7.0);
    }

    @Test
    void a_missing_log_is_not_an_error(@TempDir Path dir) {
        Path absent = dir.resolve("var").resolve("telemetry.ndjson");
        assertThat(TelemetrySummary.render(absent))
                .isEqualTo("gctx telemetry summary — " + absent + "\nno events recorded yet\n");
    }

    @Test
    void an_older_log_is_named_rather_than_under_reported(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("telemetry.ndjson");
        Files.writeString(log, """
                {"@timestamp":"2026-08-18T15:00:00.000Z","schema_version":1,"query":"q",\
                "route":"DIRECT","rationale":"r","retrieval_path":"deterministic",\
                "canonical_hit":true,"relevance_floor_passed":null,"refused":false,"cites":1,\
                "latency_ms":{"deterministic":1.0,"semantic":null,"total":1.0}}
                """, StandardCharsets.UTF_8);

        assertThat(TelemetrySummary.render(log))
                .contains("! this log holds schema_version 1 events and the summary expects 2");
    }

    @Test
    void a_current_log_carries_no_warning(@TempDir Path dir) throws IOException {
        assertThat(TelemetrySummary.render(TelemetryFixtures.sampleLog(dir)))
                .doesNotContain("! this log holds schema_version");
    }
}
