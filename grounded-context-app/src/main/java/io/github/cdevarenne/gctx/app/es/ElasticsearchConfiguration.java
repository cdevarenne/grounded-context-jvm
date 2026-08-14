package io.github.cdevarenne.gctx.app.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.cdevarenne.gctx.service.SemanticSearch;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the semantic path when, and only when, credentials are present.
 *
 * <p>This produces the {@link SemanticSearch} bean in one place and returns
 * {@link SemanticSearch#UNAVAILABLE} when there is nothing to connect to. Deliberately not
 * expressed as an {@code Optional<ElasticsearchClient>} bean: Spring reads an {@code Optional}
 * <em>parameter</em> as an optional dependency on the wrapped type, so such a bean is never
 * injected and the engine silently degrades to refusing everything — which looks identical to
 * working correctly with an empty corpus.
 */
@Configuration
public class ElasticsearchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfiguration.class);

    @Bean
    public SemanticSearch semanticSearch() {
        Optional<ElasticsearchSettings> settings = ElasticsearchSettings.discover();
        if (settings.isEmpty()) {
            // Not an error: the deterministic path is unaffected, and the exploratory branch
            // refuses rather than falling back to a model's own memory.
            log.debug("semantic path disabled — {}", ElasticsearchSettings.missingDescription());
            return SemanticSearch.UNAVAILABLE;
        }
        return new HybridSemanticSearch(connect(settings.get()));
    }

    /**
     * A connected client when credentials are discoverable, empty otherwise.
     *
     * <p>The public entry point, so callers never handle {@link ElasticsearchSettings} — and so
     * the API key stays reachable only from inside this package.
     */
    public static Optional<ElasticsearchClient> client() {
        return ElasticsearchSettings.discover().map(ElasticsearchConfiguration::connect);
    }

    static ElasticsearchClient connect(ElasticsearchSettings settings) {
        return ElasticsearchClient.of(b -> b
                .host(settings.url())
                .apiKey(settings.apiKey()));
    }
}
