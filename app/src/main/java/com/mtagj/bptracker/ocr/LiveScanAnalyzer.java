package com.mtagj.bptracker.ocr;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.mtagj.bptracker.core.BPReading;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Runs {@link SevenSegmentDecoder} directly on the live CameraX preview feed
 * -- no photo is ever taken or saved, the frame lives only as an in-memory
 * OpenCV Mat for the duration of one analysis call and is released
 * immediately after. As soon as one frame decodes into a usable reading,
 * the listener is notified -- the review screen that follows is always
 * editable, so confirming correctness there (rather than by waiting for
 * repeated identical frames here) is the intended checkpoint.
 *
 * UPDATED (2026-09-23): the live scanner stopped detecting anything on a
 * second Omron monitor I use (same HEM-7120 model, different physical
 * unit). Diagnosed against a real photo taken holding the phone at the
 * actual distance/angle used to scan (not a generic gallery photo) --
 * verified, not guessed:
 *
 * 1) The single fixed guide-box crop this used to use (just GUIDE_WIDTH/
 *    HEIGHT_FRACTION, one decode() call per frame) found ZERO digit boxes on
 *    that real frame. Confirmed by dumping the raw contours before any of
 *    SevenSegmentDecoder's own filtering (internal to that class, private and
 *    not included in this repository): at that crop size, the display's
 *    own outer bezel/border line thresholds as foreground and happens to be
 *    pixel-connected to the digits, so the whole display -- border and
 *    digits together -- comes out as ONE oversized contour, which
 *    SevenSegmentDecoder correctly rejects as not digit-shaped --
 *    taking every real digit down with it. Fix: try several progressively
 *    tighter centered crops per frame instead of one fixed crop (see
 *    CROP_ATTEMPTS below) -- ported from the same, already-verified pattern
 *    {@link OcrHelper#recognize} has used for gallery photos since the
 *    start of "usar foto existente". A tighter crop excludes the outer
 *    border before it ever reaches the decoder. Verified against that same
 *    real photo: the fixed crop found nothing, but a tighter crop within it
 *    correctly reads SYS/DIA (matching the monitor's real display).
 * 2) Even with that fix, the same real frame's PULSE digit doesn't resolve
 *    at live-camera-frame scale (confirmed separately: not a wrong value,
 *    genuinely unresolved, same safety behavior as always -- see
 *    SevenSegmentDecoder). Under the OLD rule here (require ALL 3 fields
 *    before ever notifying the listener), that meant the scanner would
 *    never advance past "buscando..." for this photo, indistinguishable
 *    from detecting nothing at all -- even though SYS/DIA were being read
 *    correctly on every frame. Fix: see {@link BPReading#hasSysDia()}.
 *
 * Confirmed working on the real device the same day: SYS/DIA now read
 * correctly live, PULSE came back blank (not wrong) exactly as this fix
 * intends -- chasing PULSE specifically further (never at the cost of a
 * wrong value) led to a THIRD, separate finding, all against that same
 * real photo (not guessed):
 *
 * 3) At the (0.55, 0.38) crop where SYS/DIA first resolve, PULSE's two
 *    digits were being split into a separate top half and bottom half by
 *    one of the decoder's internal morphology steps (private, not included
 *    in this repository) -- confirmed by drawing the actual detected boxes
 *    on the real photo and seeing that each one only covered a digit's
 *    upper loop, never its lower loop, which is why they didn't match the
 *    decoder's expected digit proportions and were filtered out. Two fixes
 *    were tried and REJECTED here specifically because they produced a
 *    wrong digit, not because they were merely unhelpful: loosening that
 *    internal shape filter let the (still only-half-a-digit) box through
 *    the decoder's digit-recognition step, which read it as a confident
 *    but wrong digit; adjusting another internal parameter to force the
 *    two halves to merge eventually did merge them, but the resulting box
 *    still decoded to the wrong digit every time it was tried -- rejected
 *    for the same reason. Neither required any change outside the private
 *    decoder.
 *    The fix that actually worked: an in-between crop step, (0.50, 0.34),
 *    added to CROP_ATTEMPTS between the existing (0.55, 0.38) and
 *    (0.45, 0.30). At that specific zoom level PULSE's strokes render
 *    solid instead of splitting, and the existing pipeline -- completely
 *    unchanged -- reads SYS/DIA/PULSE all three correctly on the first try.
 *    Verified against the same real photo (exact match against the
 *    monitor's display) and re-checked against all 7 previously-verified
 *    real photos from this project (the 4 original references plus the 3
 *    from the second tensiómetro): no change to any of their results, and
 *    the two cases that already produced a stray/partial digit on their
 *    own (unrelated to this change, present before it too) stay safely
 *    blocked by {@link BPReading#hasSysDia()} either way, exactly as
 *    before.
 *    This part (point 3, the extra crop step) is NOT yet confirmed on a
 *    real device -- same as before, verified only against the real photo
 *    and the Python/OpenCV port, pending a retest.
 */
public class LiveScanAnalyzer implements ImageAnalysis.Analyzer {

    public interface Listener {
        /** Called on every analyzed frame, even incomplete ones -- useful for a "still searching…" UI. */
        void onFramePreview(BPReading latestGuess);

        /** Called exactly once a reading has been confirmed stable across several consecutive frames. */
        void onReadingStabilized(BPReading reading);
    }

    private static final String TAG = "BPScan"; // filter Logcat by this tag to see per-frame diagnostics
    private static final long MIN_INTERVAL_MS = 350; // throttle: don't run the decoder on every single frame
    private static final int STABLE_FRAMES_REQUIRED = 1;

    // Must match the "aim here" guide box drawn in activity_main.xml
    // (app:layout_constraintWidth_percent / Height_percent on scanGuideBox). Only the pixels
    // inside this centered box are ever analyzed -- everything else in the camera's field of
    // view (the table, her hand, the rest of the room) is discarded before SevenSegmentDecoder
    // even runs. This is what earlier testing was missing: the decoder was verified only
    // against photos already cropped tightly to the display, but a live camera frame is the
    // WHOLE scene, and without this crop the decoder was finding "digit-shaped" noise
    // (edges, glare, background clutter) anywhere in that whole scene, not just on the
    // monitor's screen.
    // Widened from 0.78 to 0.85: her SYS reading's leading "1" (as in "119") was
    // intermittently missing from the decoded rows, consistent with that narrow digit sitting
    // right at the crop's left edge and getting clipped depending on exactly how she holds the
    // phone. More margin makes that less likely without giving up much of the background
    // exclusion the crop was added for.
    private static final double GUIDE_WIDTH_FRACTION = 0.85;
    private static final double GUIDE_HEIGHT_FRACTION = 0.50;

    /**
     * Crop fractions to try per frame, loosest to tightest -- same idea and same values as
     * {@link OcrHelper#CROP_ATTEMPTS} (minus its 1.00/1.00 "no crop" first step, which a live
     * frame doesn't need: the guide box already keeps the analyzed region roughly centered on
     * the display, unlike an arbitrary gallery photo). The first entry matches the on-screen
     * guide box exactly, so behavior for a frame that already worked under the old single-crop
     * code is unchanged; the following, tighter crops are only reached when that one comes up
     * empty or incomplete -- see the class Javadoc for why a single fixed crop wasn't enough.
     */
    private static final double[][] CROP_ATTEMPTS = {
            {GUIDE_WIDTH_FRACTION, GUIDE_HEIGHT_FRACTION},
            {0.75, 0.46},
            {0.65, 0.42},
            {0.55, 0.38},
            {0.50, 0.34},
            {0.45, 0.30},
            {0.45, 0.24},
    };

    private final Listener listener;

    private volatile boolean paused = false;
    private volatile boolean confirmed = false;
    private long lastAnalysisTime = 0L;
    private BPReading lastReading = null;
    private int stableCount = 0;

    public LiveScanAnalyzer(Listener listener) {
        this.listener = listener;
    }

    /** Call after a save (or "escanear de nuevo") to start looking for a fresh reading again. */
    public void reset() {
        confirmed = false;
        lastReading = null;
        stableCount = 0;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        long now = System.currentTimeMillis();
        boolean skip = paused || confirmed || (now - lastAnalysisTime) < MIN_INTERVAL_MS;
        if (skip) {
            imageProxy.close();
            return;
        }
        lastAnalysisTime = now;

        try {
            Mat gray = grayMatFromImageProxy(imageProxy);
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            Mat upright = rotate(gray, rotation);
            if (upright != gray) gray.release();

            android.util.Log.d(TAG, "frame " + upright.cols() + "x" + upright.rows()
                    + " rotation=" + rotation + " -- probando " + CROP_ATTEMPTS.length + " recortes");

            // Try progressively tighter centered crops, same pattern as OcrHelper.recognize()
            // for gallery photos -- keep whichever attempt filled the most fields so far, stop
            // early once a frame is fully complete. See the class Javadoc (2026-09-23) for why
            // one fixed crop isn't enough: it can catch the display's own outer border and lose
            // every digit to that, not just the small/far-away ones.
            BPReading best = null;
            for (double[] fractions : CROP_ATTEMPTS) {
                Mat roi = cropCenter(upright, fractions[0], fractions[1]);
                List<String> rows = SevenSegmentDecoder.decode(roi);
                roi.release();
                BPReading candidate = SevenSegmentDecoder.toReading(rows);
                if (best == null || filledFieldCount(candidate) > filledFieldCount(best)) {
                    best = candidate;
                }
                if (candidate.isComplete()) {
                    break;
                }
            }
            upright.release();

            android.util.Log.d(TAG, "best reading this frame: " + best);

            listener.onFramePreview(best);
            handleReading(best);
        } catch (Throwable e) {
            // A single frame failing to decode is normal (motion blur, glare passing
            // through, phone still moving into position) -- log it and skip, never crash.
            // Logged as an error (not silently swallowed) precisely so a *persistent*
            // failure -- as opposed to an occasional bad frame -- is visible in Logcat.
            android.util.Log.e(TAG, "frame analysis failed", e);
        } finally {
            imageProxy.close();
        }
    }

    /** The Y plane of a YUV_420_888 camera frame IS the grayscale luma image -- no color conversion needed. */
    private static Mat grayMatFromImageProxy(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();

        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        Mat full = new Mat(height, rowStride, CvType.CV_8UC1);
        full.put(0, 0, data);
        if (rowStride == width) {
            return full;
        }
        Mat cropped = full.submat(0, height, 0, width).clone();
        full.release();
        return cropped;
    }

    /** Centered crop to the given width/height fraction of the frame -- see CROP_ATTEMPTS above. */
    private static Mat cropCenter(Mat mat, double widthFraction, double heightFraction) {
        int w = mat.cols();
        int h = mat.rows();
        int cropW = (int) Math.round(w * widthFraction);
        int cropH = (int) Math.round(h * heightFraction);
        int x0 = Math.max(0, (w - cropW) / 2);
        int y0 = Math.max(0, (h - cropH) / 2);
        int x1 = Math.min(w, x0 + cropW);
        int y1 = Math.min(h, y0 + cropH);
        return mat.submat(y0, y1, x0, x1).clone();
    }

    /** Cuántos de los 3 campos trae resueltos -- usado para comparar intentos de recorte (ver OcrHelper.filledFieldCount). */
    private static int filledFieldCount(BPReading reading) {
        int n = 0;
        if (reading.systolic != null) n++;
        if (reading.diastolic != null) n++;
        if (reading.pulse != null) n++;
        return n;
    }

    private static Mat rotate(Mat mat, int degrees) {
        if (degrees == 0) return mat;
        Mat out = new Mat();
        if (degrees == 90) {
            Core.rotate(mat, out, Core.ROTATE_90_CLOCKWISE);
        } else if (degrees == 180) {
            Core.rotate(mat, out, Core.ROTATE_180);
        } else if (degrees == 270) {
            Core.rotate(mat, out, Core.ROTATE_90_COUNTERCLOCKWISE);
        } else {
            return mat;
        }
        return out;
    }

    private void handleReading(BPReading reading) {
        // Used to require isComplete() (all 3 fields) -- relaxed to just SYS+DIA (2026-09-23,
        // see BPReading.hasSysDia() and the class Javadoc above): a frame that has SYS/DIA
        // right but can't resolve PULSE should still reach the review screen, which already
        // shows a blank, editable field for whatever wasn't read rather than blocking on it.
        if (!reading.hasSysDia()) {
            stableCount = 0;
            lastReading = null;
            return;
        }
        if (sameValues(reading, lastReading)) {
            stableCount++;
        } else {
            stableCount = 1;
            lastReading = reading;
        }
        if (stableCount >= STABLE_FRAMES_REQUIRED && !confirmed) {
            confirmed = true;
            listener.onReadingStabilized(reading);
        }
    }

    private static boolean sameValues(BPReading a, BPReading b) {
        if (a == null || b == null) return false;
        return Objects.equals(a.systolic, b.systolic)
                && Objects.equals(a.diastolic, b.diastolic)
                && Objects.equals(a.pulse, b.pulse);
    }
}
