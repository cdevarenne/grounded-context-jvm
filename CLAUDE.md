# CLAUDE.md — grounded-context (JVM)

## What this is
A Java/Spring port of [grounded-context](https://github.com/cdevarenne/grounded-context), built so
a JVM team can clone it, point it at their own Elasticsearch and their own documents, and run a
grounded context layer without rebuilding it from a blog post.

**The rationale is deliberately not here.** The thesis, the design argument, the specs and the
findings live in the Python repo. Two copies of an argument drift the way two copies of a bundle
do. Read them there. Do not restate them here.

## Invariants — an agent working here can break every one of these

**`grounded-context-core` stays framework-free.** One compile dependency: SnakeYAML. No Spring, no
HTTP client, no Elasticsearch. The README publishes this as a claim a reader can check in one
command, so a second dependency does not just add weight — it makes the README false. Anything
probabilistic plugs in through `SemanticSearch`, an interface `core` defines and never implements.

**Two builds, kept in step.** Maven and Gradle both build all of it. A dependency or version change
goes in `pom.xml` *and* `gradle/libs.versions.toml`; `BuildParityTest` fails otherwise. It also
pins `maven.compiler.release` against `options.release`.

**`knowledge/` is a copy.** The Python repo holds the original. `BundleParityTest` compares them
when that repo is checked out alongside, or wherever `GCTX_REFERENCE_BUNDLE` points. Change the
original first, then sync.

**`docs/index-spec.md` is a contract with a copy in the other repo.** `IndexSpecParityTest` guards
both the copies and the constants they state. Changing the chunking rule or the mapping changes
ranks, which silently invalidates every published number.

**The pinned numbers describe the reference corpus, not the code.** 320 chunks, 44 of 149, 0 of 87,
the eight arm-comparison rows. `ReferenceCorpus` gates those tests so they skip on an adopter's
index. Never edit a literal to make one pass: a failure means the index changed.

**The README test count is drift-tested.** Add a test and `ReadmeCountCheck` fails at `mvn verify`
or `./gradlew build` until the README states the new number.

**Refusal is a result, not a failure.** Exit 1 is a grounded refusal; exit 2 is a real error. An
unreachable Elasticsearch returns `Not found in the grounded sources.` Never fall back to the
model's own memory, and never let the semantic path answer an exact fact.

**Every answer carries a citation block** — source, retrieval path, trust tier, staleness. No
answer without one.

## Scope
Read-only. No auth, no scale, no multi-tenancy. **No corpus fetching**: the Python repo's
`scripts/fetch_corpus.py` collects the reference corpus, and an adopting team already has its own
documents.

## Toolchain
Java 21 is the compiler release — the floor a consumer must clear, not the JDK this develops on.
Build with `mvn package` or `./gradlew build`; the jar lands in `target/` or `build/libs/`
respectively. Elasticsearch settings come from the environment or a gitignored `.env`: `ES_URL`,
`ES_API_KEY`, `ES_INDEX`. This repo ships no `.env` and will not read a sibling checkout's.
