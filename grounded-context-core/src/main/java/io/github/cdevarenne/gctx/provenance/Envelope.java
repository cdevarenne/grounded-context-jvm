package io.github.cdevarenne.gctx.provenance;

import io.github.cdevarenne.gctx.router.Route;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The grounded-answer envelope: no answer without a citation.
 *
 * <p>The refusal is enforced here rather than left to callers. An empty citation list rewrites
 * the answer to {@link #NOT_FOUND}, so there is no path through this type that returns prose
 * without evidence — which is the guarantee the whole artifact rests on.
 */
public record Envelope(String answer, String retrievalPath, Route router, List<Citation> citations) {

    public static final String NOT_FOUND = "Not found in the grounded sources.";

    public static final String DETERMINISTIC = "deterministic";
    public static final String SEMANTIC = "semantic";
    public static final String MIXED = "mixed";

    public Envelope {
        citations = List.copyOf(citations);
        if (citations.isEmpty()) {
            answer = NOT_FOUND;
        }
    }

    public static Envelope grounded(
            String answer, List<Citation> citations, String retrievalPath, Route router) {
        return new Envelope(answer, retrievalPath, router, citations);
    }

    /** A refusal is a result, not an error: the engine ran and found nothing to stand on. */
    public static Envelope refusal(String retrievalPath, Route router) {
        return new Envelope(NOT_FOUND, retrievalPath, router, List.of());
    }

    public boolean isRefusal() {
        return citations.isEmpty();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("answer", answer);
        map.put("retrieval_path", retrievalPath);
        map.put("router", router == null ? null : router.asMap());
        map.put("citations", citations.stream().map(Citation::asMap).toList());
        return map;
    }
}
