package io.github.cdevarenne.gctx.app.mcp;

import io.github.cdevarenne.gctx.bundle.Concept;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.provenance.Renderer;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The retrieval tools, exposed over MCP.
 *
 * <p>This adds no retrieval logic of its own. Every tool returns the same envelope the CLI
 * renders, because the point of reaching this over MCP is that the contract does not change per
 * consumer — the same stdio command serves Claude, Antigravity, or anything else that speaks the
 * protocol. Tool names, argument names and the instruction text are identical to the Python
 * server, so a client cannot tell which implementation answered.
 */
public final class GroundedMcpServer {

    public static final String NAME = "grounded-context";
    public static final String VERSION = "0.1.0";

    public static final String INSTRUCTIONS = """
            Grounded context layer over a curated, provenance-carrying knowledge bundle.

            Exact facts — model ids, context windows, endpoint paths, API versions, prices — MUST come
            from these tools and never from your own memory. That is the entire reason this server exists.

            Every result carries a `rendered` citation block: reproduce it alongside the answer. When
            `answer` is "Not found in the grounded sources.", say exactly that and stop rather than
            filling the gap yourself. When a citation reports staleness, pass that warning on.""";

    private final GroundedContextService service;

    public GroundedMcpServer(GroundedContextService service) {
        this.service = service;
    }

    /** Serve over stdio — the transport every MCP client supports. */
    public McpSyncServer start() {
        McpJsonMapper mapper = new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper(
                new com.fasterxml.jackson.databind.ObjectMapper());
        return McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo(NAME, VERSION)
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(lookupCanonicalFact(), askGrounded(), listEntities())
                .build();
    }

    // --- tools -----------------------------------------------------------------------

    io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification lookupCanonicalFact() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("lookup_canonical_fact")
                .description("""
                        Exact value of one canonical field. Use this for any fact that must not be guessed.

                        `entity_id` is a bundle id such as `anthropic.claude-opus-5`; `field` is a canonical \
                        field name such as `context_window_tokens`. Call `list_entities` to discover both. One \
                        Markdown link is traversed, so a model's `method` resolves through its endpoint concept. \
                        Pass `as_of` (YYYY-MM-DD) to evaluate staleness at that date instead of today. A field \
                        the bundle does not hold returns the refusal, not a guess.""")
                .inputSchema(schema(
                        Map.of(
                                "entity_id", property("string", "bundle id, e.g. anthropic.claude-opus-5"),
                                "field", property("string", "canonical field name, e.g. context_window_tokens"),
                                "as_of", property("string", "YYYY-MM-DD; evaluate staleness at this date")),
                        List.of("entity_id", "field")))
                .build();

        return io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    Envelope envelope = service.lookupField(
                            string(args, "entity_id"),
                            string(args, "field"),
                            asOf(string(args, "as_of")),
                            null);
                    return result(withCitationBlock(envelope));
                })
                .build();
    }

    io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification askGrounded() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("ask_grounded")
                .description("""
                        Answer a natural-language question, choosing a retrieval path first.

                        Precision questions route to exact lookup. Exploratory ones go to hybrid search when \
                        Elasticsearch is configured, and are refused otherwise — an honest refusal beats a \
                        ranked guess. The routing decision and its rationale come back in `router` and are part \
                        of the audit trail. Prefer `lookup_canonical_fact` when you already know the entity id \
                        and field.""")
                .inputSchema(schema(
                        Map.of(
                                "query", property("string", "the question, in natural language"),
                                "as_of", property("string", "YYYY-MM-DD; evaluate staleness at this date")),
                        List.of("query")))
                .build();

        return io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    Envelope envelope = service.ask(
                            string(args, "query"), asOf(string(args, "as_of")));
                    return result(withCitationBlock(envelope));
                })
                .build();
    }

    io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification listEntities() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("list_entities")
                .description("""
                        Inventory of the bundle: entity ids, types, trust tiers, and canonical field names.

                        Call this first to discover valid `entity_id` and `field` arguments.""")
                .inputSchema(schema(Map.of(), List.of()))
                .build();

        return io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> result(entities()))
                .build();
    }

    Map<String, Object> entities() {
        List<Map<String, Object>> entities = service.bundle().concepts().stream()
                .sorted(Comparator.comparing(Concept::id))
                .map(concept -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", concept.id());
                    entry.put("type", concept.type());
                    entry.put("trust_tier", concept.trustTier().label());
                    entry.put("stale_after",
                            concept.staleAfter() == null ? null : concept.staleAfter().toString());
                    entry.put("canonical_fields", concept.canonical().keySet().stream().sorted().toList());
                    return entry;
                })
                .toList();
        return Map.of("entities", entities);
    }

    // --- helpers ---------------------------------------------------------------------

    /** The envelope plus its rendered citation block, which the instructions tell clients to echo. */
    Map<String, Object> withCitationBlock(Envelope envelope) {
        Map<String, Object> payload = new LinkedHashMap<>(envelope.asMap());
        payload.put("rendered", Renderer.render(envelope));
        return payload;
    }

    /**
     * A refusal is structured content, not a protocol error.
     *
     * <p>{@code isError} would tell the client the call failed; it did not. It ran and found
     * nothing, which is a result the model is instructed to report verbatim.
     */
    private static McpSchema.CallToolResult result(Map<String, Object> payload) {
        return McpSchema.CallToolResult.builder()
                .structuredContent(payload)
                .addTextContent(String.valueOf(payload.getOrDefault("rendered", payload.toString())))
                .isError(false)
                .build();
    }

    private static Map<String, Object> schema(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate asOf(String raw) {
        return raw == null || raw.isBlank() ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(raw);
    }
}
