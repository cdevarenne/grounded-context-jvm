package io.github.cdevarenne.gctx.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bundle loading tests.
 *
 * <p>These run against the committed {@code knowledge/} bundle rather than fixtures wherever
 * the claim is about real data, because the bundle is the source of truth the whole
 * deterministic path rests on.
 */
class BundleTest {

    static final Path ROOT = Path.of("..", "knowledge");

    @Test
    void loads_the_committed_bundle() {
        Bundle bundle = Bundle.load(ROOT);
        assertThat(bundle.size()).isEqualTo(4);
        assertThat(bundle.concepts()).extracting(Concept::id)
                .containsExactlyInAnyOrder(
                        "anthropic.claude-opus-5",
                        "anthropic.claude-sonnet-5",
                        "anthropic.claude-haiku-4-5",
                        "anthropic.messages");
    }

    @Test
    void reads_canonical_values_with_their_types_intact() {
        Concept opus = Bundle.load(ROOT).get("anthropic.claude-opus-5").orElseThrow();
        assertThat(opus.canonical().get("context_window_tokens")).isEqualTo(1_000_000);
        assertThat(opus.canonical().get("model_string")).isEqualTo("claude-opus-5");
        assertThat(opus.canonical().get("vision")).isEqualTo(true);
    }

    @Test
    void derives_the_trust_tier_from_the_verified_actors() {
        Concept opus = Bundle.load(ROOT).get("anthropic.claude-opus-5").orElseThrow();
        assertThat(opus.trustTier()).isEqualTo(TrustTier.HUMAN_REVIEWED);
        assertThat(opus.trustTier().label()).isEqualTo("human-reviewed");
        assertThat(opus.verifiedAt()).contains("2026-08-10T19:06:23-07:00");
    }

    @Test
    void staleness_is_an_absolute_date_not_a_ttl() {
        Concept opus = Bundle.load(ROOT).get("anthropic.claude-opus-5").orElseThrow();
        assertThat(opus.staleAfter()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(opus.isStale(LocalDate.of(2026, 9, 8))).isFalse();
        // OKF v0.2: stale once the date is reached, not after it passes.
        assertThat(opus.isStale(LocalDate.of(2026, 9, 9))).isTrue();
        assertThat(opus.isStale(LocalDate.of(2026, 10, 1))).isTrue();
    }

    @Test
    void resolves_markdown_links_to_real_concepts() {
        Bundle bundle = Bundle.load(ROOT);
        assertThat(bundle.linked("anthropic.claude-opus-5")).extracting(Concept::id)
                .contains("anthropic.messages");
    }

    @Test
    void rejects_a_file_without_front_matter(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("broken.md"), "# just a heading\n");
        assertThatThrownBy(() -> Bundle.load(dir))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("no YAML front matter");
    }

    @Test
    void rejects_a_missing_required_field(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("no-id.md"), """
                ---
                type: model
                title: No id here
                ---
                body
                """);
        assertThatThrownBy(() -> Bundle.load(dir))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("missing required field 'id'");
    }

    @Test
    void rejects_a_relative_ttl_where_a_date_belongs(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("ttl.md"), """
                ---
                type: model
                id: x.y
                stale_after: 30d
                ---
                body
                """);
        assertThatThrownBy(() -> Bundle.load(dir))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("stale_after must be a YYYY-MM-DD date");
    }

    @Test
    void rejects_a_link_that_escapes_the_bundle_root(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("escape.md"), """
                ---
                type: model
                id: x.y
                links:
                  - "[outside](../../etc/passwd)"
                ---
                body
                """);
        assertThatThrownBy(() -> Bundle.load(dir))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("link escapes the bundle root");
    }

    @Test
    void rejects_duplicate_concept_ids(@TempDir Path dir) throws IOException {
        String same = """
                ---
                type: model
                id: duplicated.id
                ---
                body
                """;
        Files.writeString(dir.resolve("a.md"), same);
        Files.writeString(dir.resolve("b.md"), same);
        assertThatThrownBy(() -> Bundle.load(dir))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("duplicate concept id");
    }
}
