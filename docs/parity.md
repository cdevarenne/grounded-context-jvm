# Parity with the Python implementation

This repo provides a cross-check of the numbers the Python repo publishes. A single codebase cannot
distinguish "this is true of the index" from "this is true of my code"; two can.

Both implementations read the **same Elasticsearch index** (`grounded-context-corpus`, 320
chunks, ELSER via `.elser-2-elasticsearch`) and the **same knowledge bundle**. Everything below
was produced by running both and diffing the output, on 2026-08-14.

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

The `0 of 87` is the important one. It is the figure that disproved the original
"the analyzer splits `rank_constant` on the underscore" claim, and it now falls out of an
implementation with a different regex engine, a different YAML parser and a different
Elasticsearch client. A subtle extraction bug in the Python sweep would have shown up here.

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

## What the cross-check found

The two implementations disagreed on one field:

| | `verified_at`, before the fix |
|---|---|
| JVM | `2026-08-10T19:06:23-07:00` |
| Python | `2026-08-10 19:06:23-07:00` |

The bundle file contains the `T` form. PyYAML resolved that scalar to a `datetime`. Python's
`str()` then rendered it with a space. The result was not valid ISO-8601, and it was not what the
file says. Provenance must quote its source. It must not reinterpret it.

Both implementations now keep timestamp-shaped scalars as text. Python uses a loader without the
timestamp resolver. This repo uses `LiteralTimestampResolver`. A second run of the same six MCP
calls shows no differences.

The same conversion is more dangerous on the JVM. SnakeYAML makes `stale_after: 2026-09-09` a
`java.util.Date` at UTC midnight. In a negative-offset zone, that date reads back as 2026-09-08.
Every staleness boundary would move one day earlier. Facts would be stale before their date. The
ported tests found this on the first run. `BundleTest` pins both behaviours.

Neither implementation could show the `verified_at` defect alone. Each one was self-consistent.
