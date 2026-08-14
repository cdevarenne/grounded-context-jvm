package io.github.cdevarenne.gctx.service;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.lookup.Lookup;
import io.github.cdevarenne.gctx.lookup.LookupResult;
import io.github.cdevarenne.gctx.lookup.QueryMatcher;
import io.github.cdevarenne.gctx.provenance.Citation;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.router.Route;
import io.github.cdevarenne.gctx.router.Router;
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
 */
public final class GroundedContextService {

    public static final int SEMANTIC_RESULTS = 5;

    private final Bundle bundle;
    private final SemanticSearch semantic;

    public GroundedContextService(Bundle bundle) {
        this(bundle, SemanticSearch.UNAVAILABLE);
    }

    public GroundedContextService(Bundle bundle, SemanticSearch semantic) {
        this.bundle = bundle;
        this.semantic = semantic;
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

    /** Envelope for one exact field, or the refusal when the bundle does not hold it. */
    public Envelope lookupField(String entityId, String field, LocalDate asOf, Route decision) {
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

    /** Route a natural-language question, then answer it on the path chosen. */
    public Envelope ask(String query, LocalDate asOf) {
        Route decision = Router.route(query);

        if (Route.SEMANTIC.equals(decision.route())) {
            return semanticAnswer(query, decision);
        }

        Optional<String> entity = QueryMatcher.findEntity(bundle, query);
        Optional<String> field = entity
                .map(id -> QueryMatcher.findField(bundle, query, id))
                .orElseGet(() -> QueryMatcher.findField(bundle, query, null));

        Envelope exact = entity.isPresent() && field.isPresent()
                ? lookupField(entity.get(), field.get(), asOf, decision)
                : Envelope.refusal(Envelope.DETERMINISTIC, decision);

        if (!Route.BOTH.equals(decision.route())) {
            return exact;
        }

        // router.md: query both, prefer an exact hit where one exists, never drop provenance.
        List<Citation> extra = semanticCitations(query);
        if (exact.isRefusal()) {
            return semanticAnswer(query, decision);
        }
        if (extra.isEmpty()) {
            return exact;
        }
        List<Citation> merged = new ArrayList<>(exact.citations());
        merged.addAll(extra);
        return Envelope.grounded(exact.answer(), merged, Envelope.MIXED, decision);
    }

    private List<Citation> semanticCitations(String query) {
        return semantic.search(query, SEMANTIC_RESULTS);
    }

    /** Grounded passages, best first. The caller writes prose; this supplies the ground. */
    private Envelope semanticAnswer(String query, Route decision) {
        List<Citation> citations = semanticCitations(query);
        String answer = citations.isEmpty() ? "" : citations.getFirst().snippet();
        return Envelope.grounded(answer, citations, Envelope.SEMANTIC, decision);
    }
}
