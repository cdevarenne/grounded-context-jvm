package io.github.cdevarenne.gctx.app.es;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Credentials for the semantic path.
 *
 * <p>Read from the environment, or from a gitignored {@code .env} found by walking up from the
 * working directory. They are never logged and never committed — the deterministic path needs
 * none of this, which is why nothing in the core module can reach these values.
 */
public final class ElasticsearchSettings {

    /** The corpus this project curates. An adopter points {@code ES_INDEX} at their own. */
    public static final String DEFAULT_INDEX = "grounded-context-corpus";

    /**
     * The index every command reads and writes.
     *
     * <p>Configurable because a team adopting this will have its own corpus, and hard-coding the
     * name would force them to edit the source. Resolved once at startup from {@code ES_INDEX},
     * falling back to the reference corpus.
     */
    public static final String INDEX = resolveIndex();

    /** Preconfigured ELSER endpoint on Elastic Cloud Serverless. */
    public static final String INFERENCE_ID = ".elser-2-elasticsearch";

    public static final String URL_VAR = "ES_URL";
    public static final String API_KEY_VAR = "ES_API_KEY";
    public static final String INDEX_VAR = "ES_INDEX";

    private static String resolveIndex() {
        String fromEnv = System.getenv(INDEX_VAR);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        String fromFile = fromEnvFile().getOrDefault(INDEX_VAR, "").strip();
        return fromFile.isEmpty() ? DEFAULT_INDEX : fromFile;
    }

    static final String ENV_FILE = ".env";
    private static final int MAX_PARENTS = 5;

    private final String url;
    private final String apiKey;

    private ElasticsearchSettings(String url, String apiKey) {
        this.url = url;
        this.apiKey = apiKey;
    }

    public String url() {
        return url;
    }

    /** Package-private: the key must not be reachable from logging or rendering code. */
    String apiKey() {
        return apiKey;
    }

    /**
     * Settings when both values are present, empty otherwise.
     *
     * <p>Absence is a normal state, not an error: a clone with no credentials must degrade to a
     * refusal on the exploratory branch rather than fail.
     */
    public static Optional<ElasticsearchSettings> discover() {
        Map<String, String> values = new LinkedHashMap<>(fromEnvFile());
        // A real environment variable wins over the file, matching the Python setdefault order.
        for (String key : new String[] {URL_VAR, API_KEY_VAR}) {
            String actual = System.getenv(key);
            if (actual != null && !actual.isBlank()) {
                values.put(key, actual);
            }
        }
        String url = values.getOrDefault(URL_VAR, "").strip();
        String apiKey = values.getOrDefault(API_KEY_VAR, "").strip();
        if (url.isEmpty() || apiKey.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ElasticsearchSettings(url, apiKey));
    }

    /** Names what is missing, never what is set — an error message must not leak a key. */
    public static String missingDescription() {
        Map<String, String> values = new LinkedHashMap<>(fromEnvFile());
        for (String key : new String[] {URL_VAR, API_KEY_VAR}) {
            String actual = System.getenv(key);
            if (actual != null && !actual.isBlank()) {
                values.put(key, actual);
            }
        }
        StringBuilder missing = new StringBuilder();
        for (String key : new String[] {URL_VAR, API_KEY_VAR}) {
            if (values.getOrDefault(key, "").isBlank()) {
                missing.append(missing.isEmpty() ? "" : ", ").append(key);
            }
        }
        return "missing " + missing + " — set it in the environment or in a gitignored .env";
    }

    static Map<String, String> fromEnvFile() {
        Optional<Path> file = locateEnvFile();
        if (file.isEmpty()) {
            return Map.of();
        }
        return parse(file.get());
    }

    static Map<String, String> parse(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int split = line.indexOf('=');
                values.put(line.substring(0, split).strip(), line.substring(split + 1).strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return values;
    }

    private static Optional<Path> locateEnvFile() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth <= MAX_PARENTS && candidate != null; depth++) {
            Path env = candidate.resolve(ENV_FILE);
            if (Files.isRegularFile(env)) {
                return Optional.of(env);
            }
            candidate = candidate.getParent();
        }
        return Optional.empty();
    }
}
