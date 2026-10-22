# Latido Miri — Blood Pressure Tracker

A native Android app (Java) that reads a blood pressure monitor's display with the phone's camera, logs every measurement, and turns them into a report my doctor can actually use.

## The problem

I needed reliable blood pressure readings so my doctor could assess them with data, not a single reading at the office. Logging every measurement by hand in a spreadsheet was repetitive, error-prone and slow.

## First attempt

In 2024 I tried to build it and could not get reliable digit recognition from the monitor's display.

## How I built it

In 2026 I rebuilt it with Claude in four days (about 16 hours). I wrote the specification — read the result, store each measurement, analyze trends over a date range — and worked through it iteration by iteration: I considered a neural-network-based approach early on, then concluded it wasn't necessary for this problem, and instead directed the iterations that actually worked: a different recognition technique, an on-screen reference guide to frame the shot consistently, and tuning for brightness and glare on the monitor's glossy screen.

A blood pressure monitor's display is a 7-segment LCD, not printed text, so general-purpose text OCR (I tried ML Kit first) cannot read it at all — it's trained on font glyph shapes, and 7-segment digits don't look like any font character. The recognition module (`SevenSegmentDecoder`) instead does what dedicated tools like `ssocr` do: it finds each digit's bounding box in the image, checks directly which of its 7 physical segments are lit, and looks up the resulting on/off pattern against a table of digits.

**The core recognition module is private; happy to walk you through it live.** This repository includes a placeholder in its place (same public methods, so the rest of the codebase — `OcrHelper`, `LiveScanAnalyzer`, their tests — still shows how it's wired in) so the architecture, data layer, UI, statistics, and tests are all here to review, without publishing the actual digit-recognition logic.

## Result

The app replaced the manual log and produces a summary, which my doctor reviewed and used for her assessment.

## How it works

- **Capture** — three ways in: a live camera scan (CameraX preview + frame-by-frame analysis, nothing is ever saved to disk), picking an existing photo, or manual entry. A guide box on screen helps frame the monitor's display consistently.
- **Recognition** — `SevenSegmentDecoder` turns a grayscale image into rows of digits (see above; the real implementation is private — this repo ships a placeholder in its place). It tries several progressively tighter crops per frame/photo, because a loose crop can fuse the display's own bezel with the digits into one unreadable shape. If a value genuinely can't be read with confidence, the field is left blank for the user to fill in by hand — it never guesses, because a wrong number silently saved is worse than an empty one.
- **Storage** — Room/SQLite, entirely local. No account, no cloud, nothing leaves the device unless the user explicitly shares a report.
- **Analysis** — pick a date range and get n, average, median, max, min and population standard deviation for systolic, diastolic and pulse, an AM/PM breakdown, and a trend chart. Deliberately does **not** classify a reading as normal/elevated/high: that's a clinical judgment I wanted left to the doctor, not the app.
- **Export** — a one-page PDF summary, a combined PDF (summary + full reading history for the same date range), and CSV export with the same columns as a typical tracking spreadsheet.

## How to run it

1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder (`bp-tracker/`) as a project ("Open" → select the folder).
3. Let Gradle sync — it needs internet the first time to download Room, OpenCV, CameraX, etc. OpenCV is the heaviest dependency and can take a bit longer.
4. Connect a phone with USB debugging enabled, or use an emulator with a camera, and hit Run.

No API keys, accounts or configuration are required — the app works fully offline.

**About the recognition module:** `SevenSegmentDecoder.java` in this repository is a placeholder — it keeps the same public methods the real class exposes (so the rest of the code compiles and reads normally) but does not contain the actual digit-recognition logic, which is kept private. That means this public repository will **not** read a real monitor display out of the box; everything else (capture flow, storage, trends/statistics, PDF/CSV export, tests) is complete and reviewable as-is. Happy to walk through the real recognition module live.

**What I verified myself, and what I couldn't:**
- The pure-Java core logic (`core/BPStatistics.java`) has no Android dependency, so I compiled and ran it directly here with `tests/run_tests.sh` (needs only a JDK, no Android SDK) against a synthetic sample dataset — all checks pass, including confirming the app uses *population* standard deviation (Excel's STDEVP), not sample stdev, which was a real, easy mistake to make.
- Every `.java` and `.xml` file in the project was checked for valid syntax, and every Android view ID referenced from Java code was cross-checked against its layout file.
- I could **not** compile or run the full Android app (the camera/OCR/UI pipeline) in the environment I prepared this repository in: it has no Android SDK installed, and the sandbox's network access blocks Google's Maven repository, which the Android Gradle Plugin needs to resolve. This isn't a code issue — it's a limitation of that specific environment — but it means the build/run steps above are not verified by me in this session; they reflect the standard Android Studio workflow and this project's own development history, where the app (with the real recognition module in place) did compile and run on a real device.

## Screenshots

_Placeholders — see `docs/screenshots/` for which images to add:_

- `docs/screenshots/scan.png` — the live camera scan screen, mid-scan (guide box visible).
- `docs/screenshots/review.png` — the review/edit screen after a reading is detected.
- `docs/screenshots/analytics.png` — the trends screen with the stats block and chart.

## Limitations and next steps

- Recognition accuracy has not been measured as a formal number (e.g. % of fields read correctly) against manual readings over a larger sample — that would be the natural next step to quantify how well this actually works rather than relying on individual verified cases.
- Live-scan recognition of the pulse value specifically had a fix implemented late in development but not yet confirmed working under real scanning conditions (motion, varying distance/lighting) on a physical device — it was verified against a static reference photo and a Python port of the algorithm, not a live camera.
- Altitude tagging via phone GPS is accurate only to roughly ±10-30 meters and needs a recent location fix; the field is always editable and never blocks saving.
- No automated UI/instrumentation tests — verification so far has been a pure-Java test harness for the statistics, plus manual testing on a real device during development.
- Possible future improvement: a more accurate altitude lookup via an elevation API instead of raw GPS altitude, at the cost of needing an internet connection at save time.

---

© 2026 Miriam González Jaimes. All rights reserved. This code is shared for portfolio review only; please contact me before using it.
