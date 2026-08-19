package io.github.cdevarenne.gctx.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import io.github.cdevarenne.gctx.service.SemanticResult;
import io.github.cdevarenne.gctx.service.SemanticSearch;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The observability slice, and the three guarantees it is not allowed to break.
 *
 * <p>Each non-negotiable from {@code docs/specs/observability.md} has a test that fails when the
 * guarantee is broken, rather than a comment claiming it holds: telemetry never changes an answer,
 * never blocks one, and needs no cloud.
 */
class TelemetryTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 8, 20);
    static final Path BUNDLE = Path.of("..", "knowledge");

    /** Collects events in memory, so a test can read what the service emitted. */
    static final class Recorder implements TelemetrySink {
        final List<TelemetryEvent> events = new ArrayList<>();

        @Override
        public void emit(TelemetryEvent event) {
            events.add(event);
        }
    }

    private static GroundedContextService service(TelemetrySink sink) {
        return new GroundedContextService(Bundle.load(BUNDLE), SemanticSearch.UNAVAILABLE, sink);
    }

    private static GroundedContextService service(SemanticSearch semantic, TelemetrySink sink) {
        return new GroundedContextService(Bundle.load(BUNDLE), semantic, sink);
    }

    // --- the event ---------------------------------------------------------------------

    @Test
    void the_event_carries_every_field_the_spec_names() {
        Recorder sink = new Recorder();
        service(sink).lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null);

        assertThat(sink.events).hasSize(1);
        Map<String, Object> event = sink.events.getFirst().asMap();
        assertThat(event.keySet()).containsExactly(
                "@timestamp", "schema_version", "query", "route", "rationale", "retrieval_path",
                "canonical_hit", "relevance_floor_passed", "relevance_score", "refused", "cites",
                "latency_ms");
        assertThat(event).containsEntry("schema_version", TelemetryEvent.SCHEMA_VERSION);
        assertThat((String) event.get("@timestamp"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(((Map<?, ?>) event.get("latency_ms")).keySet().stream()
                .map(String::valueOf).toList())
                .containsExactly("deterministic", "semantic", "total");
    }

    @Test
    void a_direct_lookup_reports_itself_as_unrouted() {
        Recorder sink = new Recorder();
        service(sink).lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null);

        TelemetryEvent event = sink.events.getFirst();
        assertThat(event.route()).isEqualTo(TelemetryEvent.DIRECT);
        assertThat(event.rationale()).isEqualTo(TelemetryEvent.DIRECT_RATIONALE);
        // Precision by construction, so the curation signal is never absent on these.
        assertThat(event.canonicalHit()).isTrue();
    }

    @Test
    void canonical_hit_separates_a_miss_from_a_query_that_never_asked() {
        Recorder sink = new Recorder();
        service(sink).lookupField("anthropic.claude-opus-5", "rate_limit_rpm", AS_OF, null);
        assertThat(sink.events.getFirst().canonicalHit())
                .as("consulted and not held is false, not absent")
                .isFalse();

        Recorder semantic = new Recorder();
        service(semantic).ask("How should I chunk documents for retrieval?", AS_OF);
        assertThat(semantic.events.getFirst().canonicalHit())
                .as("never consulted is absent, which is not the same as a miss")
                .isNull();
    }

    @Test
    void a_refusal_is_recorded_as_one() {
        Recorder sink = new Recorder();
        Envelope envelope =
                service(sink).lookupField("anthropic.claude-opus-5", "rate_limit_rpm", AS_OF, null);

        assertThat(envelope.isRefusal()).isTrue();
        assertThat(sink.events.getFirst().refused()).isTrue();
        assertThat(sink.events.getFirst().cites()).isZero();
    }

    @Test
    void a_routed_deterministic_answer_emits_once() {
        Recorder sink = new Recorder();
        service(sink).ask("What is the exact context window of claude-opus-5?", AS_OF);

        assertThat(sink.events)
                .as("ask() reaches the lookup through a private helper, so one answer is one event")
                .hasSize(1);
        assertThat(sink.events.getFirst().route()).isEqualTo("DETERMINISTIC");
    }

    @Test
    void the_score_behind_the_floor_verdict_is_recorded() {
        SemanticSearch blocked = new SemanticSearch() {
            @Override
            public List<io.github.cdevarenne.gctx.provenance.Citation> search(String q, int size) {
                return List.of();
            }

            @Override
            public SemanticResult probe(String query, int size) {
                return new SemanticResult(List.of(), false, 1.7);
            }
        };
        Recorder sink = new Recorder();
        service(blocked, sink).ask("How should I chunk documents for retrieval?", AS_OF);

        assertThat(sink.events.getFirst().relevanceFloorPassed()).isFalse();
        assertThat(sink.events.getFirst().relevanceScore()).isEqualTo(1.7);
    }

    @Test
    void no_probe_means_no_score() {
        Recorder sink = new Recorder();
        service(sink).ask("How should I chunk documents for retrieval?", AS_OF);

        TelemetryEvent event = sink.events.getFirst();
        assertThat(event.relevanceFloorPassed())
                .as("an engine that never ran has no verdict; absent is not blocked")
                .isNull();
        assertThat(event.relevanceScore()).isNull();
    }

    // --- the three non-negotiables ------------------------------------------------------

    @Test
    void telemetry_does_not_change_an_answer() {
        Envelope withSink = service(new Recorder())
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null);
        Envelope withoutSink = new GroundedContextService(Bundle.load(BUNDLE))
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null);

        assertThat(withSink).isEqualTo(withoutSink);
    }

    @Test
    void an_answer_survives_a_sink_that_throws() {
        TelemetrySink broken = event -> {
            throw new IllegalStateException("disk full");
        };
        Envelope envelope = service(broken)
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null);

        assertThat(envelope.answer()).isEqualTo("1,000,000");
        assertThat(envelope).isEqualTo(new GroundedContextService(Bundle.load(BUNDLE))
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null));
    }

    @Test
    void a_sink_that_throws_on_a_routed_question_still_answers() {
        TelemetrySink broken = event -> {
            throw new IllegalStateException("disk full");
        };
        Envelope envelope =
                service(broken).ask("What is the exact context window of claude-opus-5?", AS_OF);

        assertThat(envelope.answer()).isEqualTo("1,000,000");
        assertThat(envelope.isRefusal()).isFalse();
    }

    @Test
    void recording_an_event_never_reaches_the_cloud_half() {
        // SemanticSearch.UNAVAILABLE is the only engine wired, and the deterministic route never
        // consults it. An emit that had grown a lookup of its own would show up here.
        SemanticSearch mustNotRun = (query, size) -> {
            throw new AssertionError("the semantic engine was consulted on a deterministic answer");
        };
        Recorder sink = new Recorder();
        service(mustNotRun, sink)
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", AS_OF, null);

        assertThat(sink.events).hasSize(1);
    }
}
