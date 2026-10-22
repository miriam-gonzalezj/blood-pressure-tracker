package com.mtagj.bptracker.data;

/**
 * A Measurement plus the values the author's spreadsheet derives from the reading
 * that came immediately before it chronologically: "días transcurridos",
 * "var sistólica" and "var diastólica". All three are null for the very
 * first reading in a series (there is nothing to compare it to).
 *
 * Always computed on the fly from a chronologically-sorted list — see
 * MeasurementRepository.withDeltas() — never stored, so edits/deletes of
 * older entries can't leave stale deltas behind.
 */
public class MeasurementWithDeltas {
    public final Measurement measurement;
    public final Integer daysElapsed;
    public final Integer varSystolic;
    public final Integer varDiastolic;

    public MeasurementWithDeltas(Measurement measurement, Integer daysElapsed, Integer varSystolic, Integer varDiastolic) {
        this.measurement = measurement;
        this.daysElapsed = daysElapsed;
        this.varSystolic = varSystolic;
        this.varDiastolic = varDiastolic;
    }
}
