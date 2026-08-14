package io.github.cdevarenne.gctx.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP surface tests, driven through the tool specifications rather than the service.
 *
 * <p>Calling the service directly would prove nothing about registration: a tool that is never
 * registered, or one whose schema omits a required argument, still works when invoked in Java.
 * These go through the registered {@code callHandler} the way a client does.
 *
 * <p>The literals here are the ones the Python server also produces. Tool names, argument names
 * and the instruction text are part of a wire contract shared by two implementations, so a
 * divergence has to fail a build rather than surface as a confused agent.
 */
class GroundedMcpServerTest {

    static final Path ROOT = Path.of("..", "knowledge");

    static GroundedMcpServer server() {
        return new GroundedMcpServer(new GroundedContextService(Bundle.load(ROOT)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(SyncToolSpecification spec, Map<String, Object> args) {
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(spec.tool().name(), args));
        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isNotNull();
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(SyncToolSpecification spec) {
        return (List<String>) spec.tool().inputSchema().get("required");
    }

    @Test
    void the_three_tools_are_registered_with_the_contract_names() {
        GroundedMcpServer mcp = server();
        assertThat(List.of(
                        mcp.lookupCanonicalFact().tool().name(),
                        mcp.askGrounded().tool().name(),
                        mcp.listEntities().tool().name()))
                .containsExactlyInAnyOrder(
                        "lookup_canonical_fact", "ask_grounded", "list_entities");
    }

    @Test
    void every_tool_carries_a_description_that_states_the_contract() {
        GroundedMcpServer mcp = server();
        assertThat(mcp.lookupCanonicalFact().tool().description())
                .contains("must not be guessed");
        assertThat(mcp.askGrounded().tool().description())
                .contains("an honest refusal beats a ranked guess");
        assertThat(mcp.listEntities().tool().description()).contains("Call this first");
    }

    @Test
    void the_instructions_carry_the_never_answer_from_memory_rule() {
        // This text is what makes a foreign runtime honour the grounding contract, so it is
        // part of the interface rather than documentation.
        assertThat(GroundedMcpServer.INSTRUCTIONS)
                .contains("MUST come\nfrom these tools and never from your own memory")
                .contains("Not found in the grounded sources.")
                .contains("When a citation reports staleness, pass that warning on.");
    }

    @Test
    void lookup_marks_entity_and_field_required_but_not_as_of() {
        assertThat(required(server().lookupCanonicalFact()))
                .containsExactlyInAnyOrder("entity_id", "field");
    }

    @Test
    void ask_marks_only_the_query_required() {
        assertThat(required(server().askGrounded())).containsExactly("query");
    }

    @Test
    void lookup_returns_the_envelope_plus_a_rendered_citation_block() {
        Map<String, Object> structured = call(server().lookupCanonicalFact(), Map.of(
                "entity_id", "anthropic.claude-opus-5",
                "field", "context_window_tokens",
                "as_of", "2026-08-13"));

        assertThat(structured.get("answer")).isEqualTo("1,000,000");
        assertThat(structured.get("retrieval_path")).isEqualTo("deterministic");
        assertThat(String.valueOf(structured.get("rendered")))
                .contains("human-reviewed 2026-08-10")
                .contains("fresh until 2026-09-09");
    }

    @Test
    void lookup_traverses_one_link() {
        Map<String, Object> structured = call(server().lookupCanonicalFact(),
                Map.of("entity_id", "anthropic.claude-opus-5", "field", "method"));
        assertThat(structured.get("answer")).isEqualTo("POST");
        assertThat(String.valueOf(structured.get("rendered")))
                .contains("traversed: anthropic.claude-opus-5 → anthropic.messages");
    }

    @Test
    void as_of_surfaces_staleness_over_mcp_too() {
        Map<String, Object> structured = call(server().lookupCanonicalFact(), Map.of(
                "entity_id", "anthropic.claude-opus-5",
                "field", "context_window_tokens",
                "as_of", "2026-10-01"));
        assertThat(String.valueOf(structured.get("rendered")))
                .contains("⚠ STALE since 2026-09-09");
    }

    @Test
    void a_refusal_is_a_grounded_result_not_a_protocol_error() {
        // isError would tell the client the call failed. It did not: it ran and found nothing,
        // which the instructions tell the model to report verbatim.
        Map<String, Object> structured = call(server().lookupCanonicalFact(),
                Map.of("entity_id", "anthropic.claude-opus-5", "field", "rate_limit_rpm"));
        assertThat(structured.get("answer")).isEqualTo(Envelope.NOT_FOUND);
        assertThat((List<?>) structured.get("citations")).isEmpty();
    }

    @Test
    void ask_routes_and_reports_its_rationale() {
        Map<String, Object> structured = call(server().askGrounded(),
                Map.of("query", "What is the exact context window of claude-opus-5?"));
        @SuppressWarnings("unchecked")
        Map<String, Object> router = (Map<String, Object>) structured.get("router");
        assertThat(router.get("route")).isEqualTo("DETERMINISTIC");
        assertThat(String.valueOf(router.get("rationale"))).contains("must not be ranked");
    }

    @Test
    void ask_refuses_an_exploratory_question_when_no_engine_is_wired() {
        Map<String, Object> structured = call(server().askGrounded(),
                Map.of("query", "How should I chunk documents for retrieval?"));
        assertThat(structured.get("answer")).isEqualTo(Envelope.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    @Test
    void list_entities_exposes_the_bundle_inventory() {
        Map<String, Object> structured = call(server().listEntities(), Map.of());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) structured.get("entities");
        assertThat(entities).extracting(e -> e.get("id")).containsExactly(
                "anthropic.claude-haiku-4-5",
                "anthropic.claude-opus-5",
                "anthropic.claude-sonnet-5",
                "anthropic.messages");
        Map<String, Object> opus = entities.stream()
                .filter(e -> "anthropic.claude-opus-5".equals(e.get("id"))).findFirst().orElseThrow();
        assertThat(opus.get("trust_tier")).isEqualTo("human-reviewed");
        assertThat((List<String>) opus.get("canonical_fields")).contains("context_window_tokens");
    }
}
