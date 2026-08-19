package io.github.cdevarenne.gctx;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.lookup.Lookup;
import io.github.cdevarenne.gctx.lookup.LookupResult;
import io.github.cdevarenne.gctx.lookup.QueryMatcher;
import io.github.cdevarenne.gctx.provenance.Citation;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.provenance.Renderer;
import io.github.cdevarenne.gctx.router.Route;
import io.github.cdevarenne.gctx.router.Router;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Behaviour shared with the Python implementation.
 *
 * <p>Where a value appears here as a literal, it is one the Python repo also asserts. The two
 * implementations answer the same questions from the same bundle, so a divergence should fail a
 * test rather than be discovered in a demo.
 */
class CoreBehaviourTest {

    static final Path ROOT = Path.of("..", "knowledge");
    static final LocalDate FRESH = LocalDate.of(2026, 8, 13);
    static final LocalDate AFTER_STALE = LocalDate.of(2026, 10, 1);

    static GroundedContextService service() {
        return new GroundedContextService(Bundle.load(ROOT));
    }

    // --- lookup ----------------------------------------------------------------------

    @Test
    void exact_lookup_returns_the_canonical_value() {
        LookupResult hit = Lookup
                .resolve(Bundle.load(ROOT), "anthropic.claude-opus-5", "context_window_tokens")
                .orElseThrow();
        assertThat(hit.value()).isEqualTo(1_000_000);
        assertThat(hit.locator()).isEqualTo("canonical.context_window_tokens");
        assertThat(hit.hops()).containsExactly("anthropic.claude-opus-5");
    }

    @Test
    void traversal_reaches_the_endpoint_and_records_the_hop() {
        LookupResult hit = Lookup
                .resolve(Bundle.load(ROOT), "anthropic.claude-opus-5", "method")
                .orElseThrow();
        assertThat(hit.value()).isEqualTo("POST");
        assertThat(hit.hops())
                .containsExactly("anthropic.claude-opus-5", "anthropic.messages");
    }

    @Test
    void an_absent_field_is_a_miss_not_a_guess() {
        assertThat(Lookup.resolve(Bundle.load(ROOT), "anthropic.claude-opus-5", "rate_limit_rpm"))
                .isEmpty();
    }

    @Test
    void traversal_is_bounded_so_the_audit_trail_stays_short() {
        assertThat(Lookup.resolve(Bundle.load(ROOT), "anthropic.claude-opus-5", "method", 0))
                .isEmpty();
    }

    // --- query matching --------------------------------------------------------------

    @Test
    void longest_entity_match_wins() {
        Bundle bundle = Bundle.load(ROOT);
        assertThat(QueryMatcher.findEntity(bundle, "context window of claude-haiku-4-5"))
                .contains("anthropic.claude-haiku-4-5");
    }

    @Test
    void an_alias_resolves_the_natural_phrasing() {
        // eval.md Q2 asks for "Anthropic's Messages API", not the literal concept id.
        assertThat(QueryMatcher.findEntity(Bundle.load(ROOT), "the Anthropic Messages API path"))
                .contains("anthropic.messages");
    }

    @ParameterizedTest
    @CsvSource({
        "'the context window',context_window_tokens",
        "'max output tokens',max_output_tokens",
        "'what is the model id',model_string",
        "'does it support vision',vision",
    })
    void synonyms_map_phrasing_onto_canonical_fields(String query, String expected) {
        assertThat(QueryMatcher.findField(Bundle.load(ROOT), query, "anthropic.claude-opus-5"))
                .contains(expected);
    }

