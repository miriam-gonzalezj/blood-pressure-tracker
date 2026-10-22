package com.mtagj.bptracker.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descriptive statistics calculator, matching exactly the formulas already
 * used in the author's own spreadsheet (verified against a real multi-month
 * dataset of readings):
 *
 *   - AVG      -> arithmetic mean
 *   - MEDIAN   -> standard median (average of the two middle values for even n)
 *   - MAX/MIN  -> straightforward
 *   - STD DEV  -> POPULATION standard deviation (Excel STDEVP / STDEV.P),
 *                 NOT sample stdev. Confirmed against the real dataset to
 *                 8 significant figures; the sample-stdev formula gives a
 *                 visibly different result and does NOT match the sheet.
 *   - MODE     -> Excel-style single mode: the most frequent value; if there
 *                 is a tie, the value that occurs first when scanning the
 *                 input in its original (chronological) order — this is how
 *                 Excel's MODE/MODE.SNGL resolves ties. If every value is
 *                 unique (no repeats at all), Excel returns #N/A, exposed
 *                 here as StatsResult.modeUndefined = true.
 *
 * Pure Java, no Android dependency, so this class is used unmodified both
 * inside the app and inside the standalone command-line test harness.
 */
public final class BPStatistics {

    private BPStatistics() {
    }

    public static StatsResult compute(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute statistics on an empty list of readings");
        }

        int n = values.size();

        long sum = 0;
        int max = values.get(0);
        int min = values.get(0);
        for (int v : values) {
            sum += v;
            if (v > max) max = v;
            if (v < min) min = v;
        }
        double avg = (double) sum / n;

        double median = median(values);

        double variance = 0.0;
        for (int v : values) {
            double d = v - avg;
            variance += d * d;
        }
        variance /= n; // population variance (divide by n, not n-1)
        double stdDev = Math.sqrt(variance);

        Map<Integer, Integer> frequency = new LinkedHashMap<>(); // preserves first-seen order
        for (int v : values) {
            frequency.merge(v, 1, Integer::sum);
        }
        int maxFreq = 0;
        for (int f : frequency.values()) {
            if (f > maxFreq) maxFreq = f;
        }
        boolean modeUndefined = (maxFreq <= 1);
        double mode = 0;
        if (!modeUndefined) {
            // first value (in original order) whose frequency equals maxFreq
            for (int v : values) {
                if (frequency.get(v) == maxFreq) {
                    mode = v;
                    break;
                }
            }
        }

        return new StatsResult(n, avg, median, max, min, stdDev, mode, modeUndefined);
    }

    private static double median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        } else {
            return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }
    }
}
