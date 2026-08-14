package io.github.cdevarenne.gctx.lookup;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.bundle.Concept;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The deterministic path: exact lookup of canonical fields, plus one-hop link traversal.
 *
 * <p>No embeddings, no ranking, no network. A field either exists or it does not — which is the
 * entire point: a value that must be exact is never allowed to be the output of a scorer.
 */
public final class Lookup {

    /** One hop is enough to reach an endpoint's fields from a model, and keeps the trail short. */
    public static final int DEFAULT_MAX_HOPS = 1;

    private Lookup() {
    }

    /** Exact match on one canonical field of one concept. No traversal. */
    public static Optional<LookupResult> direct(Bundle bundle, String entityId, String field) {
        return bundle.get(entityId)
                .filter(concept -> concept.canonical().containsKey(field))
                .map(concept -> new LookupResult(
                        concept.canonical().get(field), concept, field, List.of(entityId)));
    }

    public static Optional<LookupResult> resolve(Bundle bundle, String entityId, String field) {
        return resolve(bundle, entityId, field, DEFAULT_MAX_HOPS);
    }

    /**
     * Exact lookup, falling back to the concept's Markdown links.
     *
     * <p>A model file does not restate the endpoint's HTTP method; it links to the endpoint
     * concept that owns it. Every hop is recorded so the citation can show how the value was
     * reached — a traversed answer and a direct one are not the same claim.
     */
    public static Optional<LookupResult> resolve(
            Bundle bundle, String entityId, String field, int maxHops) {

        Optional<LookupResult> hit = direct(bundle, entityId, field);
        if (hit.isPresent()) {
            return hit;
        }
        if (maxHops < 1 || bundle.get(entityId).isEmpty()) {
            return Optional.empty();
        }

        for (Concept neighbor : bundle.linked(entityId)) {
            Optional<LookupResult> found = resolve(bundle, neighbor.id(), field, maxHops - 1);
            if (found.isPresent()) {
                LookupResult result = found.get();
                List<String> hops = new ArrayList<>();
                hops.add(entityId);
                hops.addAll(result.hops());
                return Optional.of(new LookupResult(
                        result.value(), result.concept(), result.field(), hops));
            }
        }
        return Optional.empty();
    }
}
