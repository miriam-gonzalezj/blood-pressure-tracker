# Latido Miri — Blood Pressure Tracker

A native Android app (Java) that reads a blood pressure monitor's display with the phone's camera, logs every measurement, and turns them into a report my doctor can actually use.

## The problem

I needed reliable blood pressure readings so my doctor could assess them with data, not a single reading at the office. Many home monitors don't store past readings, so every value had to be written down right away. Logging each one by hand in a spreadsheet was repetitive, error-prone and slow.

## First attempt

In 2024 I tried to build it and could not get reliable digit recognition from the monitor's display.

## How I built it

In 2026 I rebuilt it with Claude in four days (about 16 hours). I wrote the specification — read the result, store each measurement, analyze trends over a date range — and directed the work iteration by iteration. I considered a neural network early on, concluded it wasn't necessary for this problem, and instead drove the changes that actually worked: a different recognition technique, an on-screen reference guide to frame the shot consistently, and tuning for brightness and glare on the monitor's glossy screen.

The key insight: a blood pressure monitor shows a 7-segment LCD, not printed text, so general-purpose OCR (I tried ML Kit first) can't read it reliably. I built a dedicated decoder for this kind of display instead.

**The core recognition module is private; happy to walk you through it live.** This repository includes a placeholder with the same public methods, so the architecture, data layer, UI, statistics and tests are all here to review.

## Result

The app replaced the manual log and produces a summary, which my doctor reviewed and used for her assessment.

## How it works

- **Capture** — three ways in: a live camera scan (frame-by-frame analysis, nothing is saved to disk), picking an existing photo, or manual entry, for readings taken without the phone at hand. A guide box on screen helps frame the display consistently.
- **Recognition** — a dedicated decoder reads systolic, diastolic and pulse. If a value can't be read with confidence, the field is left blank for the user to fill in — it never guesses, because a wrong number silently saved is worse than an empty one.
- **Storage** — Room/SQLite, entirely local. No account, no cloud, nothing leaves the device unless the user explicitly shares a report.
- **Analysis** — pick a date range and get n, average, median, max, min and population standard deviation for systolic, diastolic and pulse, an AM/PM breakdown, and a trend chart. It deliberately does **not** classify a reading as normal or high: that's a clinical judgment for the doctor, not the app.
- **Export** — a one-page PDF summary, a combined PDF with the full reading history, and CSV export with the same columns as a typical tracking spreadsheet.

## How to run it

1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder as a project ("Open" → select the folder).
3. Let Gradle sync — it needs internet the first time to download Room, OpenCV, CameraX, etc.
4. Connect a phone with USB debugging enabled, or use an emulator with a camera, and hit Run.

No API keys, accounts or configuration are required — the app works fully offline. With the placeholder decoder, the public version runs everything except reading the display; values can be entered manually.

## Testing

The statistics core (`core/BPStatistics.java`) has no Android dependency and can be tested with only a JDK:

    tests/run_tests.sh

It runs against a synthetic sample dataset. One detail the tests check: the app uses population standard deviation (Excel's STDEVP), not sample standard deviation, to match the original spreadsheet analysis.

## Status and next steps

- Pulse recognition was the last piece to be fixed; it is now confirmed working in daily use with live scanning on a physical device.
- Altitude tagging via phone GPS is accurate to roughly ±10–30 meters and needs a recent location fix; the field is always editable and never blocks saving.
- Possible improvement: a more accurate altitude lookup via an elevation API, at the cost of needing an internet connection at save time.
- Next step: measure recognition accuracy formally (percentage of readings correct against manual checks over a larger sample).

---

© 2026 Miriam González Jaimes. All rights reserved. This code is shared for portfolio review only; please contact me before using it.
