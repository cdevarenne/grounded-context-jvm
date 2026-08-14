package io.github.cdevarenne.gctx.app;

import io.github.cdevarenne.gctx.bundle.Bundle;
import io.github.cdevarenne.gctx.bundle.Concept;
import io.github.cdevarenne.gctx.provenance.Envelope;
import io.github.cdevarenne.gctx.provenance.Renderer;
import io.github.cdevarenne.gctx.router.Route;
import io.github.cdevarenne.gctx.router.Router;
import io.github.cdevarenne.gctx.service.GroundedContextService;
import io.github.cdevarenne.gctx.service.SemanticSearch;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * The human-facing surface, mirroring the Python {@code gctx} interface.
 *
 * <p>Option names, subcommands and exit codes are deliberately identical, so the two
 * implementations are interchangeable in a demo and a reviewer can run either against the same
 * bundle. Exit code 1 means a refusal — a grounded outcome, not a failure — and 2 is reserved
 * for a genuine error such as a malformed bundle.
 */
@Command(
        name = "gctx",
        description = "Grounded context layer — deterministic path.",
        mixinStandardHelpOptions = true,
        subcommands = {
            GctxCommand.LookupCommand.class,
            GctxCommand.AskCommand.class,
            GctxCommand.RouteCommand.class,
            GctxCommand.EntitiesCommand.class,
            GctxCommand.McpCommand.class,
        })
public class GctxCommand implements Callable<Integer> {

    /** A refusal exits 1: nothing was found, and that is a result the caller can branch on. */
    public static final int EXIT_REFUSAL = 1;
    public static final int EXIT_ERROR = 2;

    @Option(names = "--bundle", description = "path to the knowledge/ bundle")
    String bundle;

    @Option(names = "--as-of", paramLabel = "YYYY-MM-DD",
            description = "evaluate staleness as of this date instead of today")
    String asOf;

    @Option(names = "--json", description = "emit the raw envelope")
    boolean json;

    private final SemanticSearch semantic;

    public GctxCommand(SemanticSearch semantic) {
        this.semantic = semantic;
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return EXIT_ERROR;
    }

    LocalDate asOfDate() {
        return asOf == null ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(asOf);
    }

    GroundedContextService service() {
        return new GroundedContextService(Bundle.load(BundleLocator.resolve(bundle)), semantic);
    }

    /** Print an envelope and translate it into the process exit code. */
    int emit(Envelope envelope, PrintWriter out) {
        out.println(json ? JsonWriter.write(envelope.asMap()) : Renderer.render(envelope));
        return envelope.isRefusal() ? EXIT_REFUSAL : 0;
    }

    @Command(name = "lookup", description = "exact lookup of one canonical field")
    static class LookupCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;
        @Parameters(index = "0", paramLabel = "ENTITY") String entity;
        @Parameters(index = "1", paramLabel = "FIELD") String field;

        @Override
        public Integer call() {
            return parent.emit(
                    parent.service().lookupField(entity, field, parent.asOfDate(), null),
                    new PrintWriter(System.out, true));
        }
    }

    @Command(name = "ask", description = "route a natural-language question, then answer it")
    static class AskCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;
        @Parameters(index = "0", paramLabel = "QUERY") String query;

        @Override
        public Integer call() {
            return parent.emit(
                    parent.service().ask(query, parent.asOfDate()),
                    new PrintWriter(System.out, true));
        }
    }

    @Command(name = "route", description = "show the routing decision only")
    static class RouteCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;
        @Parameters(index = "0", paramLabel = "QUERY") String query;

        @Override
        public Integer call() {
            Route decision = Router.route(query);
            PrintWriter out = new PrintWriter(System.out, true);
            if (parent.json) {
                out.println(JsonWriter.write(decision.asMap()));
            } else {
                out.println(decision.route() + " — " + decision.rationale());
            }
            return 0;
        }
    }

    @Command(name = "mcp", description = "serve the retrieval tools over MCP on stdio")
    static class McpCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;

        @Override
        public Integer call() throws Exception {
            var server = new io.github.cdevarenne.gctx.app.mcp.GroundedMcpServer(parent.service())
                    .start();
            // stdio has no idle timeout: the client owns the lifetime, and the server stays up
            // until the transport closes or the process is terminated.
            Thread.currentThread().join();
            server.close();
            return 0;
        }
    }

    @Command(name = "entities", description = "list concepts and their canonical fields")
    static class EntitiesCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;

        @Override
        public Integer call() {
            Bundle loaded = Bundle.load(BundleLocator.resolve(parent.bundle));
            LocalDate asOf = parent.asOfDate();
            PrintWriter out = new PrintWriter(System.out, true);
            loaded.concepts().stream()
                    .sorted(Comparator.comparing(Concept::id))
                    .forEach(concept -> {
                        String flag = concept.isStale(asOf) ? " ⚠ STALE" : "";
                        out.println(concept.id() + "  [" + concept.type() + "]  "
                                + concept.trustTier().label() + flag);
                        concept.canonical().keySet().stream().sorted()
                                .forEach(name -> out.println("    canonical." + name));
                    });
            return 0;
        }
    }
}
