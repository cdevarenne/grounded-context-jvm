# Grounded Context Layer for Enterprise Agents — JVM

A Java/Spring port of the [**grounded-context**](https://github.com/cdevarenne/grounded-context) Python version:
a grounded, composable, **deterministic-where-it-matters** context layer for LLM agents, built
on Elasticsearch and reached over MCP.

**Background:** [A Grounded Context Layer for Agents — and Three Things Hybrid Search Won't Tell
You](https://medium.com/@claude.devarenne/a-grounded-context-layer-for-agents-and-three-things-hybrid-search-wont-tell-you-e71fdc334773)
— why the pattern is shaped this way. This repo is how to build it.

> ⚠️ **This is a prototype** — a proof of concept, an architecture backed by sample code.
> Read-only, single user, small curated corpus. Not production software.

---

## Why this exists

Most enterprise backends run on the JVM. This repo makes the pattern build-and-runnable on that
stack. A team can clone it, point it at their own Elasticsearch and their own documents, and have
a grounded context layer running — without rebuilding it from a blog post.

It is a complete implementation, not one half of one. It does not fetch documents. It does
everything else: parse the knowledge bundle, answer exact lookups, **build the semantic index**,
run hybrid retrieval, serve the tools over MCP, and evaluate the result.

Start at [Run it](#run-it). To use your own documents, see
[Bring your own corpus](#bring-your-own-corpus).

**It also reproduces the reference implementation faithfully.** That is what makes it safe to
adopt: a team that indexes with this code gets the behaviour the
[Python repo](https://github.com/cdevarenne/grounded-context) documents. Every published figure
was recomputed here, and the comparison found one defect in the reference, now fixed. The
evidence — and the exact limits of what it shows — is in [**docs/parity.md**](docs/parity.md).

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

### Bring your own corpus

This is the adopter path, and it needs no Python.

```bash
# 1. Put your documents in a directory as Markdown with YAML front matter:
#      id, title, url, provider, topic, fetched_at
# 2. Choose an index name, then build it.
export ES_INDEX=my-team-corpus
java -jar grounded-context-app/target/gctx.jar index --corpus ./my-docs --recreate

# 3. Everything else now runs against your index.
java -jar grounded-context-app/target/gctx.jar ask "how do we rotate credentials?"
```

`ES_INDEX` selects the index for every command; it defaults to the reference corpus. The indexer
creates the mapping the retrieval path depends on — the `exact_token` analyzer, the `content.exact`
subfield, and `semantic_text` wired to ELSER — so the index is built the same way whoever builds it.

**What does not port:** fetching. The Python repo's `scripts/fetch_corpus.py` collects the
reference corpus from public documentation. Deliberately not reimplemented — an adopting team
already has its documents, and a fetcher tuned to someone else's sources is not useful to them.

Two consequences worth knowing. The reference corpus is not committed to either repo, so
reproducing the published numbers requires running that Python script once. And the tests that
pin those numbers **skip** on any other index, so building your own corpus does not turn the
suite red.

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
| Corpus indexer (`index`) | ✅ chunking byte-identical to the reference index |
| Bundle drift check against the Python repo | ✅ fails on divergence, skips when absent |
| Test suite | ✅ 107 tests; cluster tests skip without credentials |
| Embabel ingestion / post-search actions | ⬜ planned, via the `SemanticSearch` seam |

---

## Learn more

- [**docs/parity.md**](docs/parity.md) — what was compared, how, and what the comparison found.
- [**docs/index-spec.md**](docs/index-spec.md) — the chunking rule and index mapping this builds
  to. Read it before changing the indexer: an index built differently returns different ranks.
- The **[Python repo](https://github.com/cdevarenne/grounded-context)** is the reference
  implementation. **Which to use:** it holds the corpus tooling — the fetch script — plus the
  design rationale, the specs, and the findings. This repo is the build-ready port for JVM teams:
  it indexes and serves, and you point it at your own Elasticsearch and your own documents. The
  rationale and findings are deliberately **not** duplicated here: two copies of an argument
  drift exactly the way two copies of a bundle do.

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
