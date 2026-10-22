package com.mtagj.bptracker.core;

/**
 * Result of parsing a photo of a blood pressure monitor's display.
 * Any of the three fields can be null if the parser could not find a
 * confident value — the UI must always let the user review/correct these
 * before saving, never save them blindly.
 */
public class BPReading {

    public enum FieldSource {
        /** Found right next to a recognized "SYS"/"DIA"/"PULSE" label — high confidence. */
        LABEL_MATCH,
        /** No matching label found nearby; guessed purely from vertical position
         *  (top-to-bottom = SYS, DIA, PULSE, as on virtually every digital monitor). */
        POSITIONAL_FALLBACK,
        /** Field could not be determined at all. */
        NOT_FOUND
    }

    public final Integer systolic;
    public final Integer diastolic;
    public final Integer pulse;

    public final FieldSource systolicSource;
    public final FieldSource diastolicSource;
    public final FieldSource pulseSource;

    public BPReading(Integer systolic, FieldSource systolicSource,
                      Integer diastolic, FieldSource diastolicSource,
                      Integer pulse, FieldSource pulseSource) {
        this.systolic = systolic;
        this.systolicSource = systolicSource;
        this.diastolic = diastolic;
        this.diastolicSource = diastolicSource;
        this.pulse = pulse;
        this.pulseSource = pulseSource;
    }

    public boolean isComplete() {
        return systolic != null && diastolic != null && pulse != null;
    }

    /**
     * True once the two fields that matter most (systolic/diastolic) are both resolved --
     * pulse optional. Added 2026-09-23 for the live scanner: it used to require
     * {@link #isComplete()} (all 3) before ever showing the review screen, which meant a
     * reading that had SYS/DIA right but genuinely could not resolve PULSE from a given
     * camera frame (verified real case: her new Omron's PULSE digits print small enough,
     * and low-res live frames are coarse enough, that PULSE sometimes just isn't legible even
     * though SYS/DIA are) would never surface at all -- the scanner would spin forever on
     * "buscando..." instead of showing what it DID find. The review screen already handles a
     * null field correctly (blank box, low-confidence hint, she fills it in) -- that was
     * already how "usar foto existente" behaved. This lets the live scanner reach that same,
     * already-safe behavior instead of demanding a perfect frame that specific hardware/
     * lighting combinations may never produce.
     */
    public boolean hasSysDia() {
        return systolic != null && diastolic != null;
    }

    /** True if every resolved field came from a label match (nothing guessed by position). */
    public boolean isHighConfidence() {
        return (systolic == null || systolicSource == FieldSource.LABEL_MATCH)
                && (diastolic == null || diastolicSource == FieldSource.LABEL_MATCH)
                && (pulse == null || pulseSource == FieldSource.LABEL_MATCH);
    }

    @Override
    public String toString() {
        return "BPReading{sys=" + systolic + "(" + systolicSource + ")" +
                ", dia=" + diastolic + "(" + diastolicSource + ")" +
                ", pulse=" + pulse + "(" + pulseSource + ")}";
    }
}
