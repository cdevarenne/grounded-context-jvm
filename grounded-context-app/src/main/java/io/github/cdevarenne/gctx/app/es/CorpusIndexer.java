package io.github.cdevarenne.gctx.app.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import io.github.cdevarenne.gctx.bundle.FrontMatter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Build the semantic index from a directory of fetched corpus pages.
 *
 * <p>One document per chunk, not per page: the citation contract cites {@code chunk:N} with a
 * snippet, so the unit indexed has to be the unit cited. Each chunk carries its source metadata,
 * and {@code content} is copied into a {@code semantic_text} field so the same text is reachable
 * both lexically (BM25) and semantically (ELSER). That is what makes the RRF fusion meaningful.
 *
 * <p>The index is a rebuildable projection. Markdown on disk stays the source of truth.
 *
 * <p>This exists so a JVM team can stand up the whole system without running the Python tooling.
 * The chunking and mapping match {@code scripts/index_corpus.py} exactly — a team whose index is
 * built differently would get different ranks from the same corpus, and the reference numbers
 * would stop meaning anything.
 */
public final class CorpusIndexer {

    public static final int TARGET_CHUNK_CHARS = 1200;
    public static final int MIN_CHUNK_CHARS = 200;

    /**
     * The standard analyzer strips punctuation and splits on hyphens, so {@code claude-opus-5}
     * shatters and a code sample's {@code "rank_constant":} collapses onto the prose mention.
     * This subfield lowercases and splits on whitespace only, keeping both intact.
     */
    static final String SETTINGS = """
            {"analysis":{"analyzer":{"exact_token":{"tokenizer":"whitespace","filter":["lowercase"]}}}}
            """;

    private static final String MAPPING_TEMPLATE = """
            {"properties":{
              "source_id":{"type":"keyword"},
              "title":{"type":"text","fields":{"keyword":{"type":"keyword"}}},
              "url":{"type":"keyword"},
              "provider":{"type":"keyword"},
              "topic":{"type":"keyword"},
              "chunk_index":{"type":"integer"},
              "fetched_at":{"type":"date"},
              "content":{"type":"text","copy_to":"semantic",
                         "fields":{"exact":{"type":"text","analyzer":"exact_token"}}},
              "semantic":{"type":"semantic_text","inference_id":"%s"}
            }}
            """;

    /** One indexed chunk: its document id and the source fields it carries. */
    public record Document(String id, Map<String, Object> source) {
    }

    private final ElasticsearchClient client;

    public CorpusIndexer(ElasticsearchClient client) {
        this.client = client;
    }

    // --- chunking --------------------------------------------------------------------

    /**
     * Pack consecutive lines into passages of roughly {@link #TARGET_CHUNK_CHARS}.
     *
     * <p>Extracted documentation text arrives as many short lines rather than prose paragraphs,
     * so packing lines is what produces passages a person would recognize as a passage — and a
     * citation snippet has to be quotable.
     */
    public static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int size = 0;

        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (size > 0 && size + line.length() > TARGET_CHUNK_CHARS) {
                chunks.add(String.join("\n", current));
                current = new ArrayList<>();
                size = 0;
            }
            current.add(line);
            size += line.length() + 1;
        }
        if (!current.isEmpty()) {
            chunks.add(String.join("\n", current));
        }
        return chunks.stream().filter(c -> c.length() >= MIN_CHUNK_CHARS).toList();
    }

    /** One document per chunk of every page in the corpus directory, in file-name order. */
    public static List<Document> documents(Path corpus) {
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> files = Files.list(corpus)) {
            List<Path> pages = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();

            for (Path page : pages) {
                String text = Files.readString(page, StandardCharsets.UTF_8);
                var parsed = FrontMatter.parse(text);
                if (parsed.isEmpty()) {
                    System.err.println("skip " + page.getFileName() + ": no front matter");
                    continue;
                }
                Map<String, Object> meta = parsed.get().meta();
                List<String> passages = chunk(parsed.get().body());
                for (int index = 0; index < passages.size(); index++) {
                    documents.add(new Document(
                            meta.get("id") + "::" + index,
                            source(meta, index, passages.get(index))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return documents;
    }

    private static Map<String, Object> source(Map<String, Object> meta, int index, String passage) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_id", meta.get("id"));
        source.put("title", meta.get("title"));
        source.put("url", meta.get("url"));
        source.put("provider", meta.get("provider"));
        source.put("topic", meta.get("topic"));
        source.put("chunk_index", index);
        // Kept as written. The value is a date the fetcher recorded, not one to reinterpret.
        source.put("fetched_at", String.valueOf(meta.get("fetched_at")));
        source.put("content", passage);
        return source;
    }

    // --- indexing --------------------------------------------------------------------

    /** Create the index with the analyzer and mapping the retrieval path depends on. */
    public void createIndex(String index) throws IOException {
        String mapping = MAPPING_TEMPLATE.formatted(ElasticsearchSettings.INFERENCE_ID);
        client.indices().create(c -> c
                .index(index)
                .withJson(new java.io.StringReader(
                        "{\"settings\":" + SETTINGS + ",\"mappings\":" + mapping + "}")));
    }

    public boolean exists(String index) throws IOException {
        return client.indices().exists(e -> e.index(index)).value();
    }

    public void delete(String index) throws IOException {
        client.indices().delete(d -> d.index(index));
    }

    /**
     * Bulk-load every chunk, then refresh so the documents are immediately searchable.
     *
     * @return the number of documents the index holds afterwards
     */
    public long index(String indexName, List<Document> documents) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Document document : documents) {
            bulk.operations(op -> op.index(i -> i
                    .index(indexName)
                    .id(document.id())
                    .document(document.source())));
        }
        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            String first = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.id() + ": " + item.error().reason())
                    .findFirst().orElse("unknown");
            throw new IOException("bulk indexing reported errors, first: " + first);
        }
        client.indices().refresh(r -> r.index(indexName));
        return client.count(c -> c.index(indexName)).count();
    }
}
