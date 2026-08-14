package io.github.cdevarenne.gctx.lookup;

import io.github.cdevarenne.gctx.bundle.Concept;
import java.util.List;

/**
 * One exact hit on a canonical field.
 *
 * @param hops the concept ids traversed to reach the value, starting at the one asked for.
 *             Recorded so a citation can show the path taken rather than just the answer.
 */
public record LookupResult(Object value, Concept concept, String field, List<String> hops) {

    public LookupResult {
        hops = List.copyOf(hops);
    }

    public String locator() {
        return "canonical." + field;
    }
}
