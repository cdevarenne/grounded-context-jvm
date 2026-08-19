# Quickstart — clone to first grounded answer

From `git clone` to an answer with a citation block, then to the same thing running against
**your** Elasticsearch and **your** documents.

Part 1 needs no cloud account and no API key. Part 2 is the adopter path and needs a cluster.
Every output below is captured from a real run, not typed by hand — only the checkout path is
shortened.

The Python reference implementation has its own quickstart:
[grounded-context/docs/quickstart.md](https://github.com/cdevarenne/grounded-context/blob/main/docs/quickstart.md).

---

## Part 1 — Build it and answer with no cloud

### 1. Build

**Java 21 or newer**, and either build tool. Both build the same two modules from the same
dependency versions — `BuildParityTest` fails if they drift — so use the one your team already
has.

```bash
git clone https://github.com/cdevarenne/grounded-context-jvm.git
cd grounded-context-jvm

mvn package        # -> grounded-context-app/target/gctx.jar
./gradlew build    # -> grounded-context-app/build/libs/gctx.jar
```

**The examples below use the Maven path.** With Gradle, substitute `build/libs/` for `target/`.

It is worth shortening the command before going further:

```bash
alias gctx='java -jar grounded-context-app/target/gctx.jar'
```

### 2. Check the guarantee before you trust it

The deterministic half is supposed to run with nothing but a YAML parser. Here that is a build
constraint you can verify rather than a promise in a README:

```console
$ mvn -pl grounded-context-core dependency:tree
io.github.cdevarenne:grounded-context-core
+- org.yaml:snakeyaml:jar:2.5:compile      <- the only compile dependency
+- org.junit.jupiter:junit-jupiter:jar:5.14.0:test
\- org.assertj:assertj-core:jar:3.27.6:test
```

No Spring, no HTTP client, no Elasticsearch in `core`. Bundle parsing, exact lookup, link
traversal, the router, the provenance contract and the answer envelope all live there. The
probabilistic half plugs in through `SemanticSearch`, an interface `core` defines and never
implements — which is what stops the guaranteed spine quietly acquiring a network dependency.

### 3. Your first grounded answer

```console
$ gctx lookup anthropic.claude-opus-5 context_window_tokens
Answer: 1,000,000

  ↳ source: anthropic.claude-opus-5 · canonical.context_window_tokens
    path: deterministic (exact-lookup) · human-reviewed 2026-08-10
    fresh until 2026-09-09
    https://platform.claude.com/docs/en/about-claude/models/overview
```

The block under the `↳` is the point of the project. Read it field by field:

| Line | What it commits to |
|---|---|
| `source:` | the entity and the exact canonical field the value came from |
| `path:` | which retrieval path answered — `deterministic` here, so nothing was ranked |
| `human-reviewed 2026-08-10` | the trust tier and the date a human verified it against the live doc |
| `fresh until 2026-09-09` | the governance date; after it, this citation prints `STALE` |
| the URL | the source a reader can open and check |

No answer is emitted without one. The contract is
[`docs/specs/provenance.md`](https://github.com/cdevarenne/grounded-context/blob/main/docs/specs/provenance.md)
in the Python repo — the specs live in one place on purpose.

### 4. See the bundle, and a link being traversed

```console
$ gctx entities
anthropic.claude-haiku-4-5  [model]  human-reviewed
    canonical.adaptive_thinking
    canonical.api_alias
    canonical.context_window_tokens
    canonical.default_endpoint
    canonical.extended_thinking
    canonical.input_price_per_mtok_usd
    canonical.max_output_tokens
    canonical.model_string
    canonical.output_price_per_mtok_usd
    canonical.vision
anthropic.claude-opus-5  [model]  human-reviewed
    …
```

`method` is not a field on a model — it belongs to the endpoint the model points at, and the
lookup follows that link:

```console
$ gctx lookup anthropic.claude-opus-5 method
Answer: POST

  ↳ source: anthropic.messages · canonical.method
    path: deterministic (exact-lookup) · human-reviewed 2026-08-10
    fresh until 2026-09-09
    traversed: anthropic.claude-opus-5 → anthropic.messages
    https://platform.claude.com/docs/en/get-started
```

The `traversed:` line is not decoration. One hop is one more place an answer could have gone
wrong, so the hop is in the audit trail.

### 5. Ask in plain English, and read the router's reasoning

```console
$ gctx ask "What is the exact context window of claude-opus-5?"
router: DETERMINISTIC — precision phrasing ("context window", "exact") plus a named model (claude-opus-5) — an exact fact must not be ranked

Answer: 1,000,000

  ↳ source: anthropic.claude-opus-5 · canonical.context_window_tokens
    path: deterministic (exact-lookup) · human-reviewed 2026-08-10
    fresh until 2026-09-09
    https://platform.claude.com/docs/en/about-claude/models/overview
```

The router states its reason, not just its verdict — a routing decision you cannot read is a
decision you cannot audit. To see the decision without answering:

```console
$ gctx route "How should I chunk documents for retrieval?"
SEMANTIC — exploratory phrasing ("how should i", "should i"), no exact field requested
```

### 6. A refusal, and the exit code

```console
$ gctx lookup anthropic.claude-opus-5 rate_limit_rpm
Answer: Not found in the grounded sources.

  ↳ no grounded source — nothing was returned rather than guessed.
$ echo $?
1
```

**Exit `1` is a refusal, not a failure.** Exit `2` is a real error, such as a malformed bundle. A
refusal is the correct outcome when nothing grounded exists, and it is the behavior the whole
design protects: the layer never falls back to a model's own memory.

### 7. Staleness

Every canonical fact carries a `stale_after` date. Ask again as if that date had passed:

```console
$ gctx --as-of 2026-10-01 lookup anthropic.claude-opus-5 context_window_tokens
Answer: 1,000,000

  ↳ source: anthropic.claude-opus-5 · canonical.context_window_tokens
    path: deterministic (exact-lookup) · human-reviewed 2026-08-10
    ⚠ STALE since 2026-09-09 — re-verify before relying on this
    https://platform.claude.com/docs/en/about-claude/models/overview
```

The value still comes back, and it is still flagged. An "authoritative" layer that goes quietly
out of date is worse than no authoritative layer.

**You have now reached a citation block, a routing decision, a refusal and a staleness warning
with no cloud account.**

---

## Part 2 — Your Elasticsearch, your documents

This is the adopter path, and it needs no Python.

### Prerequisites, in order

**1. An Elasticsearch cluster with ELSER.** Elastic Cloud Serverless (project type
*Elasticsearch*) is what this was built against; `.elser-2-elasticsearch` is preconfigured there.
A self-managed cluster works, but you deploy ELSER yourself and name your own inference endpoint.

**2. Credentials.** In the environment, or in a gitignored `.env` found by walking up from the
working directory. **This repo ships no `.env`** and will not borrow one from a sibling checkout.
[`.env.example`](../.env.example) is the template.

| Setting | |
|---|---|
| `ES_URL` · `ES_API_KEY` | required; never logged, never committed |
| `ES_INDEX` | the index every command reads and writes (default `grounded-context-corpus`) |
| `ES_INFERENCE_ID` | the ELSER endpoint the mapping is built against (default `.elser-2-elasticsearch`) |

`ES_INFERENCE_ID` is baked into the mapping when the index is created, so set it **before**
building the index. A wrong value is not a setting you correct later — it is an index you rebuild.

For a cluster behind a private CA, note that `ElasticsearchConfiguration.connect()` does not yet
wire an SSL context. That gap is tracked as
[#4](https://github.com/cdevarenne/grounded-context-jvm/issues/4); Serverless uses a public CA, so
nothing in the reference setup needs it.

**3. Your documents, as Markdown with YAML front matter.** One file per page, carrying
`id`, `title`, `url`, `provider`, `topic`, `fetched_at`.

### Build the index

```bash
export ES_INDEX=my-team-corpus
gctx index --corpus ./my-docs --recreate
```

The indexer creates the mapping the retrieval path depends on — the `exact_token` analyzer, the
`content.exact` subfield, and `semantic_text` wired to ELSER — and chunks by the rule in
[`docs/index-spec.md`](index-spec.md). That file is a contract with a copy in the Python repo,
guarded by `IndexSpecParityTest`, because an index built to a different rule returns different
ranks for the same corpus and every published number stops meaning anything.

Then everything else runs against your index:

```bash
gctx ask "how do we rotate credentials?"
```

**What does not port: fetching.** The Python repo's `scripts/fetch_corpus.py` collects *its*
reference corpus from public documentation. Deliberately not reimplemented — an adopting team
already has its own documents, and a fetcher tuned to someone else's sources is not useful.

### What an exploratory answer looks like

Against the reference corpus:

```console
$ gctx ask "How should I chunk documents for retrieval?"
router: SEMANTIC — exploratory phrasing ("how should i", "should i"), no exact field requested

Top passage: Generates embeddings during indexing: Automatically generates embeddings when you index documents, without requiring ingestion pipelines or inference processors.
Handles chunking: Automatically chunks long text documents during indexing.
…

  ↳ source: elastic-mapping-semantic-text · chunk:1
    path: semantic (hybrid(bm25+elser,rrf)) · indexed 2026-08-13 · score 0.0893
    https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/semantic-text

  ↳ source: elastic-inference-api · chunk:4
    path: semantic (hybrid(bm25+elser,rrf)) · indexed 2026-08-13 · score 0.0861
    https://www.elastic.co/docs/explore-analyze/elastic-inference/inference-api
```

Same citation contract, different `path:` — `semantic` names the retrieval method and the fused
score instead of a trust tier and a review date, because that is what a ranked result can honestly
claim. Chunks are cited, not pages, so the unit you can check is the unit that was retrieved.

**Without credentials this returns `Not found in the grounded sources.`** rather than failing. An
unavailable engine is a refusal, never an error.

### The eval set and the findings sweep

These are pinned to the *reference* corpus, so they are useful for checking your build rather than
your documents:

```bash
gctx eval                          # the 12-question set, with verdicts
gctx eval --compare rank_constant  # ELSER vs BM25 vs hybrid, for one query
gctx measure                       # the corpus-wide figures behind the findings
```

---

## Part 3 — From an agent, over MCP

```bash
gctx mcp        # serves on stdio; a client drives it
```

[`.mcp.json`](../.mcp.json) wires it up on clone. It is **not** a copy of the Python one, which
runs `uv run --extra mcp gctx-mcp`; this runs the jar, so build first. It names the Maven output,
because JSON carries no comment offering an alternative — with Gradle, change that one path to
`grounded-context-app/build/libs/gctx.jar`.

Three tools: `lookup_canonical_fact`, `ask_grounded`, `list_entities`, carrying the same citation
block the CLI prints.

All logging goes to stderr. Over stdio, **stdout is the JSON-RPC channel**.

---

## Part 4 — What the layer recorded about itself

Every answered query appends one event to a local log. No cluster, no configuration, and nothing
new on the answer path — so the readback works on the clone you just built:

```console
$ gctx telemetry summary
gctx telemetry summary — /opt/devel/grounded-context-jvm/var/telemetry.ndjson
events: 5   window: 2026-08-19T22:00:41.824Z .. 2026-08-19T22:00:47.790Z

route mix        DETERMINISTIC 1 (20%)   SEMANTIC 0 (0%)   BOTH 0 (0%)   DIRECT 4 (80%)
canonical        hit 4   miss 1   n/a 0      miss rate 20% of 5 precision queries
refusals         1 (20%)
floor            cleared 0   blocked 0      (of 0 semantic-consulted)
floor scores     blocked n/a   cleared n/a
latency p50 ms   deterministic 2.1   semantic n/a   total 2.1
latency p95 ms   deterministic 3.2   semantic n/a   total 4.7
```

Those are the six commands from Part 1, counted: four direct `lookup` calls (un-routed, so
`DIRECT`), one routed `ask`, one refusal. The `canonical` row is the curation backlog measured
rather than estimated — a *miss* is a precision query the bundle could not answer, and it is
deliberately distinct from `n/a`, a query that never asked for a canonical field at all.

The log is `var/telemetry.ndjson` at the repo root, gitignored. `GCTX_TELEMETRY_SINK` moves it;
`GCTX_TELEMETRY=0` turns recording off.

**Telemetry is an observer.** The event is built from the finished answer envelope and emitted
after it, best-effort — a sink that fails cannot change or block an answer. Each of those is
pinned by a test that fails when the guarantee does.

With Elasticsearch configured, project the log into a queryable index:

```console
$ gctx telemetry index
projected 6 events from /opt/devel/grounded-context-jvm/var/telemetry.ndjson into grounded-context-telemetry
```

The log is the source of truth; the index is a projection over it, rebuildable from it and never
the reverse — the same relationship `knowledge/` has to the corpus index. A Kibana dashboard over
that projection is committed in the Python repo.

The event schema is field-for-field the Python one and `gctx telemetry summary` prints
byte-identical output over the same log. The evidence is [`docs/parity.md`](parity.md#telemetry).

---

## Running the tests

```bash
mvn verify         # JUnit XML + text summaries in grounded-context-*/target/surefire-reports/
./gradlew build    # the same, plus HTML at grounded-context-*/build/reports/tests/test/index.html
```

**Expect tests to skip, and do not read that as a broken checkout.** Three gates:

| Gate | Skips when |
|---|---|
| Cluster tests | no `ES_URL` / `ES_API_KEY` |
| Reference-number tests | the index is not the reference corpus — `ReferenceCorpus` checks for it |
| `BundleParityTest`, `TelemetryParityTest` | the Python repo is not checked out alongside |

The second one matters most for you: **a green build against your own documents is the expected
outcome**, not a sign the Java port is broken. Tests pinning corpus-specific literals — 320 chunks, 44
of 149, the eight arm-comparison rows, the seventeen probe scores — describe the reference corpus,
not the code. Behavioral tests (citation shape, refusal, routing) run on any corpus.

Never edit one of those literals to make a test pass. A failure there means the index changed.

---

## When something does not work

| What you see | What it means |
|---|---|
| `Not found in the grounded sources.` on an exact fact | The field is not in `knowledge/`. Run `gctx entities` to see what is. This is the design working. |
| `Not found in the grounded sources.` on an exploratory question | No cluster reachable, or an empty index. Check `ES_URL` / `ES_API_KEY`, then re-run `gctx index`. |
| Exit code `1` | A grounded refusal. Not an error. |
| Exit code `2` | A real error — a malformed bundle, or a corpus directory that does not exist. |
| `⚠ STALE` in a citation | The `stale_after` date has passed. Re-verify the fact against its source; do not edit the date to silence it. |
| `Unable to access jarfile` | Build first, or you are pointing at the other build tool's output directory. |
| ELSER errors on a self-managed cluster | `ES_INFERENCE_ID` still points at the Serverless default. Set your own and rebuild the index. |
| Nothing in `var/telemetry.ndjson` | `GCTX_TELEMETRY` is set to `0`/`false`/`no`/`off`, or `GCTX_TELEMETRY_SINK` points elsewhere. |

---

## Where to go next

- [`docs/parity.md`](parity.md) — the evidence that this port reproduces the reference, and the
  one defect building it twice surfaced.
- [`docs/index-spec.md`](index-spec.md) — the chunking rule and mapping both implementations build
  to, so an index is the same whoever builds it.
- [`README.md`](../README.md) — the module rationale and what this deliberately is not.
- [grounded-context](https://github.com/cdevarenne/grounded-context) — the reference
  implementation, and where the thesis, the specs and the findings live. Two copies of an argument
  drift the way two copies of a bundle do, so they are not restated here.
