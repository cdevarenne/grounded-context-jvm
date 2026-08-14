package io.github.cdevarenne.gctx.app.eval;

import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.router.Route;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import java.time.LocalDate;
import java.util.List;

/**
 * The evaluation set, runnable.
 *
 * <p>Small and illustrative — <b>not</b> a benchmark. What it checks is that the router sends
 * questions to the right place, that every answer carries provenance, and that the one question
 * with no grounded answer refuses.
 *
 * <p>Known deviations are declared rather than hidden. A case that fails for an understood
 * reason is reported as {@code KNOWN} with the reason attached, and counted separately from a
 * pass — otherwise the harness would rubber-stamp whatever the code happens to do.
 */
public final class EvalHarness {

    private final GroundedContextService service;

    public EvalHarness(GroundedContextService service) {
        this.service = service;
    }

    /** Ask one question and judge the outcome against the spec. */
    public EvalResult runCase(EvalCase testCase, LocalDate asOf) {
        Envelope envelope = service.ask(testCase.question(), asOf);
        Route router = envelope.router();

        EvalResult result = new EvalResult(
                testCase,
                router == null ? "-" : router.route(),
                router == null ? "-" : router.rationale(),
                envelope.retrievalPath(),
                envelope.answer(),
                envelope.citations().size(),
                "");

        boolean passed = result.actual().equals(testCase.expected());
        boolean grounded = result.citations() > 0 || EvalCase.REFUSAL.equals(result.actual());

        String verdict;
        if (passed && grounded) {
            verdict = testCase.hasKnownDeviation() ? EvalResult.KNOWN : EvalResult.PASS;
        } else if (testCase.hasKnownDeviation()) {
            verdict = EvalResult.KNOWN;
        } else {
            verdict = EvalResult.FAIL;
        }

        return new EvalResult(result.testCase(), result.route(), result.rationale(),
                result.retrievalPath(), result.answer(), result.citations(), verdict);
    }

    /** Run the whole set, in spec order. */
    public List<EvalResult> runAll(LocalDate asOf) {
        return EvalCase.CASES.stream().map(testCase -> runCase(testCase, asOf)).toList();
    }
}
