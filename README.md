# Grounded Context Layer for Enterprise Agents — JVM

A Java/Spring port of [**grounded-context**](https://github.com/cdevarenne/grounded-context):
a grounded, composable, **deterministic-where-it-matters** context layer for LLM agents, built
on Elasticsearch and reached over MCP.

> ⚠️ **This is a prototype** — a proof of concept, an architecture backed by sample code.
> Read-only, single user, small curated corpus. Not production software.

---

## Why a second implementation

Not to have the same thing in two languages. The Python repo publishes measured claims — rank
tables, score ranges, corpus-wide counts — and a single codebase cannot tell you whether those
numbers describe the *index* or merely describe *itself*. A second implementation reading the
same index, with its own regex engine, YAML parser and Elasticsearch client, can.

Every published figure was recomputed here. The results are in
[**docs/parity.md**](docs/parity.md), which is the point of this repo: **one divergence across
the entire system**, and it is a case where the Python side looks wrong.

It also extends the model-agnostic claim. The Python repo showed one MCP server driven by Claude
and by Gemini; this shows the same tool contract served by two independent implementations —
model-agnostic *and* language-agnostic.

---

## The module split is the thesis

```
grounded-context-core   no framework, one compile dependency: SnakeYAML
grounded-context-app    Spring Boot: CLI, Elasticsearch, MCP, eval, sweep
```

The Python project promises that the deterministic path runs with zero cloud dependency. Here
that promise is a build constraint you can verify in one command:

```console
$ mvn -pl grounded-context-core dependency:tree
io.github.cdevarenne:grounded-context-core
+- org.yaml:snakeyaml:jar:2.5:compile      <- the only compile dependency
+- org.junit.jupiter:junit-jupiter:jar:5.14.0:test
\- org.assertj:assertj-core:jar:3.27.6:test
```

Bundle parsing, exact lookup, link traversal, the router, the provenance contract and the answer
envelope all live in `core`. It has no Spring, no HTTP client, and no Elasticsearch — so the
guaranteed spine cannot quietly acquire a network dependency.

`core` also defines `SemanticSearch`, an interface it never implements. That is the seam the
probabilistic half plugs into, and the reason the deterministic path stays framework-free.

---

## Run it

Requires **Java 21+** and Maven. The repo develops against a newer JDK, but `maven.compiler.release`
is 21 so an older LTS can still build it — the same pin-versus-floor split the Python repo uses
for its interpreter.

```bash
mvn package                                    # builds grounded-context-app/target/gctx.jar
```

### Deterministic path — no cloud account, no API key

```bash
java -jar grounded-context-app/target/gctx.jar ask "What is the exact context window of claude-opus-5?"
java -jar grounded-context-app/target/gctx.jar lookup anthropic.claude-opus-5 method   # traverses model → endpoint
java -jar grounded-context-app/target/gctx.jar --as-of 2026-10-01 lookup anthropic.claude-opus-5 context_window_tokens
java -jar grounded-context-app/target/gctx.jar entities
```

Exit code `1` means a refusal — a grounded outcome, not a failure. `2` is a real error, such as a
malformed bundle.

### Semantic path — needs Elasticsearch + ELSER

Set `ES_URL` and `ES_API_KEY` in the environment or in a gitignored `.env` at the repo root.
**This repo ships no `.env`**, and it will not find one belonging to a sibling checkout.

```bash
java -jar grounded-context-app/target/gctx.jar ask "How should I chunk documents for retrieval?"
java -jar grounded-context-app/target/gctx.jar eval                                  # the 12-question set
java -jar grounded-context-app/target/gctx.jar eval --compare rank_constant          # ELSER vs BM25 vs hybrid
java -jar grounded-context-app/target/gctx.jar measure                               # the corpus-wide figures
```

Without credentials the exploratory branch returns `Not found in the grounded sources.` rather
than failing. An unavailable engine is a refusal, never an error, and never a fallback to the
model's own memory.

The corpus itself is **not** built here. It is fetched and indexed by the Python repo's
`scripts/fetch_corpus.py` and `scripts/index_corpus.py`; this implementation reads the index
those produce, which is precisely what makes the comparison meaningful.

### From an agent, over MCP

```bash
java -jar grounded-context-app/target/gctx.jar mcp     # serves on stdio; a client drives it
```

[`.mcp.json`](.mcp.json) wires it up on clone — it is **not** a copy of the Python one, which
runs `uv run --extra mcp gctx-mcp`. Build the jar first. Three tools: `lookup_canonical_fact`,
`ask_grounded`, `list_entities`.

All logging goes to stderr. Over stdio, stdout *is* the JSON-RPC channel.

---

## Status

| Component | Status |
|---|---|
| Deterministic spine (bundle, lookup, router, provenance) | ✅ framework-free, one compile dependency |
| CLI (`lookup` / `ask` / `route` / `entities`) | ✅ byte-identical to the Python CLI |
| Elasticsearch hybrid path (BM25 + ELSER, RRF) | ✅ same index, ranks reproduce |
| MCP server (3 tools, stdio) | ✅ same tool contract, protocol 2025-11-25 |
| Eval harness (`eval`, `eval --compare`) | ✅ 11 pass + 1 declared deviation |
| Findings sweep (`measure`) | ✅ reproduces every published aggregate |
| Bundle drift check against the Python repo | ✅ fails on divergence, skips when absent |
| Test suite | ✅ 97 tests; cluster tests skip without credentials |
| Embabel ingestion / post-search actions | ⬜ planned, via the `SemanticSearch` seam |

---

## Learn more

- [**docs/parity.md**](docs/parity.md) — what was compared, how, and the one thing that differs.
- The **[Python repo](https://github.com/cdevarenne/grounded-context)** holds the design
  rationale, the specs, and the findings. They are deliberately **not** duplicated here: two
  copies of an argument drift exactly the way two copies of a bundle do.

The knowledge bundle *is* duplicated, because this repo has to run standalone — so
`BundleParityTest` fails if the copies diverge. It runs when the Python repo is checked out
alongside, or wherever `GCTX_REFERENCE_BUNDLE` points.

---

## Out of scope

- **Read-only.** No writes, no actions, no tool execution.
- **No auth, no multi-tenancy, no scale story.** The MCP server runs over stdio as a local
  subprocess with no authentication layer; a remote transport would need one.
- **No corpus tooling.** Fetching and indexing stay in the Python repo, deliberately.
- **Small-n evaluation.** The eval set is illustrative — which engine answers, and that
  provenance is present. It is **not** a benchmark and no performance claims are made from it.

## License

MIT — see [LICENSE](LICENSE).
