package io.github.cdevarenne.gctx.bundle;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One OKF document: the unit that carries both a fact and the evidence for it.
 *
 * @param canonical exact values that must never be inferred or ranked
 * @param links     Markdown links, per the OKF v0.2 convention, used for one-hop traversal
 * @param aliases   local extension: names a person might use when asking about this concept
 */
public record Concept(
        Path path,
        String id,
        String type,
        String title,
        Map<String, Object> canonical,
        List<Map<String, Object>> sources,
        List<Map<String, Object>> verified,
        Map<String, Object> generated,
        String status,
        LocalDate staleAfter,
        List<String> links,
        List<String> aliases,
        String body) {

    /** Markdown link, e.g. {@code [Claude Opus 5](../models/anthropic-claude-opus-5.md)}. */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)]+)\\)");

    public Concept {
        canonical = Map.copyOf(canonical);
        sources = List.copyOf(sources);
        verified = List.copyOf(verified);
        generated = Map.copyOf(generated);
        links = List.copyOf(links);
        aliases = List.copyOf(aliases);
    }

    public TrustTier trustTier() {
        return TrustTier.from(verified);
    }

    /** The most recent verification timestamp, or empty when unverified. */
    public Optional<String> verifiedAt() {
        return verified.stream()
                .filter(entry -> entry.containsKey("at"))
                .map(entry -> String.valueOf(entry.get("at")))
                .max(Comparator.naturalOrder());
    }

    public Optional<String> sourceUrl() {
        return sources.stream()
                .filter(source -> source.containsKey("resource"))
                .map(source -> String.valueOf(source.get("resource")))
                .findFirst();
    }

    /** OKF v0.2: a concept is stale once {@code asOf} reaches {@code stale_after}. */
    public boolean isStale(LocalDate asOf) {
        return staleAfter != null && !asOf.isBefore(staleAfter);
    }

    /**
     * Resolve this concept's Markdown links to absolute paths inside the bundle.
     *
     * <p>A link that escapes the bundle root is rejected rather than followed. The bundle is
     * the trust boundary of the deterministic path, so traversal must not be able to leave it
     * via a crafted {@code ../} link.
     */
    public List<Path> linkTargets(Path root) {
        Path canonicalRoot = root.toAbsolutePath().normalize();
        List<Path> targets = new ArrayList<>();
        for (String link : links) {
            Matcher match = MARKDOWN_LINK.matcher(link);
            if (!match.find()) {
                continue;
            }
            Path resolved = path.toAbsolutePath().getParent()
                    .resolve(match.group(1)).normalize();
            if (!resolved.startsWith(canonicalRoot)) {
                throw new BundleException(
                        path + ": link escapes the bundle root: " + match.group(1));
            }
            targets.add(resolved);
        }
        return List.copyOf(targets);
    }
}
