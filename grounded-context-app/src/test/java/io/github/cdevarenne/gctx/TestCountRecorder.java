package io.github.cdevarenne.gctx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Records how many tests this module ran, so the README's figure can be checked.
 *
 * <p>A hand-typed count drifts the moment a test is added — it did so three times in the Python
 * repo before a check existed there.
 *
 * <p>It counts <em>executions</em> rather than planned identifiers. The obvious alternative,
 * {@code TestPlan.countTestIdentifiers} at plan start, silently ignores every
 * {@code @ParameterizedTest}: those expand dynamically, so a plan-start count matches the number
 * of plain {@code @Test} methods and would not move when a parameterized case is added.
 *
 * <p>The flip side is that a count of executions only describes a <em>complete</em> run. A
 * class-level {@code @EnabledIf} skips the whole container, so the tests inside it are never
 * expanded and never reported at all — no amount of counting recovers them. The recorder
 * therefore also reports whether anything was skipped, and the check stands down when something
 * was, the same way every cluster-dependent assertion in this repo does.
 */
public class TestCountRecorder implements TestExecutionListener {

    public static final String FILE_NAME = "test-count.txt";

    /** Written when the run was partial, so a published total is not compared against it. */
    public static final String PARTIAL = "partial";

    private final AtomicLong seen = new AtomicLong();
    private final AtomicBoolean partial = new AtomicBoolean();

    @Override
    public void executionFinished(
            TestIdentifier identifier, org.junit.platform.engine.TestExecutionResult result) {
        if (identifier.isTest()) {
            seen.incrementAndGet();
        }
    }

    /** Any skip at all — test or container — means this run cannot confirm the published total. */
    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
        partial.set(true);
        if (identifier.isTest()) {
            seen.incrementAndGet();
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        try {
            Path target = Path.of("target");
            Files.createDirectories(target);
            Files.writeString(target.resolve(FILE_NAME),
                    partial.get() ? PARTIAL : Long.toString(seen.get()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
