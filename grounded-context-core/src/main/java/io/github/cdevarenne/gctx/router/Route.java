package io.github.cdevarenne.gctx.router;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A routing decision and the reason for it.
 *
 * <p>The rationale is not a log line — it is part of the audit trail the citation block carries,
 * so a reader can see why an engine was chosen and not merely which one answered.
 *
 * @param precision {@code true} when the query asks for exact values <em>by construction</em>,
 *                  whatever path runs — a cross-entity comparison is one. The service reads this
 *                  to decide whether a deterministic miss may fall back to ranked passages. It is
 *                  deliberately absent from {@link #asMap()}: the envelope's router block is a
 *                  published contract and its shape does not change.
 */
public record Route(String route, String rationale, boolean precision) {

    /**
     * A decision that is not precision-by-construction, which is most of them.
     */
    public Route(String route, String rationale) {
        this(route, rationale, false);
    }

    public static final String DETERMINISTIC = "DETERMINISTIC";
    public static final String SEMANTIC = "SEMANTIC";
    public static final String BOTH = "BOTH";

    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("route", route);
        map.put("rationale", rationale);
        return map;
    }
}
