package io.github.cdevarenne.gctx.bundle;

import java.util.List;
import java.util.Map;

/**
 * OKF v0.2 does not store a trust tier; it stores who verified a concept. The tier is
 * derived from that actor list, so it cannot be asserted independently of the evidence.
 */
public enum TrustTier {
    UNVERIFIED("unverified"),
    MACHINE_CONFIRMED("machine-confirmed"),
    HUMAN_REVIEWED("human-reviewed");

    private final String label;

    TrustTier(String label) {
        this.label = label;
    }

    /** The wire form, matching the Python implementation and docs/specs/provenance.md. */
    public String label() {
        return label;
    }

    /**
     * Derive the tier from OKF {@code verified[]} entries.
     *
     * <p>A human actor outranks a machine one: any {@code human:} prefix promotes the whole
     * concept, because the strongest evidence is what a reader should be told about.
     */
    public static TrustTier from(List<Map<String, Object>> verified) {
        if (verified == null || verified.isEmpty()) {
            return UNVERIFIED;
        }
        boolean human = verified.stream()
                .map(entry -> String.valueOf(entry.getOrDefault("by", "")))
                .anyMatch(actor -> actor.startsWith("human:"));
        return human ? HUMAN_REVIEWED : MACHINE_CONFIRMED;
    }
}
