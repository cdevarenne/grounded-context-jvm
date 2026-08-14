package io.github.cdevarenne.gctx.bundle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A Markdown file with YAML front matter.
 *
 * <p>Two kinds of file use this shape: the curated knowledge bundle, and the fetched corpus
 * pages the indexer reads. The parsing rule is therefore a shared contract rather than a detail
 * of either, and lives here so the two cannot drift apart.
 */
public final class FrontMatter {

    /** {@code ---\n<yaml>\n---\n<body>}, matched against the whole file. */
    public static final Pattern PATTERN =
            Pattern.compile("\\A---\\n(.*?)\\n---\\n?(.*)\\z", Pattern.DOTALL);

    /** The parsed front matter and the body text that follows it. */
    public record Parsed(Map<String, Object> meta, String body) {
    }

    private FrontMatter() {
    }

    /** Parse a file's text, or empty when it carries no front matter. */
    public static Optional<Parsed> parse(String text) {
        Matcher match = PATTERN.matcher(text);
        if (!match.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(load(match.group(1)), match.group(2)));
    }

    /**
     * Load the YAML mapping, keeping timestamp-shaped scalars as text.
     *
     * <p>Safe despite the bare {@code load}: {@link LiteralTimestampResolver}'s loader derives
     * from {@link SafeConstructor}, so it cannot instantiate arbitrary types. It differs from a
     * plain safe load in exactly one respect — it does not resolve timestamps. See
     * {@link LiteralTimestampResolver} for the two defects that earns.
     */
    static Map<String, Object> load(String yaml) {
        Yaml parser = new Yaml(
                new SafeConstructor(new LoaderOptions()),
                new org.yaml.snakeyaml.representer.Representer(new DumperOptions()),
                new DumperOptions(),
                new LoaderOptions(),
                new LiteralTimestampResolver());
        Object loaded = parser.load(yaml);
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new BundleException("front matter is not a mapping");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        map.forEach((key, value) -> meta.put(String.valueOf(key), value));
        return meta;
    }
}
