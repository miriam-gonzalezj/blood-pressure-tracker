package com.mtagj.bptracker.ocr;

import org.opencv.core.Mat;

import com.mtagj.bptracker.core.BPReading;

import java.util.List;

/**
 * PRIVATE MODULE -- NOT INCLUDED IN THIS PUBLIC REPOSITORY.
 *
 * This file is a placeholder that keeps the rest of the codebase
 * (OcrHelper, LiveScanAnalyzer, their tests, etc.) compiling against the
 * same public method signatures the real class exposes. The actual
 * implementation -- the 7-segment LCD digit recognition logic (threshold,
 * morphology, contour/shape filtering, and the segment-pattern lookup
 * table) -- is kept private and is intentionally not published here.
 *
 * See the README for what this module does and the engineering decisions
 * behind it (why a neural network wasn't used, the on-screen reference
 * guide, brightness/glare tuning, etc.) without showing the code itself.
 *
 * The core recognition module is private; happy to walk you through it live.
 */
public class SevenSegmentDecoder {

    private SevenSegmentDecoder() {
    }

    /**
     * Placeholder for the real digit-recognition entry point. The actual
     * implementation is private and not included in this repository.
     */
    public static List<String> decode(Mat grayFrame) {
        throw new UnsupportedOperationException(
                "SevenSegmentDecoder is a private module and is not included in this public repository.");
    }

    /**
     * Placeholder for the real row-to-reading conversion. The actual
     * implementation is private and not included in this repository.
     */
    public static BPReading toReading(List<String> rows) {
        throw new UnsupportedOperationException(
                "SevenSegmentDecoder is a private module and is not included in this public repository.");
    }
}
