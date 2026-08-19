package io.github.cdevarenne.gctx.app.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The committed telemetry fixtures, loaded from the classpath.
 *
 * <p>Both are the Python repo's files, copied here so this repo stands alone and guarded by
 * {@link TelemetryParityTest}. Loading them by resource rather than by relative path keeps the
 * tests independent of which build tool set the working directory.
 */
public final class TelemetryFixtures {

    public static final String SAMPLE = "/telemetry/telemetry-sample.ndjson";
    public static final String GOLDEN = "/telemetry/telemetry-summary.golden.txt";

    private TelemetryFixtures() {
    }

    public static String read(String resource) {
        try (InputStream stream = TelemetryFixtures.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sample session written to {@code <dir>/var/telemetry.ndjson}. */
    public static Path sampleLog(Path dir) throws IOException {
        Path log = dir.resolve("var").resolve("telemetry.ndjson");
        Files.createDirectories(log.getParent());
        Files.writeString(log, read(SAMPLE), StandardCharsets.UTF_8);
        return log;
    }
}
