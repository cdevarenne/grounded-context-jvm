package io.github.cdevarenne.gctx.provenance;

import io.github.cdevarenne.gctx.bundle.Concept;
import io.github.cdevarenne.gctx.lookup.LookupResult;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One citation. Both retrieval paths emit this same shape.
 *
 * <p>That sameness is the point: a dual engine only reads as one auditable system if a reader
 * cannot tell, from the shape of the evidence, which half produced it. Fields that do not apply
 * to a path are null rather than absent — a semantic hit has no OKF trust tier, and saying so
 * explicitly is more honest than omitting the key.
 *
 * @param score null on the deterministic path; an exact hit is not ranked, and a score would
 *              imply it could have been
 */
public record Citation(
        String path,
        String sourceId,
        String sourceUrl,
        String locator,
        String method,
        Double score,
        String verifiedAt,
        String trustTier,
        String status,
        String staleAfter,
        boolean isStale,
        List<String> hops,
        String snippet) {

    public static final String DETERMINISTIC = "deterministic";
    public static final String SEMANTIC = "semantic";
    public static final String EXACT_LOOKUP = "exact-lookup";

    public Citation {
        hops = List.copyOf(hops);
    }

    /** Build a citation from a deterministic hit, inheriting the concept's OKF provenance. */
    public static Citation fromLookup(LookupResult result, LocalDate asOf) {
        Concept concept = result.concept();
        return new Citation(
                DETERMINISTIC,
                concept.id(),
                concept.sourceUrl().orElse(null),
                result.locator(),
                EXACT_LOOKUP,
                null,
                concept.verifiedAt().orElse(null),
                concept.trustTier().label(),
                concept.status(),
                concept.staleAfter() == null ? null : concept.staleAfter().toString(),
                concept.isStale(asOf),
                result.hops(),
                result.locator() + " = " + render(result.value()));
    }

    /** Python renders the value with repr(); quote strings so the snippet reads the same. */
    private static String render(Object value) {
        return value instanceof String s ? "'" + s + "'" : String.valueOf(value);
    }

    /** The wire form, keyed exactly as docs/specs/provenance.md specifies. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", path);
        map.put("source_id", sourceId);
        map.put("source_url", sourceUrl);
        map.put("locator", locator);
        map.put("method", method);
        map.put("score", score);
        map.put("verified_at", verifiedAt);
        map.put("trust_tier", trustTier);
        map.put("status", status);
        map.put("stale_after", staleAfter);
        map.put("is_stale", isStale);
        map.put("hops", hops);
        map.put("snippet", snippet);
        return map;
    }
}
