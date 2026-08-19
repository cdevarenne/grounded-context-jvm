package io.github.cdevarenne.gctx.telemetry;

/**
 * Where an event goes once the answer is final.
 *
 * <p>The same seam as {@code SemanticSearch}: the core defines it and never implements a real one,
 * so the guaranteed spine acquires no file format, no serializer and no cluster. The app module
 * supplies the newline-delimited log, and could supply Micrometer instead without the core moving.
 *
 * <p>{@link #NONE} is the deliberate default. A service with no sink still answers; telemetry is an
 * observer, and an observer that is absent must change nothing.
 */
@FunctionalInterface
public interface TelemetrySink {

    /** Discards every event. Used wherever no sink is wired, including the default constructor. */
    TelemetrySink NONE = event -> { };

    /**
     * Record one event.
     *
     * <p>Implementations may throw: {@link Telemetry#record} is the guard, so a sink is free to be
     * written as though writing always works.
     */
    void emit(TelemetryEvent event);
}
