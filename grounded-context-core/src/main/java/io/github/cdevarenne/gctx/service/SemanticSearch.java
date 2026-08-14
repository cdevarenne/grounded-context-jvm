package io.github.cdevarenne.gctx.service;

import io.github.cdevarenne.gctx.provenance.Citation;
import java.util.List;

/**
 * The seam between the guaranteed spine and the probabilistic half.
 *
 * <p>The core module defines this interface but never implements it, which is what keeps the
 * deterministic path free of Elasticsearch, HTTP, and Spring. An implementation lives in the
 * app module; later ingestion or post-search work can supply another without the core changing.
 *
 * <p>{@link #UNAVAILABLE} is the deliberate default: when no engine is wired, the exploratory
 * branch returns nothing and the envelope turns that into a refusal. An unavailable engine is a
 * refusal, never an error, and never a fall back to a model's own memory.
 */
@FunctionalInterface
public interface SemanticSearch {

    /** Never-configured engine: always empty, which the envelope renders as the refusal. */
    SemanticSearch UNAVAILABLE = (query, size) -> List.of();

    /** Grounded passages for an open question, best first. */
    List<Citation> search(String query, int size);
}
