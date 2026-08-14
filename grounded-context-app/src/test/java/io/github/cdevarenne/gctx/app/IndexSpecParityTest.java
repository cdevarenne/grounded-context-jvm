package io.github.cdevarenne.gctx.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.app.es.CorpusIndexer;
import io.github.cdevarenne.gctx.app.es.HybridSemanticSearch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The index spec is duplicated across repos, so something has to compare the copies.
 *
 * <p>Each repo carries its own {@code docs/index-spec.md} so it stands alone — the same reasoning
 * that duplicates {@code knowledge/} here. Two copies of a contract diverge unless a test says
 * otherwise, and a diverged index spec means two implementations quietly building different
 * indices from the same corpus.
 *
 * <p>The constants check runs everywhere. The cross-repo comparison skips when the Python repo is
 * not checked out alongside; {@code GCTX_REFERENCE_REPO} overrides its location.
 */
class IndexSpecParityTest {

    static final Path SPEC = Path.of("..", "docs", "index-spec.md");
    static final Path DEFAULT_REFERENCE_REPO = Path.of("..", "..", "grounded-context");

    /** The copies differ on one line by design: each points at the other repo. */
    static final String CROSS_LINK = "grounded-context";

    static Path referenceSpec() {
        String configured = System.getenv("GCTX_REFERENCE_REPO");
        Path root = configured == null || configured.isBlank()
                ? DEFAULT_REFERENCE_REPO : Path.of(configured);
        return root.resolve("docs").resolve("index-spec.md");
    }

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean referenceSpecAvailable() {
        return Files.isRegularFile(referenceSpec());
    }

    private static List<String> lines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @EnabledIf("referenceSpecAvailable")
    void the_two_index_specs_agree_apart_from_their_cross_link() {
        List<String> ours = lines(SPEC);
        List<String> theirs = lines(referenceSpec());

        assertThat(ours).as("the index specs have diverged in length").hasSameSizeAs(theirs);

        List<String> differing = new ArrayList<>();
        for (int i = 0; i < ours.size(); i++) {
            if (!ours.get(i).equals(theirs.get(i))) {
                differing.add((i + 1) + ": " + ours.get(i) + "  ||  " + theirs.get(i));
            }
        }
        assertThat(differing)
                .as("only the cross-link line may differ between the two copies")
                .hasSizeLessThanOrEqualTo(1);
        differing.forEach(line -> assertThat(line).contains(CROSS_LINK));
    }

    @Test
    void the_spec_states_the_constants_this_implementation_uses() {
        // A spec that drifts from the code is worse than no spec: it is a confident wrong answer.
        String text = String.join("\n", lines(SPEC));

        assertThat(text).contains("`TARGET_CHUNK_CHARS` | " + CorpusIndexer.TARGET_CHUNK_CHARS);
        assertThat(text).contains("`MIN_CHUNK_CHARS` | " + CorpusIndexer.MIN_CHUNK_CHARS);
        assertThat(text).contains("`RANK_CONSTANT` (RRF `k`) | " + HybridSemanticSearch.RANK_CONSTANT);
        assertThat(text).contains("`RANK_WINDOW_SIZE` | " + HybridSemanticSearch.RANK_WINDOW_SIZE);
        assertThat(text).contains("`EXACT_TOKEN_BOOST` | " + HybridSemanticSearch.EXACT_TOKEN_BOOST);
        assertThat(text).contains("`RELEVANCE_FLOOR` | " + HybridSemanticSearch.RELEVANCE_FLOOR);
    }
}
