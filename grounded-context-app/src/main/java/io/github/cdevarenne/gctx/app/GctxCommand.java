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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            GctxCommand.EvalCommand.class,
            GctxCommand.MeasureCommand.class,
            GctxCommand.IndexCommand.class,
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

    @Command(name = "eval", description = "run the eval set from docs/specs/eval.md")
    static class EvalCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;

        @Option(names = "--compare", paramLabel = "QUERY",
                description = "instead: rank one query under ELSER, BM25, and hybrid")
        String compare;

        @Override
        public Integer call() {
            PrintWriter out = new PrintWriter(System.out, true);
            return compare != null ? compareArms(out) : runSet(out);
        }

        private int compareArms(PrintWriter out) {
            if (!(parent.semantic instanceof io.github.cdevarenne.gctx.app.es.HybridSemanticSearch hybrid)) {
                System.err.println("error: --compare needs Elasticsearch; set ES_URL and ES_API_KEY");
                return EXIT_ERROR;
            }
            var comparison = new io.github.cdevarenne.gctx.app.eval.ArmComparison(hybrid);
            var target = io.github.cdevarenne.gctx.app.eval.ArmComparison.targetFor(compare);
            Map<String, Integer> ranks = comparison.compare(compare, target);

            if (parent.json) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("query", compare);
                payload.put("target", target.sourceId() + ":chunk:" + target.chunkIndex());
                payload.put("ranks", ranks);
                out.println(JsonWriter.write(payload));
                return 0;
            }
            out.println("query: '" + compare + "'");
            out.println("target: " + target.sourceId() + " chunk:" + target.chunkIndex()
                    + " — the chunk that defines the term");
            out.println();
            ranks.forEach((arm, rank) -> out.printf("  %-8s %s%n", arm,
                    rank == null ? "not in top 20" : "rank " + rank));
            return 0;
        }

        private int runSet(PrintWriter out) {
            var harness = new io.github.cdevarenne.gctx.app.eval.EvalHarness(parent.service());
            List<io.github.cdevarenne.gctx.app.eval.EvalResult> results =
                    harness.runAll(parent.asOfDate());

            if (parent.json) {
                out.println(JsonWriter.write(results.stream()
                        .map(io.github.cdevarenne.gctx.app.eval.EvalResult::asMap).toList()));
            } else {
                out.printf("%-5s%-15s%-15s%-14s%-7s%s%n",
                        "id", "expected", "actual", "route", "cites", "verdict");
                for (var r : results) {
                    out.printf("%-5s%-15s%-15s%-14s%-7d%s%n", r.testCase().id(),
                            r.testCase().expected(), r.actual(), r.route(), r.citations(),
                            r.verdict());
                }
                for (var r : results) {
                    if (r.testCase().hasKnownDeviation()) {
                        out.println();
                        out.println(r.testCase().id() + " KNOWN — "
                                + r.testCase().knownDeviation());
                    }
                }
                long passed = results.stream().filter(r -> "PASS".equals(r.verdict())).count();
                long known = results.stream().filter(r -> "KNOWN".equals(r.verdict())).count();
                long failed = results.stream().filter(r -> "FAIL".equals(r.verdict())).count();
                out.println();
                out.println(passed + " pass · " + known + " known deviation · " + failed + " fail");
            }
            // A failure exits non-zero so this can gate a build; a declared deviation does not.
            return results.stream().anyMatch(r -> "FAIL".equals(r.verdict())) ? EXIT_REFUSAL : 0;
        }
    }

    @Command(name = "measure",
            description = "recompute the corpus-wide figures quoted in the findings")
    static class MeasureCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;

        @Override
        public Integer call() {
            var client = io.github.cdevarenne.gctx.app.es.ElasticsearchConfiguration.client();
            if (client.isEmpty()) {
                System.err.println("error: this needs Elasticsearch; set ES_URL and ES_API_KEY");
                return EXIT_ERROR;
            }
            var sweep = new io.github.cdevarenne.gctx.app.eval.FindingsSweep(client.get());
            Map<String, Object> report = sweep.report();
            PrintWriter out = new PrintWriter(System.out, true);

            if (parent.json) {
                out.println(JsonWriter.write(report));
                return 0;
            }

            out.println("index " + io.github.cdevarenne.gctx.app.es.ElasticsearchSettings.INDEX
                    + ": " + report.get("chunks") + " chunks");
            out.println();

            @SuppressWarnings("unchecked")
            Map<String, Object> mech = (Map<String, Object>) report.get("mechanism");
            out.println("Finding 2 — why the subfield helps, on " + mech.get("term")
                    + " (" + mech.get("target") + ")");
            out.println("    matches on content        " + mech.get("content_matches")
                    + " chunks   (punctuation stripped, so code samples collapse onto the prose mention)");
            out.println("    matches on content.exact  " + mech.get("exact_matches")
                    + " chunk    (punctuation kept, so only the bare prose mention matches)");
            out.println("    rank of the defining chunk: content-only " + mech.get("rank_content_only")
                    + " -> with exact " + mech.get("rank_with_exact"));

            out.println();
            out.println("Finding 2 — rank improved by the content.exact subfield");
            out.println("  (tokens unique to one chunk, longer than 10 characters)");
            @SuppressWarnings("unchecked")
            Map<String, io.github.cdevarenne.gctx.app.eval.FindingsSweep.ShapeEffect> effects =
                    (Map<String, io.github.cdevarenne.gctx.app.eval.FindingsSweep.ShapeEffect>)
                            report.get("subfield_effect");
            effects.forEach((shape, effect) -> out.printf("    %-12s %3d of %3d improved, %d regressed%n",
                    shape, effect.improved(), effect.total(), effect.regressed()));

            @SuppressWarnings("unchecked")
            Map<String, Object> hidden = (Map<String, Object>) report.get("invisible_to_exact");
            out.println();
            out.println("  tokens matching `content` but INVISIBLE to `content.exact`");
            out.println("  (hyphenated or underscored, at least 8 characters):");
            out.println("    " + hidden.get("invisible") + " of " + hidden.get("total"));
            @SuppressWarnings("unchecked")
            List<String> examples = (List<String>) hidden.get("examples");
            out.println("    first three alphabetically: "
                    + String.join(", ", examples.subList(0, Math.min(3, examples.size()))));
            @SuppressWarnings("unchecked")
            Map<String, Boolean> documented = (Map<String, Boolean>) hidden.get("documented");
            documented.forEach((token, present) -> out.printf(
                    "    cited in findings.md: %-20s %s%n", token,
                    present ? "in the set" : "ABSENT"));

            out.println();
            out.println("Finding 3 — fused vs pre-fusion score (floor = " + report.get("floor") + ")");
            out.printf("  %-13s %7s %7s  query%n", "kind", "fused", "sparse");
            @SuppressWarnings("unchecked")
            List<io.github.cdevarenne.gctx.app.eval.FindingsSweep.Probe> probes =
                    (List<io.github.cdevarenne.gctx.app.eval.FindingsSweep.Probe>) report.get("probes");
            for (var probe : probes) {
                out.printf("  %-13s %7.4f %7.2f  %s%n",
                        probe.kind(), probe.fused(), probe.sparse(), probe.query());
            }
            return 0;
        }
    }

    @Command(name = "index",
            description = "build the semantic index from a directory of corpus pages")
    static class IndexCommand implements Callable<Integer> {
        @ParentCommand GctxCommand parent;

        @Option(names = "--corpus", paramLabel = "DIR", required = true,
                description = "directory of front-matter Markdown pages")
        String corpus;

        @Option(names = "--index", paramLabel = "NAME",
                description = "index to build; defaults to the reference index")
        String index;

        @Option(names = "--recreate", description = "delete and rebuild the index first")
        boolean recreate;

        @Override
        public Integer call() throws Exception {
            var client = io.github.cdevarenne.gctx.app.es.ElasticsearchConfiguration.client();
            if (client.isEmpty()) {
                System.err.println("error: this needs Elasticsearch; set ES_URL and ES_API_KEY");
                return EXIT_ERROR;
            }
            java.nio.file.Path dir = java.nio.file.Path.of(corpus);
            if (!java.nio.file.Files.isDirectory(dir)) {
                System.err.println("error: no such corpus directory: " + dir);
                return EXIT_ERROR;
            }

            var indexer = new io.github.cdevarenne.gctx.app.es.CorpusIndexer(client.get());
            String target = index != null ? index
                    : io.github.cdevarenne.gctx.app.es.ElasticsearchSettings.INDEX;
            PrintWriter out = new PrintWriter(System.out, true);

            var documents = io.github.cdevarenne.gctx.app.es.CorpusIndexer.documents(dir);
            if (documents.isEmpty()) {
                System.err.println("error: no corpus pages in " + dir);
                return EXIT_ERROR;
            }

            if (recreate && indexer.exists(target)) {
                indexer.delete(target);
                out.println("deleted index " + target);
            }
            if (!indexer.exists(target)) {
                indexer.createIndex(target);
                out.println("created index " + target + " (semantic_text via "
                        + io.github.cdevarenne.gctx.app.es.ElasticsearchSettings.INFERENCE_ID + ")");
            }

            long held = indexer.index(target, documents);
            out.println("indexed " + documents.size() + " chunks, 0 errors");
            out.println(target + " now holds " + held + " documents");
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
