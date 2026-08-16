# Parity with the Python implementation

This is the evidence that the port reproduces the reference implementation. A team adopting it
gets the same system, not a similar one.

It is also a cross-check on the reference. Building the same behaviour twice, from the same
specification, in two languages surfaces defects that neither codebase can show on its own —
and it found one.

**What this can and cannot show.** Elasticsearch computes BM25, ELSER and the RRF fusion. Both
implementations are clients that send a query and render what comes back, so agreeing on a score
is expected and proves nothing about the score. What the comparison does rule out is an artifact
in the client code — YAML parsing, query construction, provenance rendering, and the sweep's
identifier enumeration — which is exactly where the defect below was found.

One part is genuinely independent: **the index build**. Each implementation computes chunk
boundaries itself, from the same source pages. See
[the independently built index](#the-independently-built-index).

Unless stated otherwise, figures below were produced on 2026-08-14 by running both
implementations against the reference index (`grounded-context-corpus`, 320 chunks, ELSER via
`.elser-2-elasticsearch`) and the same knowledge bundle, and diffing the output.

## Result

| Surface | Compared | Outcome |
|---|---|---|
| CLI | 7 commands, stdout + exit codes | byte-identical |
| Semantic path | 4 queries incl. an out-of-domain refusal | byte-identical |
| MCP | instructions, tool schemas, 6 tool calls | byte-identical |
| Eval set | all 12 questions, verdicts and routes | byte-identical |
| Compare table | all 8 rows of the arm comparison | byte-identical |
| Findings sweep | every corpus-wide aggregate, 17 probes | byte-identical |

**No divergences.** The cross-check found one, in `verified_at`. The Python implementation was
corrected. The two now agree on every field. The section at the bottom records what it was.

## How it was checked

Each surface was run in both implementations and passed through `diff`, rather than compared by
eye. For example:

```console
$ diff /tmp/java_cli.txt /tmp/py_cli.txt && echo "*** BYTE-IDENTICAL ACROSS ALL 7 COMMANDS ***"
*** BYTE-IDENTICAL ACROSS ALL 7 COMMANDS ***

$ diff /tmp/j_measure.txt /tmp/p_measure.txt && echo "*** IDENTICAL ***"
*** IDENTICAL ***
```

The claims are also pinned as tests, so they fail on regression rather than needing to be
re-run by hand:

| Test | Pins |
|---|---|
| `GctxCommandTest` | rendered output and exit codes of every subcommand |
| `HybridSemanticSearchTest` | the published arm ranks, the floor, the RRF-score claim |
| `GroundedMcpServerTest` | tool names, required arguments, the instruction text |
| `EvalHarnessTest` | all 8 rows of the compare table, as literals |
| `FindingsSweepTest` | 44/149, 0/87, 137/568, the 6/1 counts, rank 3 → 1 |
| `BundleParityTest` | the two `knowledge/` copies, byte-for-byte and parsed |

## The aggregates, recomputed

`gctx measure` against the same index:

```
Finding 2 — why the subfield helps, on rank_constant (elastic-rrf:chunk:1)
    matches on content        6 chunks
    matches on content.exact  1 chunk
    rank of the defining chunk: content-only 3 -> with exact 1

Finding 2 — rank improved by the content.exact subfield
  (tokens unique to one chunk, longer than 10 characters)
    hyphenated    44 of 149 improved, 0 regressed
    underscored    0 of  87 improved, 0 regressed

  tokens matching `content` but INVISIBLE to `content.exact`
  (hyphenated or underscored, at least 8 characters):
    137 of 568
    cited in findings.md: batch_id             in the set
    cited in findings.md: claude-sonnet-4-6    in the set
```

The `0 of 87` is the important one. It is the measurement that settled the original hypothesis —
that the standard analyzer splits `rank_constant` on the underscore — and it is re-derived here
rather than read back: each implementation enumerates the corpus tokens itself and counts what
the subfield changes. An enumeration defect on one side would show up as a different denominator.

The regex patterns were copied to match, so agreement on them is weak evidence on its own. The
enumeration, chunking and counting around them are separate code.

## The arm comparison, recomputed

All eight rows reproduce, including the counter-example that constrains the claim:

| Identifier | Phrasing | ELSER | BM25 | Hybrid |
|---|---|---|---|---|
| `rank_constant` | token | 5 | 1 | **1** |
| `rank_constant` | sentence | 2 | 3 | **1** |
| `num_candidates` | token | 1 | 1 | **1** |
| `num_candidates` | sentence | 2 | 5 | **1** |
| `anthropic-ratelimit-tokens-reset` | token | 2 | 1 | **1** |
| `anthropic-ratelimit-tokens-reset` | sentence | 1 | 1 | **1** |
| `rank_window_size` | token | 6 | 2 | 5 |
| `rank_window_size` | sentence | 7 | 2 | 3 |

`rank_window_size` is where fusion **loses** to BM25 alone. That it reproduces here matters more
than the rows where hybrid wins: the narrowed claim — *fusion is never worse than the weaker
arm, but does not always beat the stronger one* — is now supported by two implementations,
including the row that limits it.

## The independently built index

Everything above compares two clients reading one index, so it says nothing about how that index
was built. This section does.

`gctx index` builds the index from the corpus pages: it chunks them, creates the mapping, and
bulk-loads. Chunk boundaries are computed by each implementation from the same source text, not
read back from Elasticsearch. Two checks follow from that.

**The chunks agree.** All 320 chunks the Java indexer computes are byte-identical to the ones in
the reference index, document ids included. `CorpusIndexerTest` pins this.

**The numbers survive a separate index.** A second index was built from the same corpus using
only the Java indexer, and the whole suite was run against it on 2026-08-14; the test suite has grown since:

```console
$ ES_INDEX=grounded-context-corpus-jvm \
    java -jar grounded-context-app/target/gctx.jar index --corpus ../grounded-context/corpus/raw --recreate
created index grounded-context-corpus-jvm (semantic_text via .elser-2-elasticsearch)
indexed 320 chunks, 0 errors

$ ES_INDEX=grounded-context-corpus-jvm mvn test
Tests run: 105, Failures: 0, Errors: 0, Skipped: 0
```

Every pinned literal held: 44 of 149, 0 of 87, 137 of 568, the 6/1 match counts, rank 3 → 1, all
eight rows of the arm comparison, all seventeen probe scores, and the eval verdicts. A full
`measure` run against the two indices differs only in the index name it prints.

This is narrow but real. It shows the published aggregates are a property of the corpus and the
retrieval configuration, not of one particular index build. It still does not audit
Elasticsearch's scoring — the same engine scored both.

## What the cross-check found

The two implementations disagreed on one field:

| | `verified_at`, before the fix |
|---|---|
| JVM | `2026-08-10T19:06:23-07:00` |
| Python | `2026-08-10 19:06:23-07:00` |

The bundle file contains the `T` form. PyYAML resolved that scalar to a `datetime`. Python's
`str()` then rendered it with a space. The result was not valid ISO-8601, and it was not what the
file says. Provenance must quote its source. It must not reinterpret it.

Neither implementation could surface this alone. Each was self-consistent, and each rendered
what its own YAML library produced. Only the comparison made the difference visible.

Both implementations now keep timestamp-shaped scalars as text. Python uses a loader without the
timestamp resolver. This repo uses `LiteralTimestampResolver`. A second run of the same six MCP
calls shows no differences.

The same conversion is more dangerous on the JVM, and this one is a different kind of finding:
not a disagreement between implementations, but a defect the ported tests caught before it
shipped. SnakeYAML makes `stale_after: 2026-09-09` a `java.util.Date` at UTC midnight. In a
negative-offset zone, that date reads back as 2026-09-08. Every staleness boundary would move
one day earlier, and facts would be stale before their date.

The tests were ported before the code, which is why it surfaced on the first run. `BundleTest`
pins both behaviours.
