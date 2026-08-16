package io.github.cdevarenne.gctx.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The repo declares its dependency versions twice, so something has to compare the copies.
 *
 * <p>Maven puts them in {@code <properties>} and Gradle in {@code gradle/libs.versions.toml}. The
 * README tells an adopting team the two builds produce the same thing, which stops being true the
 * moment someone bumps one file. That is the same failure the bundle and index-spec copies have,
 * and it gets the same answer: a test between them.
 *
 * <p>The comparison is a set equality rather than a lookup table, so it also catches a version
 * added to one build and not the other. That works because the catalog aliases are named after
 * the Maven properties with {@code .version} removed — a mapping table here would be a third copy
 * of the same knowledge.
 *
 * <p>It skips when either build is absent: a team that adopts this repo and standardizes on one
 * build tool should be able to delete the other without turning the suite red.
 */
class BuildParityTest {

    static final Path PARENT_POM = Path.of("..", "pom.xml");
    static final Path APP_POM = Path.of("pom.xml");
    static final Path CATALOG = Path.of("..", "gradle", "libs.versions.toml");
    static final Path ROOT_BUILD_SCRIPT = Path.of("..", "build.gradle.kts");

    /** {@code <snakeyaml.version>2.5</snakeyaml.version>} — matched tags, so references miss. */
    static final Pattern POM_VERSION =
            Pattern.compile("<([\\w.-]+)\\.version>([^<]+)</\\1\\.version>");

    /** {@code snakeyaml = "2.5"}, applied only to the {@code [versions]} section. */
    static final Pattern CATALOG_VERSION =
            Pattern.compile("^([\\w.-]+) *= *\"([^\"]+)\"", Pattern.MULTILINE);

    static final Pattern POM_RELEASE =
            Pattern.compile("<maven\\.compiler\\.release>(\\d+)</maven\\.compiler\\.release>");

    static final Pattern GRADLE_RELEASE = Pattern.compile("options\\.release *= *(\\d+)");

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean bothBuildsPresent() {
        return Files.isRegularFile(PARENT_POM) && Files.isRegularFile(CATALOG);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> matches(Pattern pattern, String text) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2).strip());
        }
        return found;
    }

    /** Everything from {@code [versions]} up to the next section header. */
    private static String versionsSection(String toml) {
        int start = toml.indexOf("[versions]");
        assertThat(start).as("the catalog has no [versions] section").isNotNegative();
        int end = toml.indexOf('[', start + "[versions]".length());
        return end < 0 ? toml.substring(start) : toml.substring(start, end);
    }

    private static String only(Pattern pattern, Path path) {
        Matcher matcher = pattern.matcher(read(path));
        assertThat(matcher.find()).as("%s no longer states a compiler release", path).isTrue();
        return matcher.group(1);
    }

    @Test
    @EnabledIf("bothBuildsPresent")
    void the_two_builds_declare_the_same_dependency_versions() {
        Map<String, String> maven = new LinkedHashMap<>();
        for (Path pom : List.of(PARENT_POM, APP_POM)) {
            maven.putAll(matches(POM_VERSION, read(pom)));
        }
        Map<String, String> gradle = matches(CATALOG_VERSION, versionsSection(read(CATALOG)));

        // Guards the guard: an enumeration that silently found nothing would pass trivially.
        assertThat(maven).as("no versions were extracted from the poms").isNotEmpty();

        assertThat(gradle)
                .as("pom.xml and gradle/libs.versions.toml have diverged — update both")
                .containsExactlyInAnyOrderEntriesOf(maven);
    }

    @Test
    @EnabledIf("bothBuildsPresent")
    void the_two_builds_compile_to_the_same_release() {
        // The floor a consumer must clear is a published claim in the README, and it is pinned
        // in two places: maven.compiler.release and options.release.
        assertThat(only(GRADLE_RELEASE, ROOT_BUILD_SCRIPT))
                .as("the Maven and Gradle builds target different Java releases")
                .isEqualTo(only(POM_RELEASE, PARENT_POM));
    }
}
