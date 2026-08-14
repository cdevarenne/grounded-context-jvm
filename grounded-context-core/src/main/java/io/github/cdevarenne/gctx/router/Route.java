package io.github.cdevarenne.gctx.router;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A routing decision and the reason for it.
 *
 * <p>The rationale is not a log line — it is part of the audit trail the citation block carries,
 * so a reader can see why an engine was chosen and not merely which one answered.
 */
public record Route(String route, String rationale) {

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
