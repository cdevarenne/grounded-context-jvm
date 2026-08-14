package io.github.cdevarenne.gctx.app;

import io.github.cdevarenne.gctx.TestCountRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails the build when the README's test count no longer matches what ran.
 *
 * <p>Not a JUnit test, and that is the point: a test cannot know its own run's final total while
 * that run is still in progress. This executes at {@code verify}, once both modules have written
 * their counts, so the figure it checks is the one a reader would see.
 *
 * <p>The module directory arrives as an argument rather than being inferred from the working
 * directory: {@code exec:java} runs inside Maven's own JVM, so the process is at the reactor
 * root no matter which module declares it, and every relative path silently resolved to nothing.
 */
public final class ReadmeCountCheck {

    /** Matches the figure in the Status table, e.g. "✅ 97 tests; cluster tests skip…". */
    static final Pattern PUBLISHED = Pattern.compile("(\\d+) tests");

    private ReadmeCountCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("expected the module directory as an argument");
        }
        Path module = Path.of(args[0]).toAbsolutePath().normalize();
        Path repo = module.getParent();

        Path coreCount = repo.resolve("grounded-context-core")
                .resolve("target").resolve(TestCountRecorder.FILE_NAME);
        Path appCount = module.resolve("target").resolve(TestCountRecorder.FILE_NAME);
        Path readme = repo.resolve("README.md");

        if (!Files.isRegularFile(coreCount) || !Files.isRegularFile(appCount)) {
            // A silent skip that is really a broken path would defeat the whole check, so
            // name the paths that were looked in.
            System.out.println("README test count: skipped, no run recorded"
                    + " (looked in " + coreCount + " and " + appCount + ")");
            return;
        }

        String core = Files.readString(coreCount).strip();
        String app = Files.readString(appCount).strip();
        if (TestCountRecorder.PARTIAL.equals(core) || TestCountRecorder.PARTIAL.equals(app)) {
            // Without credentials the cluster tests skip, and a class-level skip hides its
            // tests entirely — so this run cannot confirm a total that describes a full one.
            System.out.println("README test count: skipped, this run was partial "
                    + "(no Elasticsearch credentials, or no reference bundle alongside)");
            return;
        }

        long ran = Long.parseLong(core) + Long.parseLong(app);
        Matcher matcher = PUBLISHED.matcher(Files.readString(readme));
        if (!matcher.find()) {
            throw new IllegalStateException("the README no longer states a test count");
        }
        long published = Long.parseLong(matcher.group(1));

        if (published != ran) {
            throw new IllegalStateException("README says " + published
                    + " tests, the build ran " + ran + " — update the README");
        }
        System.out.println("README test count: " + published + ", matches the build");
    }

}
