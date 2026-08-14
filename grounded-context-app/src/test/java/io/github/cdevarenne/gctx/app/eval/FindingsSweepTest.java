package io.github.cdevarenne.gctx.app.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.app.es.ElasticsearchConfiguration;
import io.github.cdevarenne.gctx.app.es.ElasticsearchSettings;
import io.github.cdevarenne.gctx.app.es.HybridSemanticSearch;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for the findings sweep.
 *
 * <p>The sweep exists so the corpus-wide numbers are regenerable rather than asserted, so what
 * matters is that its token extraction is right — a sloppy regex would silently turn 44 of 149
 * into some other pair — and that the figures the Python repo publishes still come out of this
 * implementation.
 */
class FindingsSweepTest {

    private static Set<String> matches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.results().map(java.util.regex.MatchResult::group)
                .collect(java.util.stream.Collectors.toSet());
    }

    @ParameterizedTest
    @CsvSource({
        "claude-opus-5,claude-opus-5",
        "'the \"claude-sonnet-4-6\" model',claude-sonnet-4-6",
    })
    void the_hyphen_pattern_matches_only_hyphenated_tokens(String text, String expected) {
        assertThat(matches(FindingsSweep.HYPHENATED, text)).containsExactly(expected);
    }

    @Test
    void the_hyphen_pattern_ignores_underscored_tokens() {
        assertThat(matches(FindingsSweep.HYPHENATED, "rank_constant")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "rank_constant,rank_constant",
        "'\"num_candidates\": 100',num_candidates",
    })
    void the_underscore_pattern_matches_only_underscored_tokens(String text, String expected) {
        assertThat(matches(FindingsSweep.UNDERSCORED, text)).containsExactly(expected);
    }

    @Test
    void the_underscore_pattern_ignores_hyphenated_tokens() {
        assertThat(matches(FindingsSweep.UNDERSCORED, "claude-opus-5")).isEmpty();
    }

    @Test
    void unique_to_one_chunk_ignores_terms_that_appear_twice() {
        // The rank sweep is only meaningful when there is exactly one right chunk to find.
        List<FindingsSweep.Chunk> chunks = List.of(
                new FindingsSweep.Chunk("a", 0, "alpha-beta-one shared-term-here"),
                new FindingsSweep.Chunk("b", 1, "gamma-delta-two shared-term-here"));
        Map<String, ArmComparison.Target> unique =
                FindingsSweep.uniqueToOneChunk(chunks, FindingsSweep.HYPHENATED, 5);
        assertThat(unique).containsOnlyKeys("alpha-beta-one", "gamma-delta-two");
        assertThat(unique.get("alpha-beta-one")).isEqualTo(new ArmComparison.Target("a", 0));
    }

    @Test
    void a_term_counts_once_per_chunk_however_often_it_repeats() {
        // Otherwise a term repeated in one chunk would look like it spans several.
        List<FindingsSweep.Chunk> chunks =
                List.of(new FindingsSweep.Chunk("s", 3, "a-b a-b a-b"));
        assertThat(FindingsSweep.uniqueToOneChunk(chunks, FindingsSweep.HYPHENATED, 1))
                .containsExactly(Map.entry("a-b", new ArmComparison.Target("s", 3)));
    }

    // --- live cluster: the published aggregates --------------------------------------

    @SuppressWarnings("unused") // referenced by @EnabledIf
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

    // The sweeps issue several hundred queries each, so every live test shares one run.
    private static FindingsSweep sweep;
    private static List<FindingsSweep.Chunk> chunks;
    private static Map<String, FindingsSweep.ShapeEffect> effect;
    private static Map<String, Object> hidden;
    private static List<FindingsSweep.Probe> probes;

    @BeforeAll
    static void measureOnce() {
        if (!indexReachable()) {
            return;
        }
        sweep = new FindingsSweep(ElasticsearchConfiguration.client().orElseThrow());
        chunks = sweep.allChunks();
        effect = sweep.subfieldEffect(chunks);
        hidden = sweep.invisibleToExact(chunks);
        probes = sweep.probeScores();
    }

    @Test
    @EnabledIf("indexReachable")
    void the_published_subfield_figures_reproduce() {
        // findings.md publishes 44 of 149 and 0 of 87; the zero is the load-bearing half,
        // because it disproves "the subfield rescues split underscore tokens".
        assertThat(effect.get("hyphenated").total()).isEqualTo(149);
        assertThat(effect.get("hyphenated").improved()).isEqualTo(44);
        assertThat(effect.get("underscored").total()).isEqualTo(87);
        assertThat(effect.get("underscored").improved()).isZero();

        // "Improved" only means what it says while nothing regresses.
        assertThat(effect.values()).allSatisfy(e -> assertThat(e.regressed()).isZero());
    }

    @Test
    @EnabledIf("indexReachable")
    void the_mechanism_numbers_quoted_in_findings_md_reproduce() {
        Map<String, Object> mechanism = sweep.mechanismCounts();
        assertThat(mechanism.get("content_matches")).isEqualTo(6L);
        assertThat(mechanism.get("exact_matches")).isEqualTo(1L);
        assertThat(mechanism.get("rank_content_only")).isEqualTo(3);
        assertThat(mechanism.get("rank_with_exact")).isEqualTo(1);
    }

    @Test
    @EnabledIf("indexReachable")
    void the_invisible_to_exact_figure_reproduces() {
        assertThat(hidden.get("total")).isEqualTo(568);
        assertThat(hidden.get("invisible")).isEqualTo(137);

        // The prose names two examples; this is what stops them being decoration.
        @SuppressWarnings("unchecked")
        Map<String, Boolean> documented = (Map<String, Boolean>) hidden.get("documented");
        assertThat(documented).isNotEmpty();
        assertThat(documented.values()).allMatch(present -> present);
    }

    @Test
    @EnabledIf("indexReachable")
    void the_fused_score_ranks_an_off_topic_question_above_a_genuine_one() {
        // Finding 3 in its strongest form. If this stops holding, the claim is overstated.
        double worstOffTopic = probes.stream().filter(p -> "off-topic".equals(p.kind()))
                .mapToDouble(FindingsSweep.Probe::fused).max().orElseThrow();
        double weakestGenuine = probes.stream().filter(p -> "in-domain".equals(p.kind()))
                .mapToDouble(FindingsSweep.Probe::fused).min().orElseThrow();
        assertThat(worstOffTopic).isGreaterThan(weakestGenuine);
    }

    @Test
    @EnabledIf("indexReachable")
    void the_pre_fusion_score_is_what_actually_separates() {
        double weakestGenuine = probes.stream().filter(p -> "in-domain".equals(p.kind()))
                .mapToDouble(FindingsSweep.Probe::sparse).min().orElseThrow();
        assertThat(weakestGenuine).isGreaterThan(HybridSemanticSearch.RELEVANCE_FLOOR);

        // Nine of ten off-topic sit well below the floor; the tenth is the declared marathon
        // leaker, which is a stated limit rather than noise.
        List<Double> offTopic = probes.stream().filter(p -> "off-topic".equals(p.kind()))
                .map(FindingsSweep.Probe::sparse).sorted().toList();
        assertThat(offTopic.get(offTopic.size() - 2))
                .isLessThan(HybridSemanticSearch.RELEVANCE_FLOOR);
    }
}