    // --- router ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "'What is the exact context window of claude-opus-5?',DETERMINISTIC",
        "'How should I chunk documents for retrieval?',SEMANTIC",
        "'Compare claude-opus-5 and claude-sonnet-5 on max output tokens.',BOTH",
        "'What is the difference between BM25 and vector search?',SEMANTIC",
        "'Tell me about claude-opus-5',BOTH",
    })
    void routes_each_kind_of_question(String query, String expected) {
        assertThat(Router.route(query).route()).isEqualTo(expected);
    }

    @Test
    void the_rationale_is_part_of_the_audit_trail() {
        Route decision = Router.route("What is the exact context window of claude-opus-5?");
        assertThat(decision.rationale()).contains("must not be ranked");
        assertThat(decision.asMap()).containsKeys("route", "rationale");
    }

    // --- provenance ------------------------------------------------------------------

    @Test
    void a_deterministic_citation_is_never_scored() {
        Envelope envelope = service()
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", FRESH, null);
        Citation cite = envelope.citations().getFirst();
        assertThat(cite.score()).isNull();
        assertThat(cite.method()).isEqualTo("exact-lookup");
        assertThat(cite.trustTier()).isEqualTo("human-reviewed");
    }

    @Test
    void the_envelope_forces_a_refusal_when_nothing_is_cited() {
        Envelope envelope = Envelope.grounded("a confident guess", List.of(), "deterministic", null);
        assertThat(envelope.answer()).isEqualTo(Envelope.NOT_FOUND);
        assertThat(envelope.isRefusal()).isTrue();
    }

    @Test
    void rendering_matches_the_provenance_spec() {
        String rendered = Renderer.render(service()
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", FRESH, null));
        assertThat(rendered).contains("Answer: 1,000,000");
        assertThat(rendered).contains("↳ source: anthropic.claude-opus-5 · canonical.context_window_tokens");
        assertThat(rendered).contains("path: deterministic (exact-lookup) · human-reviewed 2026-08-10");
        assertThat(rendered).contains("fresh until 2026-09-09");
    }

    @Test
    void staleness_surfaces_inline_with_a_warning() {
        String rendered = Renderer.render(service()
                .lookupField("anthropic.claude-opus-5", "context_window_tokens", AFTER_STALE, null));
        assertThat(rendered).contains("⚠ STALE since 2026-09-09 — re-verify before relying on this");
    }

    @Test
    void a_refusal_says_so_rather_than_printing_nothing() {
        String rendered = Renderer.render(service()
                .lookupField("anthropic.claude-opus-5", "rate_limit_rpm", FRESH, null));
        assertThat(rendered).contains(Envelope.NOT_FOUND);
        assertThat(rendered).contains("no grounded source — nothing was returned rather than guessed.");
    }

    @Test
    void a_traversed_answer_shows_the_path_it_took() {
        String rendered = Renderer.render(
                service().lookupField("anthropic.claude-opus-5", "method", FRESH, null));
        assertThat(rendered).contains("traversed: anthropic.claude-opus-5 → anthropic.messages");
    }

    // --- service ---------------------------------------------------------------------

    @Test
    void ask_answers_a_precision_question_deterministically() {
        Envelope envelope = service().ask("What is the exact context window of claude-opus-5?", FRESH);
        assertThat(envelope.router().route()).isEqualTo(Route.DETERMINISTIC);
        assertThat(envelope.answer()).isEqualTo("1,000,000");
        assertThat(envelope.retrievalPath()).isEqualTo(Envelope.DETERMINISTIC);
    }

    @Test
    void an_unavailable_semantic_engine_is_a_refusal_not_an_error() {
        Envelope envelope = service().ask("How should I chunk documents for retrieval?", FRESH);
        assertThat(envelope.answer()).isEqualTo(Envelope.NOT_FOUND);
        assertThat(envelope.citations()).isEmpty();
        assertThat(envelope.router().route()).isEqualTo(Route.SEMANTIC);
    }

    @Test
    void a_mixed_result_leads_with_the_exact_hit() {
        Citation passage = new Citation("semantic", "elastic-rrf", "https://example.test",
                "chunk:1", "hybrid(bm25+elser,rrf)", 0.0931, "2026-08-13T00:00:00-07:00",
                null, null, null, false, List.of(), "rank_constant determines influence.");
        GroundedContextService svc = new GroundedContextService(
                Bundle.load(ROOT), (query, size) -> List.of(passage));

        Envelope envelope = svc.ask(
                "Compare claude-opus-5 and claude-sonnet-5 on max output tokens.", FRESH);

        assertThat(envelope.retrievalPath()).isEqualTo(Envelope.MIXED);
        assertThat(envelope.citations().getFirst().path()).isEqualTo(Citation.DETERMINISTIC);
        assertThat(envelope.citations()).hasSize(2);
        // The exact hit leads, so the block is still an Answer rather than a Top passage.
        assertThat(Renderer.render(envelope)).startsWith("router: BOTH");
        assertThat(Renderer.render(envelope)).contains("Answer: 128,000");
    }

    @Test
    void both_retrieves_once_per_query() {
        // The semantic arm is a network round trip, and per-path latency must report one of them.
        List<String> calls = new ArrayList<>();
        GroundedContextService svc = new GroundedContextService(Bundle.load(ROOT),
                (query, size) -> {
                    calls.add(query);
                    return List.of();
                });

        // Cross-entity, so the router says BOTH, and the bundle holds no single exact answer.
        String query = "Which of these models support vision?";
        assertThat(Router.route(query).route())
                .as("if routing changed, the assertion below would prove nothing")
                .isEqualTo(Route.BOTH);

        svc.ask(query, FRESH);

        assertThat(calls)
                .as("the semantic arm ran %d times for one query", calls.size())
                .containsExactly(query);
    }

    @Test
    void a_semantic_only_result_is_labelled_a_passage_not_an_answer() {
        Citation passage = new Citation("semantic", "elastic-rrf", null, "chunk:1",
                "hybrid(bm25+elser,rrf)", 0.0889, "2026-08-13T00:00:00-07:00",
                null, null, null, false, List.of(), "Reciprocal rank fusion combines rankings.");
        GroundedContextService svc = new GroundedContextService(
                Bundle.load(ROOT), (query, size) -> List.of(passage));

        String rendered = Renderer.render(
                svc.ask("How should I chunk documents for retrieval?", FRESH));

        assertThat(rendered).contains("Top passage: Reciprocal rank fusion combines rankings.");
        assertThat(rendered).contains("indexed 2026-08-13");
        assertThat(rendered).contains("score 0.0889");
    }

    @Test
    void formats_values_for_reading() {
        assertThat(GroundedContextService.formatValue(1_000_000)).isEqualTo("1,000,000");
        assertThat(GroundedContextService.formatValue(true)).isEqualTo("yes");
        assertThat(GroundedContextService.formatValue(false)).isEqualTo("no");
        assertThat(GroundedContextService.formatValue("/v1/messages")).isEqualTo("/v1/messages");
    }

    @Test
    void the_wire_form_keys_match_the_provenance_contract() {
        Optional<Citation> cite = service()
                .lookupField("anthropic.messages", "path", FRESH, null)
                .citations().stream().findFirst();
        assertThat(cite).isPresent();
        assertThat(cite.get().asMap()).containsOnlyKeys(
                "path", "source_id", "source_url", "locator", "method", "score", "verified_at",
                "trust_tier", "status", "stale_after", "is_stale", "hops", "snippet");
    }
}
