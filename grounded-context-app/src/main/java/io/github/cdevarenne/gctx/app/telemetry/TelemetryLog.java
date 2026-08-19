package io.github.cdevarenne.gctx.app.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reading the newline-delimited log back.
 *
 * <p>Jackson rather than the hand-rolled {@code JsonWriter}: that class exists to keep the envelope
 * wire format under the same review as the contract it serializes, and it only writes. Parsing
 * arbitrary JSON — including a log an older version wrote — is not a place to hand-roll.
 */
public final class TelemetryLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> EVENT = new TypeReference<>() { };

    private TelemetryLog() {
    }

    /** Every event in the log, oldest first. A missing log is empty, not an error. */
    public static List<Map<String, Object>> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(MAPPER.readValue(line, EVENT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return events;
    }
}
