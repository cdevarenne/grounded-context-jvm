package io.github.cdevarenne.gctx.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.service.SemanticSearch;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * CLI contract tests.
 *
 * <p>The interface is the product here: option names, rendered text and exit codes are the same
 * as the Python {@code gctx}, so the two implementations are interchangeable in a demo. These
 * assert the strings the Python test suite also asserts, so a divergence fails a build rather
 * than showing up in front of an audience.
 */
class GctxCommandTest {

    /** Runs the CLI in-process, capturing stdout and the exit code. */
    private record Result(String out, int exitCode) {
    }

    private static Result run(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new GctxCommand(SemanticSearch.UNAVAILABLE)).execute(args);
            return new Result(captured.toString(StandardCharsets.UTF_8), code);
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void lookup_prints_the_answer_and_its_provenance() {
        Result result = run("lookup", "anthropic.claude-opus-5", "context_window_tokens");
        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("Answer: 1,000,000")
                .contains("↳ source: anthropic.claude-opus-5 · canonical.context_window_tokens")
                .contains("path: deterministic (exact-lookup) · human-reviewed 2026-08-10");
    }

    @Test
    void lookup_traverses_a_link_and_shows_the_hops() {
        Result result = run("lookup", "anthropic.claude-opus-5", "method");
        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("Answer: POST")
                .contains("traversed: anthropic.claude-opus-5 → anthropic.messages");
    }

    @Test
    void a_refusal_exits_one_because_it_is_a_result_not_an_error() {
        Result result = run("lookup", "anthropic.claude-opus-5", "rate_limit_rpm");
        assertThat(result.exitCode()).isEqualTo(GctxCommand.EXIT_REFUSAL);
        assertThat(result.out())
                .contains("Not found in the grounded sources.")
                .contains("no grounded source — nothing was returned rather than guessed.");
    }

    @Test
    void as_of_surfaces_staleness_inline() {
        Result result = run("--as-of", "2026-10-01",
                "lookup", "anthropic.claude-opus-5", "context_window_tokens");
        assertThat(result.out())
                .contains("⚠ STALE since 2026-09-09 — re-verify before relying on this");
    }

    @Test
    void json_emits_the_envelope_with_the_contract_keys() {
        Result result = run("--json", "lookup", "anthropic.messages", "path");
        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("\"answer\": \"/v1/messages\"")
                .contains("\"retrieval_path\": \"deterministic\"")
                .contains("\"trust_tier\": \"human-reviewed\"")
                .contains("\"score\": null");
    }

    @Test
    void ask_routes_a_precision_question_to_the_exact_path() {
        Result result = run("ask", "What is the exact context window of claude-opus-5?");
        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("router: DETERMINISTIC")
                .contains("must not be ranked")
                .contains("Answer: 1,000,000");
    }

    @Test
    void ask_refuses_an_exploratory_question_when_no_engine_is_wired() {
        Result result = run("ask", "How should I chunk documents for retrieval?");
        assertThat(result.exitCode()).isEqualTo(GctxCommand.EXIT_REFUSAL);
        assertThat(result.out()).contains("router: SEMANTIC");
        assertThat(result.out()).contains("Not found in the grounded sources.");
    }

    @Test
    void route_explains_itself_without_answering() {
        Result result = run("route", "Compare claude-opus-5 and claude-sonnet-5 on max output tokens.");
        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).startsWith("BOTH — cross-entity comparison");
    }

    @Test
    void entities_lists_concepts_with_their_trust_tier() {
        Result result = run("entities");
        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("anthropic.claude-opus-5  [model]  human-reviewed")
                .contains("    canonical.context_window_tokens");
    }

    @Test
    void entities_flags_staleness_against_the_as_of_date() {
        assertThat(run("--as-of", "2026-10-01", "entities").out())
                .contains("anthropic.claude-opus-5  [model]  human-reviewed ⚠ STALE");
    }
}
