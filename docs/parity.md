# Parity with the Python implementation

This repo exists to cross-check the numbers the Python repo publishes. A single codebase cannot
distinguish "this is true of the index" from "this is true of my code"; two can.

Both implementations read the **same Elasticsearch index** (`grounded-context-corpus`, 320
chunks, ELSER via `.elser-2-elasticsearch`) and the **same knowledge bundle**. Everything below
was produced by running both and diffing the output, on 2026-08-14.

## Result

| Surface | Compared | Outcome |
|---|---|---|
| CLI | 7 commands, stdout + exit codes | byte-identical |
| Semantic path | 4 queries incl. an out-of-domain refusal | byte-identical |
| MCP | instructions, tool schemas, 6 tool calls | identical except `verified_at` |
| Eval set | all 12 questions, verdicts and routes | byte-identical |
| Compare table | all 8 rows of the arm comparison | byte-identical |
| Findings sweep | every corpus-wide aggregate, 17 probes | byte-identical |

**One divergence across the whole system.** It is described at the bottom, and the Python side
looks like the one that is wrong.

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

The `0 of 87` is the load-bearing one. It is the figure that disproved the original
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

`rank_window_size` is where fusion **loses** to BM25 alone. It reproducing here matters more
than the rows where hybrid wins: the narrowed claim — *fusion is never worse than the weaker
arm, but does not always beat the stronger one* — is now supported by two implementations,
including the row that limits it.

## The one divergence

| | `verified_at` |
|---|---|
| JVM | `2026-08-10T19:06:23-07:00` |
| Python | `2026-08-10 19:06:23-07:00` |

Everything else on the MCP surface matches: the instruction text, the tool names, the required
arguments, the other twelve citation fields, and the rendered block (both truncate to
`2026-08-10`).

The bundle file literally contains the `T` form. PyYAML parses that scalar into a `datetime`, and
`str()` renders it with a space — so **Python reformats a timestamp it should be quoting**, and
the result is not valid ISO-8601. Provenance should reproduce its source, not reinterpret it.

This implementation avoids it with a YAML resolver that leaves timestamp-shaped scalars as text.
That was not a stylistic choice: the default behaviour produced a **date off by one**. SnakeYAML
turns `stale_after: 2026-09-09` into a `java.util.Date` at UTC midnight, and reading it back in a
negative-offset zone yields `2026-09-08` — every staleness boundary silently a day early, facts
declared stale before they are. Both bugs are pinned by `BundleTest`.

Tracked as JVM-10. Unresolved on purpose: fixing it means changing a public, pushed repo.
