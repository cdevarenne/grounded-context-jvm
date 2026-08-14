package io.github.cdevarenne.gctx.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The knowledge bundle is duplicated, so this is what stops the copies drifting.
 *
 * <p>This repo carries its own {@code knowledge/} because it has to run standalone, which makes
 * it a second source of truth. Two copies of the same facts with no check between them is the
 * same failure mode as a hand-typed count in a README: it separates silently, and the first
 * symptom is two implementations giving different answers to the same question.
 *
 * <p>The check runs only when the Python repo is checked out alongside — set
 * {@code GCTX_REFERENCE_BUNDLE} to point elsewhere. It is skipped rather than failed when
 * absent, because a clone of this repo alone is a legitimate way to work.
 */
class BundleParityTest {

    static final Path LOCAL = Path.of("..", "knowledge");
    static final String REFERENCE_VAR = "GCTX_REFERENCE_BUNDLE";

    /** Default: the Python repo as a sibling checkout, which is how the two are developed. */
    static final Path DEFAULT_REFERENCE = Path.of("..", "..", "grounded-context", "knowledge");

    static Path reference() {
        String configured = System.getenv(REFERENCE_VAR);
        return configured == null || configured.isBlank()
                ? DEFAULT_REFERENCE
                : Path.of(configured);
    }

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean referenceBundleAvailable() {
        return Files.isDirectory(reference());
    }

    /** Relative path to content hash, so a diff names the file that moved. */
    private static Map<String, String> digestByRelativePath(Path root) {
        Map<String, String> digests = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(path -> digests.put(
                            root.relativize(path).toString().replace('\\', '/'), sha256(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return digests;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @EnabledIf("referenceBundleAvailable")
    void the_bundle_is_byte_identical_to_the_python_repos_copy() {
        Map<String, String> local = digestByRelativePath(LOCAL);
        Map<String, String> canonical = digestByRelativePath(reference());

        assertThat(local.keySet())
                .as("bundle files present in one copy but not the other")
                .containsExactlyInAnyOrderElementsOf(canonical.keySet());

        assertThat(local)
                .as("bundle contents have drifted between the two repos — reconcile before "
                        + "trusting either implementation's answers")
                .containsExactlyInAnyOrderEntriesOf(canonical);
    }

    @Test
    @EnabledIf("referenceBundleAvailable")
    void both_copies_parse_to_the_same_concepts_and_canonical_values() {
        // A byte comparison catches edits; this catches the case that actually matters — the
        // two implementations answering differently — without depending on formatting.
        Bundle local = Bundle.load(LOCAL);
        Bundle canonical = Bundle.load(reference());

        assertThat(local.concepts()).extracting(Concept::id)
                .containsExactlyInAnyOrderElementsOf(
                        canonical.concepts().stream().map(Concept::id).toList());

        for (Concept concept : canonical) {
            Concept mirror = local.get(concept.id()).orElseThrow();
            assertThat(mirror.canonical()).as(concept.id()).isEqualTo(concept.canonical());
            assertThat(mirror.staleAfter()).as(concept.id()).isEqualTo(concept.staleAfter());
            assertThat(mirror.trustTier()).as(concept.id()).isEqualTo(concept.trustTier());
            assertThat(mirror.aliases()).as(concept.id()).isEqualTo(concept.aliases());
        }
    }
}
