package io.github.cdevarenne.gctx.app.eval;

import io.github.cdevarenne.gctx.provenance.Envelope;
import java.util.LinkedHashMap;
import java.util.Map;

/** What actually happened, and whether that is acceptable. */
public record EvalResult(
        EvalCase testCase, String route, String rationale, String retrievalPath,
        String answer, int citations, String verdict) {

    public static final String PASS = "PASS";
    public static final String KNOWN = "KNOWN";
    public static final String FAIL = "FAIL";

    /** A refusal is reported as its own outcome rather than as whichever path produced it. */
    public String actual() {
        return Envelope.NOT_FOUND.equals(answer) ? EvalCase.REFUSAL : retrievalPath;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", testCase.id());
        map.put("question", testCase.question());
        map.put("expected", testCase.expected());
        map.put("actual", actual());
        map.put("route", route);
        map.put("rationale", rationale);
        map.put("citations", citations);
        map.put("verdict", verdict);
        return map;
    }
}
