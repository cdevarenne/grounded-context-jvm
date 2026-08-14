package io.github.cdevarenne.gctx.app;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the knowledge bundle lives.
 *
 * <p>Resolution order matches the Python implementation: an explicit path, then {@code GC_BUNDLE},
 * then a search upward from the working directory. The upward walk is what lets the CLI work from
 * anywhere in the repo without configuration, which matters because the demo is run by hand.
 */
public final class BundleLocator {

    public static final String ENV_VAR = "GC_BUNDLE";
    static final String DIRECTORY = "knowledge";
    private static final int MAX_PARENTS = 5;

    private BundleLocator() {
    }

    public static Path resolve(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String fromEnv = System.getenv(ENV_VAR);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return search();
    }

    private static Path search() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth <= MAX_PARENTS && candidate != null; depth++) {
            Path bundle = candidate.resolve(DIRECTORY);
            if (Files.isDirectory(bundle)) {
                return bundle;
            }
            candidate = candidate.getParent();
        }
        // Nothing found: hand back the conventional location so the error names a real path.
        return Path.of("").toAbsolutePath().resolve(DIRECTORY);
    }
}
