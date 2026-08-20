package io.github.cdevarenne.gctx.app.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.app.es.ElasticsearchConfiguration;
import io.github.cdevarenne.gctx.app.es.ElasticsearchSettings;
import io.github.cdevarenne.gctx.app.es.HybridSemanticSearch;
import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import io.github.cdevarenne.gctx.service.SemanticSearch;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for the eval harness.
 *
 * <p>The harness exists to catch the system drifting away from its own spec, so what matters is
 * that it cannot quietly pass: a declared expectation that stops matching must surface, and a
 * known deviation must never be counted as a pass.
 */
class EvalHarnessTest {

    static final Path ROOT = Path.of("..", "knowledge");
    static final LocalDate AS_OF = LocalDate.of(2026, 8, 13);

    static EvalHarness harness(SemanticSearch semantic) {
        return new EvalHarness(new GroundedContextService(Bundle.load(ROOT), semantic));
    }

    static EvalCase caseById(String id) {
        return EvalCase.CASES.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void the_set_matches_the_spec_size_and_order() {
        assertThat(EvalCase.CASES).extracting(EvalCase::id)
                .containsExactly("Q1", "Q2", "Q3", "Q4", "Q5", "Q6",
                        "Q7", "Q8", "Q9", "Q10", "Q11", "Q12",
                        "Q13", "Q14", "Q15", "Q16", "Q17", "Q18");
    }

    @Test
    void every_expectation_is_a_real_path() {
        assertThat(EvalCase.CASES).allSatisfy(c -> assertThat(c.expected()).isIn(
                Envelope.DETERMINISTIC, Envelope.SEMANTIC, Envelope.MIXED, EvalCase.REFUSAL));
    }

    @Test
    void deterministic_cases_pass_without_a_cluster() {
        // No case that needs the exact path may depend on Elasticsearch being reachable.
        EvalHarness harness = harness(SemanticSearch.UNAVAILABLE);
        for (EvalCase testCase : EvalCase.CASES) {
            if (Envelope.DETERMINISTIC.equals(testCase.expected())) {
                assertThat(harness.runCase(testCase, AS_OF).verdict())
                        .as(testCase.id()).isEqualTo(EvalResult.PASS);
            }
        }
    }

    @Test
    void the_guardrail_case_refuses() {
        // Q11: absent from both engines, so nothing may be invented.
        EvalResult result = harness(SemanticSearch.UNAVAILABLE).runCase(caseById("Q11"), AS_OF);
        assertThat(result.answer()).isEqualTo(Envelope.NOT_FOUND);
        assertThat(result.citations()).isZero();
    }

    @Test
    void alias_resolution_answers_the_natural_phrasing() {
        // Q2 asks for "Anthropic's Messages API", not the literal concept id.
        EvalResult result = harness(SemanticSearch.UNAVAILABLE).runCase(caseById("Q2"), AS_OF);
        assertThat(result.verdict()).isEqualTo(EvalResult.PASS);
        assertThat(result.answer()).isEqualTo("/v1/messages");
    }

    @Test
    void a_known_deviation_never_reads_as_a_pass() {
        List<EvalCase> declared = EvalCase.CASES.stream()
                .filter(EvalCase::hasKnownDeviation).toList();
        assertThat(declared).as("the Q3 rollup gap should still be declared").isNotEmpty();
        EvalHarness harness = harness((query, size) -> List.of());
        for (EvalCase testCase : declared) {
            assertThat(harness.runCase(testCase, AS_OF).verdict()).isEqualTo(EvalResult.KNOWN);
        }
    }

    @Test
    void a_broken_expectation_fails_loudly() {
        // The harness must not rubber-stamp: a wrong expectation has to show up as FAIL.
        EvalCase wrong = new EvalCase(
                "QX", "What is the exact context window of claude-opus-5?", Envelope.SEMANTIC);
        assertThat(harness(SemanticSearch.UNAVAILABLE).runCase(wrong, AS_OF).verdict())
                .isEqualTo(EvalResult.FAIL);
    }

    @Test
    void the_defining_chunk_is_chosen_by_the_identifier_in_the_query() {
        assertThat(ArmComparison.targetFor("What does the num_candidates parameter do?"))
                .isEqualTo(new ArmComparison.Target("elastic-knn", 7));
        assertThat(ArmComparison.targetFor("anthropic-ratelimit-tokens-reset"))
                .isEqualTo(new ArmComparison.Target("anthropic-rate-limits", 12));
        // An unrecognized query falls back rather than silently ranking nothing.
        assertThat(ArmComparison.targetFor("something else entirely"))
                .isEqualTo(ArmComparison.DEFAULT_TARGET);
    }

    // --- live cluster ----------------------------------------------------------------

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean referenceCorpus() {
        return io.github.cdevarenne.gctx.app.es.ReferenceCorpus.isPresent();
    }

    static boolean indexReachable() {
        try {
            return ElasticsearchConfiguration.client()
                    .map(c -> {
                        try {
                            return c.indices()
                                    .exists(e -> e.index(ElasticsearchSettings.INDEX)).value();
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    static HybridSemanticSearch liveSearch() {
        return new HybridSemanticSearch(ElasticsearchConfiguration.client().orElseThrow());
    }

    @Test
    @EnabledIf("referenceCorpus")
    void the_whole_set_has_no_failures() {
        List<String> failures = harness(liveSearch()).runAll(AS_OF).stream()
                .filter(r -> EvalResult.FAIL.equals(r.verdict()))
                .map(r -> r.testCase().id())
                .toList();
        assertThat(failures).as("eval regressions").isEmpty();
    }

    @Test
    @EnabledIf("referenceCorpus")
    void the_published_compare_table_reproduces() {
        // Every row of the table in the Python repo's docs/findings.md, recomputed here.
        ArmComparison comparison = new ArmComparison(liveSearch());

        assertThat(comparison.compare("rank_constant"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 5, "bm25", 1, "hybrid", 1));
        assertThat(comparison.compare("What does the rank_constant parameter do?"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 2, "bm25", 3, "hybrid", 1));
        assertThat(comparison.compare("num_candidates"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 1, "bm25", 1, "hybrid", 1));
        assertThat(comparison.compare("What does the num_candidates parameter do?"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 2, "bm25", 5, "hybrid", 1));
        assertThat(comparison.compare("rank_window_size"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 6, "bm25", 2, "hybrid", 5));
        assertThat(comparison.compare("What does the rank_window_size parameter do?"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 7, "bm25", 2, "hybrid", 3));
        assertThat(comparison.compare("anthropic-ratelimit-tokens-reset"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 2, "bm25", 1, "hybrid", 1));
        assertThat(comparison.compare("What does the anthropic-ratelimit-tokens-reset header do?"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("elser", 1, "bm25", 1, "hybrid", 1));
    }

    @Test
    @EnabledIf("referenceCorpus")
    void fusion_is_never_worse_than_the_weaker_arm() {
        // The claim findings.md actually makes — deliberately not "hybrid wins".
        ArmComparison comparison = new ArmComparison(liveSearch());
        for (String query : List.of(
                "rank_constant", "What does the rank_constant parameter do?",
                "num_candidates", "What does the num_candidates parameter do?",
                "rank_window_size", "What does the rank_window_size parameter do?",
                "anthropic-ratelimit-tokens-reset",
                "What does the anthropic-ratelimit-tokens-reset header do?")) {
            Map<String, Integer> ranks = comparison.compare(query);
            assertThat(ranks.values()).as(query).doesNotContainNull();
            assertThat(ranks.get("hybrid")).as(query)
                    .isLessThanOrEqualTo(Math.max(ranks.get("elser"), ranks.get("bm25")));
        }
    }
}
