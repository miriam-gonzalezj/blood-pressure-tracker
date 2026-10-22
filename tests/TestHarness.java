import com.mtagj.bptracker.core.*;
import java.util.*;

/**
 * Standalone command-line test harness for the pure-Java core logic
 * (BPStatistics). No Android SDK involved -- this is the exact same source
 * that ships inside the Android app's com.mtagj.bptracker.core package,
 * compiled and run with plain javac/java.
 *
 * The dataset below is SYNTHETIC (invented for this repository), not real
 * health data. The original development of this app verified BPStatistics
 * against a real 81-reading dataset, matching it exactly (including
 * confirming population vs. sample standard deviation); that verification
 * is described in the README, but the real readings themselves are not
 * published here.
 */
public class TestHarness {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("=== BPStatistics tests against a synthetic sample dataset ===");
        testStatistics();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------
    // Statistics tests
    // ---------------------------------------------------------------

    static void testStatistics() {
        // Synthetic example data -- NOT real measurements. Plausible resting
        // blood pressure/pulse values, invented for this test.
        int[] sysArr = {118,122,115,120,124,117,119,121,116,123,118,120,117,122,119,121,118,116,124,120};
        int[] diaArr = {76,80,74,78,82,75,77,79,74,81,76,78,75,80,77,79,76,74,82,78};
        int[] pulArr = {68,72,65,70,74,67,69,71,64,73,68,70,67,72,69,71,68,66,74,70};

        checkStats("systolic", sysArr, 20, 119.5, 119.5, 124, 115, 2.598076211353316, 118);
        checkStats("diastolic", diaArr, 20, 77.55, 77.5, 82, 74, 2.519424537468824, 76);
        checkStats("pulse", pulArr, 20, 69.4, 69.5, 74, 64, 2.764054992217051, 68);
    }

    static void checkStats(String label, int[] arr, int expectedN, double expectedAvg, double expectedMedian,
                            int expectedMax, int expectedMin, double expectedStdDev, double expectedMode) {
        List<Integer> list = new ArrayList<>();
        for (int v : arr) list.add(v);
        StatsResult r = BPStatistics.compute(list);

        assertEquals(label + ".n", expectedN, r.n);
        assertClose(label + ".avg", expectedAvg, r.avg, 1e-9);
        assertClose(label + ".median", expectedMedian, r.median, 1e-9);
        assertEquals(label + ".max", expectedMax, r.max);
        assertEquals(label + ".min", expectedMin, r.min);
        assertClose(label + ".stdDev (population)", expectedStdDev, r.stdDev, 1e-9);
        assertClose(label + ".mode", expectedMode, r.mode, 1e-9);
        assertEquals(label + ".modeUndefined", false, r.modeUndefined);
        System.out.println("  " + label + " -> " + r);
    }

    // ---------------------------------------------------------------
    // Tiny assertion helpers
    // ---------------------------------------------------------------

    static void assertEquals(String what, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL " + what + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void assertClose(String what, double expected, double actual, double eps) {
        if (Math.abs(expected - actual) <= eps) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL " + what + ": expected=" + expected + " actual=" + actual);
        }
    }
}
