package io.github.cdevarenne.gctx.app.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdevarenne.gctx.telemetry.TelemetryEvent;
import io.github.cdevarenne.gctx.telemetry.TelemetrySink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

/**
 * The primary sink: one JSON line appended to a local log.
 *
 * <p>No network and no cluster, which is what keeps the zero-cloud guarantee intact — with no
 * Elasticsearch configured the event still lands here, and the deterministic path still makes no
 * network call. This log is the source of truth; the Elasticsearch index is a projection over it,
 * rebuildable from it, and never the reverse.
 */
public final class NdjsonTelemetrySink implements TelemetrySink {

    /** Turns telemetry off entirely. Any of {@code 0 false no off}. */
    public static final String ENABLED_VAR = "GCTX_TELEMETRY";

    /** Overrides where the log is written. */
    public static final String SINK_VAR = "GCTX_TELEMETRY_SINK";

    public static final String DIRECTORY = "var";
    public static final String FILE_NAME = "telemetry.ndjson";

    private static final Set<String> DISABLED = Set.of("0", "false", "no", "off");

    /** The marker that identifies the repo root, so the log does not follow the caller's cwd. */
    private static final String ROOT_MARKER = "knowledge";
    private static final int MAX_PARENTS = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;

    public NdjsonTelemetrySink() {
        this(resolve(null));
    }

    public NdjsonTelemetrySink(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    /** Telemetry is on unless {@link #ENABLED_VAR} turns it off. */
    public static boolean isEnabled() {
        String value = System.getenv(ENABLED_VAR);
        return value == null || !DISABLED.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolve the log: an explicit path, then {@link #SINK_VAR}, then {@code var/} at the repo root.
     *
     * <p>The default is anchored to the repo rather than the working directory. The MCP server is
     * spawned by its client from wherever that client happens to sit, and a relative path would
     * scatter the log across the filesystem. {@code GC_BUNDLE} deliberately does not move it: a
     * bundle pointed somewhere else is still this repo's run.
     */
    public static Path resolve(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String fromEnv = System.getenv(SINK_VAR);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return repoRoot().resolve(DIRECTORY).resolve(FILE_NAME);
    }

    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth <= MAX_PARENTS && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve(ROOT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    /**
     * Append one event.
     *
     * <p>Throws on a broken disk rather than swallowing: {@code Telemetry.record} is the guard, and
     * putting a second one here would hide a sink that has stopped working from its own tests.
     */
    @Override
    public void emit(TelemetryEvent event) {
        if (!isEnabled()) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, mapper.writeValueAsString(event.asMap()) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
