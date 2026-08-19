package io.github.cdevarenne.gctx.service;

import io.github.cdevarenne.gctx.provenance.Citation;
import java.util.List;

/**
 * Citations, plus what the relevance floor did — which the answer envelope has no field for.
 *
 * @param floorPassed {@code TRUE} cleared, {@code FALSE} blocked, {@code null} the probe never ran.
 *                    Absent is not blocked: an engine that was never reachable and a query that was
 *                    genuinely off topic both refuse, and only the score tells them apart afterwards
 * @param floorScore  the pre-fusion score behind that verdict, so a near miss is distinguishable
 *                    from a query that was never in domain. {@code null} whenever
 *                    {@code floorPassed} is
 */
public record SemanticResult(List<Citation> citations, Boolean floorPassed, Double floorScore) {

    /** No engine was consulted, so there is no verdict — which is not the same as blocked. */
    public static final SemanticResult NONE = new SemanticResult(List.of(), null, null);

    public SemanticResult {
        citations = List.copyOf(citations);
    }
}
