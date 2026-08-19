package io.github.cdevarenne.gctx.app.es;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The configurable connection settings.
 *
 * <p>Needs no cluster: resolving a setting reads the environment and a {@code .env} file, and
 * nothing else. Hard-coding an index name or an inference endpoint forces an adopting team to
 * edit source, which is the difference between a reference architecture and a demo.
 */
class ElasticsearchSettingsTest {

    /** No environment variable and no `.env` entry uses this name, so it exercises the fallback. */
    private static final String UNSET = "GCTX_NOT_SET_ANYWHERE";

    @Test
    void a_setting_falls_back_when_nothing_provides_it() {
        assertThat(ElasticsearchSettings.setting(UNSET, "the-default")).isEqualTo("the-default");
    }

    @Test
    void the_reference_defaults_are_what_the_published_numbers_were_measured_against() {
        assertThat(ElasticsearchSettings.DEFAULT_INDEX).isEqualTo("grounded-context-corpus");
        assertThat(ElasticsearchSettings.DEFAULT_INFERENCE_ID)
                .isEqualTo(".elser-2-elasticsearch");
    }

    @Test
    void the_variable_names_match_the_python_reference_implementation() {
        // An adopter setting ES_INDEX must get the same behaviour from either implementation.
        assertThat(ElasticsearchSettings.INDEX_VAR).isEqualTo("ES_INDEX");
        assertThat(ElasticsearchSettings.INFERENCE_ID_VAR).isEqualTo("ES_INFERENCE_ID");
    }

    @Test
    void the_resolved_values_are_never_blank() {
        // Whatever the environment says, the indexer and the retrieval path both dereference
        // these, and a blank index name fails far away from its cause.
        assertThat(ElasticsearchSettings.INDEX).isNotBlank();
        assertThat(ElasticsearchSettings.INFERENCE_ID).isNotBlank();
    }
}
