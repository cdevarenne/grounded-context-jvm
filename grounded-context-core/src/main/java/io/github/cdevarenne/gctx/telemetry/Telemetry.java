package io.github.cdevarenne.gctx.telemetry;

import io.github.cdevarenne.gctx.provenance.Envelope;

/**
 * The one call an answer path makes, and it cannot throw.
 *
 * <p>Building is inside the guard as well as writing, because an answer must survive a malformed
 * event just as it survives a full disk. Every failure is swallowed to at most one line on stderr:
 * an unavailable telemetry sink is a no-op, the same way an unavailable engine is a refusal rather
 * than a crash. The broad catch is the requirement, not an oversight.
 */
public final class Telemetry {

    private Telemetry() {
    }

    /** Build and emit one event, best-effort. Never throws, never changes the envelope. */
    public static void record(
            TelemetrySink sink,
            String query,
            Envelope envelope,
            Double deterministicMs,
            Double semanticMs,
            double totalMs,
            Boolean relevanceFloorPassed,
            Double relevanceScore) {
        try {
            sink.emit(TelemetryEvent.from(
                    query, envelope, deterministicMs, semanticMs, totalMs,
                    relevanceFloorPassed, relevanceScore));
        } catch (RuntimeException | LinkageError error) {
            System.err.println("telemetry: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        }
    }
}
