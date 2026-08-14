package io.github.cdevarenne.gctx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Records how many tests this module actually ran, so the README's figure can be checked.
 *
 * <p>A hand-typed count drifts the moment a test is added — it did so three times in the Python
 * repo before a check existed there.
 *
 * <p>It counts <em>executions</em>, deliberately. The obvious alternative,
 * {@code TestPlan.countTestIdentifiers} at plan start, silently ignores every
 * {@code @ParameterizedTest}: those expand dynamically at execution time, so a plan-start count
 * matches the number of plain {@code @Test} methods and would not move when a parameterized case
 * is added. Counting executions is what the build actually reports.
 *
 * <p>Written under each module's {@code target/} because the README states a total across both
 * modules and no single module can see both. The comparison runs after tests, in
 * {@code ReadmeCountCheck} — a test cannot check its own run's final total while that run is
 * still in progress.
 */
public class TestCountRecorder implements TestExecutionListener {

    public static final String FILE_NAME = "test-count.txt";

    private final AtomicLong executed = new AtomicLong();

    @Override
    public void executionFinished(TestIdentifier identifier, org.junit.platform.engine.TestExecutionResult result) {
        if (identifier.isTest()) {
            executed.incrementAndGet();
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        try {
            Path target = Path.of("target");
            Files.createDirectories(target);
            Files.writeString(target.resolve(FILE_NAME), Long.toString(executed.get()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
