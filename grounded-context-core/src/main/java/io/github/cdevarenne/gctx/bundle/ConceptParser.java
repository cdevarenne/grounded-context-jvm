package io.github.cdevarenne.gctx.bundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parse one Markdown file with YAML front matter into a {@link Concept}. */
public final class ConceptParser {

    private ConceptParser() {
    }

    public static Concept parse(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        FrontMatter.Parsed parsed = FrontMatter.parse(text)
                .orElseThrow(() -> new BundleException(path + ": no YAML front matter"));
        Map<String, Object> meta = parsed.meta();

        // `type` is OKF's only always-required key; `id` is our lookup key.
        for (String required : List.of("type", "id")) {
            Object value = meta.get(required);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new BundleException(path + ": missing required field '" + required + "'");
            }
        }

        String id = String.valueOf(meta.get("id"));
        return new Concept(
                path,
                id,
                String.valueOf(meta.get("type")),
                String.valueOf(meta.getOrDefault("title", id)),
                mapping(meta.get("canonical")),
                listOfMappings(meta.get("sources")),
                verifiedEntries(meta.get("verified")),
                mapping(meta.get("generated")),
                String.valueOf(meta.getOrDefault("status", "stable")),
                staleAfter(path, meta.get("stale_after")),
                strings(meta.get("links")),
                strings(meta.get("aliases")),
                parsed.body().strip());
    }

    /**
     * OKF lifecycle dates are absolute, never a relative TTL, so anything that is not a plain
     * date is rejected rather than coerced — a misread lifecycle silently disables staleness.
     */
    private static LocalDate staleAfter(Path path, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw new BundleException(
                    path + ": stale_after must be a YYYY-MM-DD date, got '" + value + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMappings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                entries.add((Map<String, Object>) map);
            }
        }
        return entries;
    }

    /** OKF permits a single mapping where a list is expected; normalize to a list. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> verifiedEntries(Object value) {
        if (value instanceof Map<?, ?> map) {
            return List.of((Map<String, Object>) map);
        }
        return listOfMappings(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
