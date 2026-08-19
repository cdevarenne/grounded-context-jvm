package io.github.cdevarenne.gctx.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.router.Route;
import io.github.cdevarenne.gctx.telemetry.TelemetryEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The telemetry event schema is the contract between this port and the Python reference.
 *
 * <p>{@code docs/specs/observability.md} says so outright: the transport may differ — a plain
 * ndjson sink here, Micrometer later — but the emitted document may not. This is the check that
 * makes that a fact rather than an intention, and it is the same shape as the bundle and
 * index-spec guards: read the reference, compare, skip when it is not checked out.
 *
 * <p>The golden fixture is duplicated for the same reason {@code knowledge/} is: this repo has to
 * run standalone. Two copies with no check between them separate silently.
 */
class TelemetryParityTest {

    static final String REFERENCE_VAR = "GCTX_REFERENCE_BUNDLE";

    /** Default: the Python repo as a sibling checkout, which is how the two are developed. */
    static final Path DEFAULT_BUNDLE = Path.of("..", "..", "grounded-context", "knowledge");

    static Path referenceRepo() {
        String configured = System.getenv(REFERENCE_VAR);
        Path bundle = configured == null || configured.isBlank()
                ? DEFAULT_BUNDLE
                : Path.of(configured);
        return bundle.getParent();
    }

    static Path spec() {
        return referenceRepo().resolve("docs").resolve("specs").resolve("observability.md");
    }

    static Path referenceFixture(String name) {
        return referenceRepo().resolve("tests").resolve("data").resolve(name);
    }

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean referenceRepoAvailable() {
        return Files.isRegularFile(spec());
    }

    /** The first fenced ```json block in the spec — the worked example of one event. */
    private static final Pattern JSON_BLOCK =
            Pattern.compile("```json\\s*\\n(\\{.*?\\n\\})\\s*\\n```", Pattern.DOTALL);

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> specExample() {
        Matcher matcher = JSON_BLOCK.matcher(read(spec()));
        assertThat(matcher.find()).as("the spec no longer shows a worked event").isTrue();
        try {
            return new ObjectMapper().readValue(matcher.group(1), new TypeReference<>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An event with every optional field populated, so none can hide from the comparison. */
    private static Map<String, Object> emitted() {
        Envelope envelope = Envelope.grounded(
                "an answer",
                List.of(new io.github.cdevarenne.gctx.provenance.Citation(
                        "semantic", "src", "https://example.test", "chunk:1", "hybrid",
                        1.0, null, null, null, null, false, List.of(), "snippet")),
                Envelope.SEMANTIC,
                new Route(Route.BOTH, "ambiguous"));
        return TelemetryEvent.from("a query", envelope, 1.8, 214.6, 216.9, true, 16.8).asMap();
    }

    @Test
    @EnabledIf("referenceRepoAvailable")
    void the_emitted_event_matches_the_field_list_the_spec_fixes() {
        assertThat(emitted().keySet())
                .as("the event schema is the contract — change the spec and both ports together")
                .containsExactlyElementsOf(specExample().keySet());
    }

    @Test
    @EnabledIf("referenceRepoAvailable")
    @SuppressWarnings("unchecked")
    void the_nested_latency_fields_match_too() {
        Map<String, Object> spec = (Map<String, Object>) specExample().get("latency_ms");
        Map<String, Object> mine = (Map<String, Object>) emitted().get("latency_ms");
        assertThat(mine.keySet()).containsExactlyElementsOf(spec.keySet());
    }

    @Test
    @EnabledIf("referenceRepoAvailable")
    void the_schema_version_matches_the_reference() {
        assertThat(specExample().get("schema_version"))
                .as("a field change bumps the version in both repos, or the summary lies")
                .isEqualTo(TelemetryEvent.SCHEMA_VERSION);
    }

    @Test
    @EnabledIf("referenceRepoAvailable")
    void the_golden_fixture_is_the_same_file_in_both_repos() {
        assertThat(TelemetryFixtures.read(TelemetryFixtures.SAMPLE))
                .as("the sample log has diverged — copy it across, do not edit one side")
                .isEqualTo(read(referenceFixture("telemetry-sample.ndjson")));
        assertThat(TelemetryFixtures.read(TelemetryFixtures.GOLDEN))
                .as("the expected output has diverged — one implementation has drifted")
                .isEqualTo(read(referenceFixture("telemetry-summary.golden.txt")));
    }
}
