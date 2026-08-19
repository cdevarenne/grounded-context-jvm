package io.github.cdevarenne.gctx.telemetry;

import io.github.cdevarenne.gctx.provenance.Citation;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.router.Route;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event per answered query, per {@code docs/specs/observability.md}.
 *
 * <p>The schema is the contract between this port and the Python reference — the transport may
 * differ, the document may not. {@link #asMap()} therefore fixes both the key names and their
 * order, and {@code TelemetrySchemaParityTest} compares them to the spec.
 *
 * <p>Every field that can be read off a finished envelope is read off it rather than recomputed,
 * so the telemetry describes the answer that was actually returned and cannot disagree with it.
 *
 * @param canonicalHit {@code null} is not {@code false}. A precision query the bundle could not
 *                     answer and a query that never asked for a canonical field are different
 *                     facts, and the curation-backlog number is only honest if they do not
 *                     collapse into one another.
 */
public record TelemetryEvent(
        String timestamp,
        int schemaVersion,
        String query,
        String route,
        String rationale,
        String retrievalPath,
        Boolean canonicalHit,
        Boolean relevanceFloorPassed,
        Double relevanceScore,
        boolean refused,
        int cites,
        Double deterministicMs,
        Double semanticMs,
        Double totalMs) {

    /** Bump on any field change; the summary and the indexer assert on it. */
    public static final int SCHEMA_VERSION = 2;

    /** A lookup that named its entity and field outright, so no router ran. */
    public static final String DIRECT = "DIRECT";
    public static final String DIRECT_RATIONALE = "explicit entity+field lookup, no routing";

    /**
     * The Python reference formats with {@code timespec="milliseconds"}, which always writes three
     * fractional digits. {@code Instant.toString()} drops them when they are zero, so the format is
     * stated rather than inherited.
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Build one event from a finished envelope and the timings taken around it. */
    public static TelemetryEvent from(
            String query,
            Envelope envelope,
            Double deterministicMs,
            Double semanticMs,
            double totalMs,
            Boolean relevanceFloorPassed,
            Double relevanceScore) {
        Route router = envelope.router();
        return new TelemetryEvent(
                TIMESTAMP.format(Instant.now()),
                SCHEMA_VERSION,
                query,
                router == null ? DIRECT : router.route(),
                router == null ? DIRECT_RATIONALE : router.rationale(),
                envelope.retrievalPath(),
                canonicalHit(envelope),
                relevanceFloorPassed,
                round(relevanceScore),
                envelope.isRefusal(),
                envelope.citations().size(),
                round(deterministicMs),
                round(semanticMs),
                round(totalMs));
    }

    /** Whether the deterministic path was consulted, and whether it held the fact. */
    private static Boolean canonicalHit(Envelope envelope) {
        Route router = envelope.router();
        if (router != null && Route.SEMANTIC.equals(router.route())) {
            return null;
        }
        return envelope.citations().stream()
                .anyMatch(cite -> Citation.DETERMINISTIC.equals(cite.path()));
    }

    /** One decimal place, half-even — the rounding {@code round(x, 1)} applies on the Python side. */
    private static Double round(Double value) {
        return value == null
                ? null
                : BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** The wire form: the key names and the order the spec fixes. */
    public Map<String, Object> asMap() {
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("deterministic", deterministicMs);
        latency.put("semantic", semanticMs);
        latency.put("total", totalMs);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("@timestamp", timestamp);
        map.put("schema_version", schemaVersion);
        map.put("query", query);
        map.put("route", route);
        map.put("rationale", rationale);
        map.put("retrieval_path", retrievalPath);
        map.put("canonical_hit", canonicalHit);
        map.put("relevance_floor_passed", relevanceFloorPassed);
        map.put("relevance_score", relevanceScore);
        map.put("refused", refused);
        map.put("cites", cites);
        map.put("latency_ms", latency);
        return map;
    }
}
