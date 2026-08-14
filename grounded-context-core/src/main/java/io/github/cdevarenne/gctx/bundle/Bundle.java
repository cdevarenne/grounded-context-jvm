package io.github.cdevarenne.gctx.bundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An indexed OKF bundle, addressable by concept id.
 *
 * <p>Markdown on disk is the source of truth. Loading touches the filesystem and nothing else:
 * no network, no cache, no inference. Link integrity is checked at load rather than at
 * traversal, so a broken bundle fails loudly at startup instead of silently answering less.
 */
public final class Bundle implements Iterable<Concept> {

    private final Path root;
    private final Map<String, Concept> concepts;
    private final Map<Path, Concept> byPath;

    private Bundle(Path root, Map<String, Concept> concepts) {
        this.root = root;
        this.concepts = Map.copyOf(concepts);
        Map<Path, Concept> index = new HashMap<>();
        concepts.values().forEach(c -> index.put(c.path().toAbsolutePath().normalize(), c));
        this.byPath = Map.copyOf(index);
    }

    public static Bundle load(Path root) {
        if (!Files.isDirectory(root)) {
            throw new BundleException("bundle root not found: " + root);
        }

        Map<String, Concept> concepts = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> markdown = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path path : markdown) {
                Concept concept = ConceptParser.parse(path);
                Concept existing = concepts.putIfAbsent(concept.id(), concept);
                if (existing != null) {
                    throw new BundleException("duplicate concept id '" + concept.id() + "': "
                            + existing.path() + " and " + path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Bundle bundle = new Bundle(root, concepts);
        for (Concept concept : concepts.values()) {
            for (Path target : concept.linkTargets(root)) {
                if (!bundle.byPath.containsKey(target)) {
                    throw new BundleException(
                            concept.path() + ": link target missing: " + target);
                }
            }
        }
        return bundle;
    }

    public Path root() {
        return root;
    }

    public Optional<Concept> get(String conceptId) {
        return Optional.ofNullable(concepts.get(conceptId));
    }

    /** The concepts this one links to, in declaration order. */
    public List<Concept> linked(String conceptId) {
        return get(conceptId)
                .map(concept -> concept.linkTargets(root).stream()
                        .map(byPath::get)
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .orElseGet(List::of);
    }

    public Collection<Concept> concepts() {
        return concepts.values();
    }

    public int size() {
        return concepts.size();
    }

    @Override
    public Iterator<Concept> iterator() {
        return concepts.values().iterator();
    }
}
