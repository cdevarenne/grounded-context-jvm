package io.github.cdevarenne.gctx.app.telemetry;

import io.github.cdevarenne.gctx.telemetry.TelemetryEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The cloud-free readback: aggregate the local log into what {@code gctx telemetry summary} prints.
 *
 * <p>Output is byte-identical to the Python implementation, checked against the same committed
 * golden fixture. Two rules make that reachable rather than approximate, and both are chosen for
 * portability rather than statistics:
 *
 * <ul>
 *   <li>percentiles use <b>nearest-rank</b> with no interpolation — any interpolating percentile
 *       drifts the last digit;
 *   <li>percentages <b>truncate</b> and never round — 8 of 26 is 30.77, which truncates to 30 and
 *       rounds to 31, so {@code Math.round} here would produce a different line.
 * </ul>
 *
 * <p>A visible consequence of the second rule: the route-mix percentages sum to 98, not 100,
 * because four buckets each lose their fractional part. That is arithmetic, not a defect.
 */
public final class TelemetrySummary {

    /** Print order for the route mix. {@code DIRECT} last: it is the un-routed path, not a decision. */
    static final List<String> ROUTES = List.of("DETERMINISTIC", "SEMANTIC", "BOTH", "DIRECT");

    private static final int LABEL = 17;
    private static final String GAP = "   ";

    private TelemetrySummary() {
    }

    /**
     * Aggregate a log into the report.
     *
     * <p>The path is rendered exactly as given rather than resolved, so the header names the log
     * the reader asked for.
     */
    public static String render(Path path) {
        List<Map<String, Object>> events = TelemetryLog.read(path);
        String header = "gctx telemetry summary — " + path;
        if (events.isEmpty()) {
            return header + "\nno events recorded yet\n";
        }

        int total = events.size();
        int hit = countHit(events, Boolean.TRUE);
        int miss = countHit(events, Boolean.FALSE);
        int absent = countHit(events, null);
        int precision = hit + miss;
        int refused = (int) events.stream().filter(e -> Boolean.TRUE.equals(e.get("refused"))).count();
        int cleared = countFloor(events, Boolean.TRUE);
        int blocked = countFloor(events, Boolean.FALSE);
        List<Double> both = events.stream()
                .filter(e -> "BOTH".equals(e.get("route")))
                .map(e -> latency(e, "total"))
                .filter(java.util.Objects::nonNull)
                .toList();

        List<String> lines = new ArrayList<>();
        lines.add(header);
        lines.add("events: " + total + "   window: " + events.getFirst().get("@timestamp")
                + " .. " + events.getLast().get("@timestamp"));
        String warning = schemaWarning(events);
        if (warning != null) {
            lines.add(warning);
        }
        lines.add("");
        lines.add(row("route mix", ROUTES.stream()
                .map(name -> {
                    int count = (int) events.stream().filter(e -> name.equals(e.get("route"))).count();
                    return name + " " + count + " (" + pct(count, total) + "%)";
                })
                .toList()));
        lines.add(row("canonical", List.of(
                "hit " + hit,
                "miss " + miss,
                "n/a " + absent + "      miss rate " + pct(miss, precision) + "% of " + precision
                        + " precision queries")));
        lines.add(row("refusals", List.of(refused + " (" + pct(refused, total) + "%)")));
        lines.add(row("floor", List.of(
                "cleared " + cleared,
                "blocked " + blocked + "      (of " + (cleared + blocked) + " semantic-consulted)")));
        lines.add(row("floor scores", List.of(
                "blocked " + scoreSpan(events, Boolean.FALSE),
                "cleared " + scoreSpan(events, Boolean.TRUE))));
        lines.add(latencyRow(events, 50));
        lines.add(latencyRow(events, 95));
        if (!both.isEmpty()) {
            lines.add(row("both-path", List.of(
                    "total p95 " + oneDecimal(percentile(both, 95)) + " ms",
                    "(" + both.size() + " BOTH queries)")));
        }
        return String.join("\n", lines) + "\n";
    }

    private static String row(String label, List<String> cells) {
        return String.format("%-" + LABEL + "s", label) + String.join(GAP, cells);
    }

    /** Percentages truncate and never round, so a port reproduces this line byte for byte. */
    static int pct(int part, int whole) {
        return whole == 0 ? 0 : (int) ((double) part / whole * 100);
    }

    /** Nearest-rank, no interpolation — chosen because it is trivial to reimplement exactly. */
    static double percentile(List<Double> values, int point) {
        List<Double> ordered = new ArrayList<>(values);
        ordered.sort(null);
        int rank = Math.max(1, (int) Math.ceil(point / 100.0 * ordered.size()));
        return ordered.get(rank - 1);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static int countHit(List<Map<String, Object>> events, Boolean state) {
        return (int) events.stream()
                .filter(e -> java.util.Objects.equals(e.get("canonical_hit"), state))
                .count();
    }

    private static int countFloor(List<Map<String, Object>> events, Boolean state) {
        return (int) events.stream()
                .filter(e -> state.equals(e.get("relevance_floor_passed")))
                .count();
    }

    @SuppressWarnings("unchecked")
    private static Double latency(Map<String, Object> event, String name) {
        Object nested = event.get("latency_ms");
        if (!(nested instanceof Map<?, ?> map)) {
            return null;
        }
        return number(((Map<String, Object>) map).get(name));
    }

    /** JSON writes a whole number without a decimal point, so Jackson hands back an Integer. */
    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static String latencyRow(List<Map<String, Object>> events, int point) {
        List<String> cells = new ArrayList<>();
        for (String name : List.of("deterministic", "semantic", "total")) {
            List<Double> values = events.stream()
                    .map(e -> latency(e, name))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            cells.add(name + " " + (values.isEmpty() ? "n/a" : oneDecimal(percentile(values, point))));
        }
        return row("latency p" + point + " ms", cells);
    }

    /** Blocked and cleared score ranges, which is what separates a near miss from off topic. */
    private static String scoreSpan(List<Map<String, Object>> events, Boolean passed) {
        List<Double> scores = events.stream()
                .filter(e -> passed.equals(e.get("relevance_floor_passed")))
                .map(e -> number(e.get("relevance_score")))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (scores.isEmpty()) {
            return "n/a";
        }
        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double max = scores.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        // En dash with spaces, matching the Python format string exactly.
        return oneDecimal(min) + " – " + oneDecimal(max);
    }

    /**
     * Name a log written by a different version rather than quietly under-reporting it.
     *
     * <p>A v1 log has no {@code relevance_score}, so the floor-score row would print {@code n/a}
     * and look like a corpus with no blocked queries instead of a log that predates the field.
     */
    private static String schemaWarning(List<Map<String, Object>> events) {
        Set<Integer> found = new TreeSet<>();
        for (Map<String, Object> event : events) {
            Object version = event.get("schema_version");
            if (version instanceof Number n && n.intValue() != TelemetryEvent.SCHEMA_VERSION) {
                found.add(n.intValue());
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        String versions = found.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElseThrow();
        return "! this log holds schema_version " + versions + " events and the summary expects "
                + TelemetryEvent.SCHEMA_VERSION + " — fields added since may read as absent";
    }
}
