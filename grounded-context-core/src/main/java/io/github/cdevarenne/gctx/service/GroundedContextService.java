package io.github.cdevarenne.gctx.service;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.lookup.Lookup;
import io.github.cdevarenne.gctx.lookup.LookupResult;
import io.github.cdevarenne.gctx.lookup.QueryMatcher;
import io.github.cdevarenne.gctx.provenance.Citation;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.router.Route;
import io.github.cdevarenne.gctx.router.Router;
import io.github.cdevarenne.gctx.telemetry.Telemetry;
import io.github.cdevarenne.gctx.telemetry.TelemetrySink;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Answer construction shared by every consumer.
 *
 * <p>The CLI and the MCP server must produce identical envelopes, so the citation contract lives
 * here once rather than being re-implemented per surface. The semantic half arrives through
 * {@link SemanticSearch}, so this class stays free of any retrieval technology.
 *
 * <p>It is also the single instrumentation site: both public entry points emit one telemetry event
 * <em>after</em> their envelope is final, so both surfaces are instrumented once rather than once
 * each. See {@code docs/specs/observability.md}.
 */
public final class GroundedContextService {

    public static final int SEMANTIC_RESULTS = 5;

    private final Bundle bundle;
    private final SemanticSearch semantic;
    private final TelemetrySink telemetry;

    public GroundedContextService(Bundle bundle) {
        this(bundle, SemanticSearch.UNAVAILABLE, TelemetrySink.NONE);
    }

    public GroundedContextService(Bundle bundle, SemanticSearch semantic) {
        this(bundle, semantic, TelemetrySink.NONE);
    }

    public GroundedContextService(Bundle bundle, SemanticSearch semantic, TelemetrySink telemetry) {
        this.bundle = bundle;
        this.semantic = semantic;
        this.telemetry = telemetry;
    }

    public Bundle bundle() {
        return bundle;
    }

    /** Render a canonical value for reading: booleans as yes/no, integers with separators. */
    public static String formatValue(Object value) {
        return switch (value) {
            case Boolean flag -> flag ? "yes" : "no";
            case Integer number -> String.format("%,d", number);
            case Long number -> String.format("%,d", number);
            case null -> "";
            default -> String.valueOf(value);
        };
    }

    private static double elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    /**
     * Envelope for one exact field, or the refusal when the bundle does not hold it.
     *
     * <p>This is the entry point for a lookup that names its entity and field outright — {@code
     * gctx lookup} and the MCP {@code lookup_canonical_fact} tool — so it records one event.
     * {@link #ask} builds through the private helper instead, so a routed deterministic answer
     * produces one event and not two.
     */
    public Envelope lookupField(String entityId, String field, LocalDate asOf, Route decision) {
        long started = System.nanoTime();
        Envelope envelope = lookupEnvelope(entityId, field, asOf, decision);
        double elapsed = elapsedMs(started);
        Telemetry.record(telemetry, entityId + " " + field, envelope,
                elapsed, null, elapsed, null, null);
        return envelope;
    }

    private Envelope lookupEnvelope(
            String entityId, String field, LocalDate asOf, Route decision) {
        Optional<LookupResult> result = Lookup.resolve(bundle, entityId, field);
        if (result.isEmpty()) {
            return Envelope.refusal(Envelope.DETERMINISTIC, decision);
        }
        LookupResult hit = result.get();
        return Envelope.grounded(
                formatValue(hit.value()),
                List.of(Citation.fromLookup(hit, asOf)),
                Envelope.DETERMINISTIC,
                decision);
    }

    /**
     * Route a natural-language question, then answer it on the path chosen.
     *
     * <p>Records one event per answered question, built from the finished envelope and emitted
     * after it — so the same query returns the same answer whether the sink works, fails, or is
     * absent.
     */
    public Envelope ask(String query, LocalDate asOf) {
        long started = System.nanoTime();
        Route decision = Router.route(query);

        if (Route.SEMANTIC.equals(decision.route())) {
            long semanticStarted = System.nanoTime();
            SemanticResult result = semanticProbe(query);
            double semanticMs = elapsedMs(semanticStarted);
            Envelope envelope = semanticAnswer(result.citations(), decision);
            Telemetry.record(telemetry, query, envelope, null, semanticMs, elapsedMs(started),
                    result.floorPassed(), result.floorScore());
            return envelope;
        }

        long deterministicStarted = System.nanoTime();
        Optional<String> entity = QueryMatcher.findEntity(bundle, query);
        Optional<String> field = entity
                .map(id -> QueryMatcher.findField(bundle, query, id))
                .orElseGet(() -> QueryMatcher.findField(bundle, query, null));

        Envelope exact = entity.isPresent() && field.isPresent()
                ? lookupEnvelope(entity.get(), field.get(), asOf, decision)
                : Envelope.refusal(Envelope.DETERMINISTIC, decision);
        double deterministicMs = elapsedMs(deterministicStarted);

        if (!Route.BOTH.equals(decision.route())) {
            Telemetry.record(telemetry, query, exact, deterministicMs, null, elapsedMs(started),
                    null, null);
            return exact;
        }

        long semanticStarted = System.nanoTime();
        SemanticResult result = semanticProbe(query);
        double semanticMs = elapsedMs(semanticStarted);

        Envelope envelope = merge(exact, result.citations(), decision);
        Telemetry.record(telemetry, query, envelope, deterministicMs, semanticMs,
                elapsedMs(started), result.floorPassed(), result.floorScore());
        return envelope;
    }

    /**
     * router.md: query both, prefer an exact hit where one exists, never drop provenance.
     *
     * <p>One exception, and it is the point of the whole design: when the router identified a
     * precision question — a cross-entity comparison asks for exact values by construction — a
     * deterministic miss is a <em>curation gap</em>, not an invitation to rank. Falling back
     * there is exactly the failure this project exists to prevent: a plausible, cited, adjacent
     * answer to a question that had a right one. So it refuses instead.
     */
    private Envelope merge(Envelope exact, List<Citation> extra, Route decision) {
        if (exact.isRefusal()) {
            return decision.precision() ? exact : semanticAnswer(extra, decision);
        }
        if (extra.isEmpty()) {
            return exact;
        }
        List<Citation> merged = new ArrayList<>(exact.citations());
        merged.addAll(extra);
        return Envelope.grounded(exact.answer(), merged, Envelope.MIXED, decision);
    }

    private SemanticResult semanticProbe(String query) {
        return semantic.probe(query, SEMANTIC_RESULTS);
    }

    /**
     * Grounded passages, best first. The caller writes prose; this supplies the ground.
     *
     * <p>Takes citations rather than fetching them, so a caller that has already retrieved cannot
     * retrieve a second time for the same query.
     */
    private Envelope semanticAnswer(List<Citation> citations, Route decision) {
        String answer = citations.isEmpty() ? "" : citations.getFirst().snippet();
        return Envelope.grounded(answer, citations, Envelope.SEMANTIC, decision);
    }
}
