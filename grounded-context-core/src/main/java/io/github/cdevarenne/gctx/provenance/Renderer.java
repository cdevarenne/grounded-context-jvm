package io.github.cdevarenne.gctx.provenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Render an envelope for a terminal, per docs/specs/provenance.md. */
public final class Renderer {

    private Renderer() {
    }

    public static String render(Envelope envelope) {
        List<String> lines = new ArrayList<>();

        if (envelope.router() != null) {
            lines.add("router: " + envelope.router().route() + " — "
                    + envelope.router().rationale());
            lines.add("");
        }

        // A retrieved passage is not a synthesized answer, so only an exact hit may be called
        // one. On a mixed result the exact hit still leads, and still earns the label.
        List<Citation> citations = envelope.citations();
        boolean exact = citations.isEmpty()
                || Citation.DETERMINISTIC.equals(citations.getFirst().path());
        lines.add((exact ? "Answer: " : "Top passage: ") + envelope.answer());

        for (Citation cite : citations) {
            lines.add("");
            lines.add("  ↳ source: " + cite.sourceId() + " · " + cite.locator());
            lines.add("    path: " + detail(cite));
            if (cite.staleAfter() != null) {
                lines.add("    " + freshness(cite));
            }
            if (cite.hops().size() > 1) {
                lines.add("    traversed: " + String.join(" → ", cite.hops()));
            }
            if (cite.sourceUrl() != null) {
                lines.add("    " + cite.sourceUrl());
            }
        }

        if (citations.isEmpty()) {
            lines.add("");
            lines.add("  ↳ no grounded source — nothing was returned rather than guessed.");
        }
        return String.join("\n", lines);
    }

    private static String detail(Citation cite) {
        StringBuilder detail = new StringBuilder(cite.path() + " (" + cite.method() + ")");
        String trust = cite.trustTier();
        String verifiedAt = cite.verifiedAt() == null
                ? "" : cite.verifiedAt().substring(0, Math.min(10, cite.verifiedAt().length()));

        if (trust != null && !verifiedAt.isEmpty()) {
            detail.append(" · ").append(trust).append(' ').append(verifiedAt);
        } else if (trust != null) {
            detail.append(" · ").append(trust);
        } else if (!verifiedAt.isEmpty()) {
            // The semantic path has no OKF trust tier — only the date it was retrieved.
            detail.append(" · indexed ").append(verifiedAt);
        }
        if (cite.score() != null) {
            detail.append(String.format(Locale.ROOT, " · score %.4f", cite.score()));
        }
        return detail.toString();
    }

    private static String freshness(Citation cite) {
        if (cite.staleAfter() == null) {
            return "freshness: no stale_after set";
        }
        return cite.isStale()
                ? "⚠ STALE since " + cite.staleAfter() + " — re-verify before relying on this"
                : "fresh until " + cite.staleAfter();
    }
}
