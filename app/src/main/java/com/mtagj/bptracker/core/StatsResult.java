package com.mtagj.bptracker.core;

/**
 * Result of computing descriptive statistics over a list of numeric readings
 * (systolic, diastolic or pulse) for a given period.
 *
 * Mirrors the columns already used in the author's spreadsheet:
 * n, AVG, MEDIAN, MAX, MIN, STD DEV, MODE.
 *
 * Pure data holder — no Android dependencies, so it can be used identically
 * inside the app and inside plain-Java unit tests.
 */
public class StatsResult {

    public final int n;
    public final double avg;
    public final double median;
    public final int max;
    public final int min;
    public final double stdDev;
    /** May be Double.NaN if there is no unique/most-frequent mode is ambiguous but a value is still returned (first smallest mode, Excel MODE.SNGL behavior). */
    public final double mode;
    /** True when every value appears exactly once (no repeats) — Excel's MODE returns #N/A in that case. */
    public final boolean modeUndefined;

    public StatsResult(int n, double avg, double median, int max, int min, double stdDev, double mode, boolean modeUndefined) {
        this.n = n;
        this.avg = avg;
        this.median = median;
        this.max = max;
        this.min = min;
        this.stdDev = stdDev;
        this.mode = mode;
        this.modeUndefined = modeUndefined;
    }

    @Override
    public String toString() {
        return "StatsResult{n=" + n + ", avg=" + avg + ", median=" + median +
                ", max=" + max + ", min=" + min + ", stdDev=" + stdDev +
                ", mode=" + (modeUndefined ? "N/A" : mode) + "}";
    }
}
