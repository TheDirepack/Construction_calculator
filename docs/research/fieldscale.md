# FieldScale: measurement and unit inventory

Research notes for our app-wide Imperial/Metric setting (`docs/plans/unit-system.md`), which named
FieldScale as the example to follow. This records observed behaviour only; it is not a spec to copy.
No code, art, layout or long text is reused.

**Main caveat: no APK was available.** None of the reputable mirrors carries the app (see §0.2), so
everything here comes from the official Play listing, its eight screenshots, the Play data-safety
page and the developer's privacy policy. Nothing was decompiled. Answers about internals are marked
**not found**, or **(inferred)** where the screenshots give strong evidence.

## 0. Scope, sources and confidence

| Item | Detail |
|---|---|
| App | Play id `com.fieldscale.app`, title "FieldScale – Blueprint Measurement & Markup Tool". Developer listed as "TradeTools"; one carpenter working alone (signs replies "Cody"). |
| What it is | A **PDF blueprint viewer** with scale calibration, on-plan measuring, markup, counts and takeoff estimates. It is not a general construction calculator, though it includes an imperial calculator sheet. |
| Version | Play shows **3.5.6**, updated 4 Nov 2026, targetSdk 36, minSdk 24 (Android 7.0). Both SDK numbers were read from the Play page's embedded data. Reviews mention 2.6.1, 3.2.2 and 3.5.0. |
| Traction | 500+ installs, 5.0★ (32 ratings). Free, with no ads per the policy. A "Founder" early-adopter program exists. |
| Tech / framework | **Not found** (APK unavailable). The listing claims "Built for Android… from day one". The backend is Google Firebase (Auth, Firestore, Storage, Crashlytics, Analytics), and AI scale reading uses the Anthropic API behind a Cloudflare proxy (privacy policy). |

### 0.1 Sources, and how much to trust each

1. **Play listing text** (description, "What's new", reviews and developer replies). High trust for the feature list, but it is marketing.
2. **Play screenshots** (8, phone portrait, marketing frames around real captures). High trust for what they show: labels, number formats, Settings → Measurements, the calculator keypad. Every screenshot is in **Imperial**, so metric mode is never shown.
3. **Play data-safety page** and the **privacy policy** at `fieldscale-7ff69.web.app/privacy.html`. High trust for data flows and the camera, Drive and AI features. Neither lists every manifest permission.
4. **No APK, manifest, dex, resources or tests.** So the internal number type, storage format, parser, rounding mode and edge cases cannot be observed. Comparisons with our app in §7 are against what we can see, plus clearly marked inferences.

### 0.2 Method and provenance (nothing executed)

Workspace: `…/7d9659bd-…/scratchpad/apks/fieldscale/` with `download/`, `extract/` (unused) and
`scripts/` (unused). All parsing was one-off `python3 -I -c` over saved HTML, passing file paths as
arguments. curl used a generic desktop-browser User-Agent and no personal data.

| Step | Result |
|---|---|
| `curl https://d.apkpure.com/b/XAPK/com.fieldscale.app?version=latest` | HTTP 404, 191-byte HTML "Page Not Found" (`file`: HTML, not a zip) |
| `…/b/APK/com.fieldscale.app?version=latest` | HTTP 404 HTML |
| `apkpure.com/fieldscale/com.fieldscale.app` | 403 to curl; **410 Gone** via a web fetch |
| APKMirror search (`s=fieldscale`, `s=com.fieldscale.app`) | 403 to curl; "No results found" via a web fetch |
| Uptodown (`fieldscale.en.uptodown.com/android`, search) | 404 / 410 |
| APKCombo (app page, `/download/apk`, `/apk-downloader/`) | Redirected to a Cloudflare Access sign-in page; no content |
| apkfab, apksos, apkgk, appbrain | 403 / 404 / unrelated redirect / 429 |
| Web searches for the package id and the app name | No mirror or third-party page found |
| APKPure's unofficial app API | Not attempted further: the request was refused by this session's permission policy, and I did not try to work around that. |

So **no APK, XAPK or split was downloaded**, and there is no version code, size or SHA-256 to record
for a binary. What was saved instead (untrusted data, read as text or images only):

- `download/play.html`: `play.google.com/store/apps/details?id=com.fieldscale.app&hl=en&gl=US`
- `download/datasafety.html`: `play.google.com/store/apps/datasafety?id=com.fieldscale.app&hl=en`
- `download/web/privacy.html`: `fieldscale-7ff69.web.app/privacy.html` (the site root is a Firebase 404 page)
- `download/shots/s0–s7.jpg`: the 8 listing screenshots from `play-lh.googleusercontent.com`, at 700 px wide (`=w700-rj`). SHA-256:
  - s0 `96be1cf1dfb048d4e4e6ba1e73e34c31294ba05b9fe35d222ad2c59af2d623c1`
  - s1 `43b3eeeff62159c45af96ea0318df7f2d0c3812fa6c79f928f609803ed354b57`
  - s2 `c72dbc96a99ac716faf6508e5c47597c6f7979574de26e327e298640d13edd89`
  - s3 `2ff82fcceca7caa767b9b44adffe3c9ac751eb9387ade961126d9e6680984648`
  - s4 `69287fa5a8b7bce007d118e38f39ff9d8324c18b06f56447e876dbae8f0e4d0d`
  - s5 `594ed6b16868a4389c4acf352814e45a9e40c12709686bbde7275c20166d2899`
  - s6 `ab08bf7bd70aea41c431b0ffcb71e4d3bd857e603e9e04731e9d88f7eb254a4f`
  - s7 `07ae5f6e2954a9609ea60493c93def35a41ce8a9cf4c646f2da64594edb013cc`

What the screenshots show: s0 calibration; s1 the measure toolbar; s2 folders and sharing; s3 a chain or polygon with perimeter and area; s4 markup; s5 the estimate PDF; **s6 Settings → Measurements**; **s7 the calculator**.

Note: `fieldscale.com` is an unrelated company (touch-sensor simulation). The app's own footer
names `fieldscale.app`, which did not resolve in DNS.

---

## 1. Units: systems, length units, precision, switching

| Question | Finding |
|---|---|
| Systems | **Imperial \| Metric**, a two-segment control in **Settings → MEASUREMENTS → "Units"** (subtitle "Imperial or metric measurements") (s6). The listing calls it "Imperial-first… or switch to metric". |
| Imperial length format | Feet and inches with a **hyphen** between them and Unicode fraction glyphs: `13'-8½"`, `5'-10⅛"`, `2'-10⅝"`, `22'-6¾"`. Zero inches are always written: `12'-0"`, `24'-0"`, `40'-6"` (s1, s3, s7). No inches-only or decimal-feet output was seen. The marketing headline "13.6 FT? 12.9 IN?" mocks decimal output. |
| Fraction precision | **"Default Precision"**, subtitle "Fractional inch precision for new sessions". The choices are **1/4", 1/8", 1/16"**, with 1/8" selected in the shot (s6). So precision is **per measuring session (per plan)**, and Settings only sets the default (inferred from "for new sessions"). Nothing finer than 1/16" is offered. |
| Metric length units | **Not found.** No metric screenshot exists. Which of mm, cm or m it uses, and its decimals or rounding, are unknown. |
| Metric rounding | **Not found.** |
| Does switching convert existing values? | **Not found** directly. Measurements on a plan are geometry plus a calibration (§6.1, inferred), so switching very likely only changes how labels are drawn. The headline "Imperial AND Metric, at the flip of a switch" suggests labels update live. |
| Both systems at once | **Not seen.** Each label shows a single value, with no secondary unit line. |
| Default by locale | **Not found.** The shots default to Imperial, and the app targets US trades. |

## 2. Input

- **Calculator** (s7) is a bottom sheet over the plan, titled "Calculator", with **IMP / SCI** tabs (imperial dimension mode vs scientific), a red **Clear** and a close ✕.
  - **Recent-measurement chips** sit above the keys: one-tap values taken from the plan's measurements (`22'-6¾"`, `30'-3½"`, `40'-6"`, `19'-2½"`, `12'-0"`, `2'-10⅝"`). Measured values feed straight into arithmetic.
  - **Fraction row**, scrolling sideways: `⅛ ¼ ⅜ ½ ⅝ ¾ ⅞` and then `1/16 3/16 …`. Eighths come first, then sixteenths written with a slash, because Unicode has no sixteenth glyphs.
  - **Keypad**: 7-8-9, 4-5-6, 1-2-3, then **`ft` 0 `in`**, then ⌫, `C`, `=`. Operators `÷ × − +` sit in a right column.
  - **Tape**: earlier operands are listed (`12'-0"`, `2'-10⅝"`) above a large result (`14'-10⅝"`, highlighted).
- **Units keys in metric**: not found.
- **Bare number** (no `ft` or `in`): not found.
- **Mixed-unit entry** (e.g. `5' 300mm`): not found.
- **On-plan entry**: the user mostly *measures* rather than types (§6.3). Typed sizes appear for "Drop rectangles and circles at an exact size" and for the calibration length.

## 3. Areas, volumes, angles, slope

- **Area**: decimal **square feet** with one decimal and the trade abbreviation **"SF"**: `A: 80.0 SF` (s3). Perimeter is a ft-in length: `P: 39'-1⅛"`.
  - Check: the four side labels `13'-8½" + 5'-10⅛" + 13'-8½" + 5'-10"` sum exactly to `39'-1⅛"`.
  - The two short sides differ by 1/8", so the polygon is hand-placed points, not a constrained rectangle.
  - 13.708 × 5.84 ≈ 80.0 SF agrees.
- **Volume**: not found (it is a 2-D plan tool).
- **Angles**: an "Angle finder — place a three-point angle". The units are not shown; degrees are assumed.
- **Slope / pitch**: not found.

## 4. Other unit settings

None seen. The only MEASUREMENTS settings are Units, Default Precision, Default Snap, Default snap
filter (All / END / MID / LINE / EDGE) and Default snap sensitivity (Low / Med / High) (s6).
Temperature and weight are not found, as expected for a plan viewer. Currency appears only in
estimates (`$`).

## 5. Features and workflows (compact)

- **Plans**: import a PDF (local or Google Drive with `drive.file` scope) or take a photo (Photo Mode, beta). Plans live in nested folders with reorder handles, and multi-page sets can be merged. Swipe between pages. Fully offline.
- **Calibrate**: manual two-point calibration ("Place", "Confirm B", "Redo A", with a 1x/2x/5x loupe) or **Auto Cal AI**, which crops the printed scale note (e.g. `SCALE: 1/2"=1'-0"`) and sends it to an LLM. "Recal" and a "CAL" badge show calibration state, and a "VEC" badge appears on vector PDFs.
- **Measure**: Linear or **Chain** (perimeters), polygon close for **area**, a three-point angle, **F / H / V** constraint (free, horizontal, vertical; inferred), snap to vector geometry (END, MID, LINE, EDGE), and a measure counter badge.
- **Count and takeoff**: labelled counters ("+24" badge), and an **estimate PDF** with item, page, unit $, qty, amount, subtotals, an estimated total, signature and date (s5).
- **Markup**: rectangle, cloud, arrow, highlight, freehand, text, date stamp, approval stamp, saved signatures, image insert, preset shapes at exact size, undo/redo, and a customisable toolbar.
- **Export**: measurements to **PDF, PNG, CSV**, and plans with markups. One review says saving a PDF with notes failed in an older version.
- **Cloud (beta, off by default)**: Google sign-in, backup, sync between phone and tablet, real-time sharing. A folder share sheet has Can edit / Can view, invite by email, and link expiry Never / 24 h / 7 d / 30 d (s2).
- **Permissions and data**: the manifest is **not inspected**. The policy and data-safety page imply **camera** (Photo Mode, on-device) and **internet** (Firebase, AI). Drive access is by OAuth scope, not a storage permission. Data collected: device IDs, crash logs, diagnostics, app interactions (optional), name and email (optional), files and docs (optional). **Analytics are on by default** (opt-out). No ads.
- **Not present**: AR or ARCore, Bluetooth laser meters, tilt or level sensors (not mentioned anywhere).

---

## 6. How measurement is implemented (as far as visible)

The coordinator asked for internals. **With no APK, none of this could be read from code**, so each
point says what is visible and what is inferred.

### 6.1 Internal representation
- **Number type** (double, decimal, rational, integer sixteenths): **not found**.
- **Base unit and storage**: **not found** in code. **(Inferred)** A plan measurement is stored as **points in PDF page coordinates plus a per-page calibration**, not as a length:
  - The policy lists "measurements, markups, and **calibration**" as separate data.
  - "Recal" exists, and snapping works on PDF vector geometry.
  - Lengths are therefore derived on demand (distance × scale), and recalibrating would rescale every measurement on the page.
  - The derived length is almost certainly floating-point: the drawing coordinates are floats and the scale comes from a typed or AI-read ratio.
- **Typed or normalized**: for plan measurements the question doesn't apply (they are geometry). For calculator entries it is **not found**.

### 6.2 Conversion and rounding
- **When it converts**: **not found**. Display-only conversion is the natural fit for geometry plus calibration (inferred).
- **Rounding**: to the session precision (1/4, 1/8 or 1/16"), with the fraction reduced (`½`, `⅛`, `¾`). Ties and direction are **not found**. No rounding indicator was seen (ArchCalc has one: `•`, `+`, `−`). Area is rounded to 0.1 SF.
- **Accumulation**: **not found**. The perimeter in s3 equals the sum of the displayed sides, which fits either summing rounded values or a lucky case. The calculator chips *display* rounded strings, but whether a tap inserts the rounded value or the raw one is unknown. That is the main open risk for an app of this kind (§8 Q3).

### 6.3 Parsing and formatting
- **Parsing**: input comes from keys (`ft`, `in`, fraction keys) rather than free text. Parsing of `5'6`, decimal feet and metric suffixes is **not found**.
- **Formatting**: architectural dimension-string style `F'-I f"`. The hyphen separates feet and inches; there is no space before the fraction glyph (`10⅝`). Zero inches are kept (`12'-0"`), as on drawings. No "≈" marker.

### 6.4 Capture methods and libraries
- **Calibrated PDF geometry**: tap two points (loupe, snap); the length comes from the calibration.
- **Auto calibration** via a cloud LLM on a cropped image.
- **Photo Mode**: the camera photographs a paper drawing, which then works like a PDF (presumably calibrated the same way).
- **Libraries**: Firebase is confirmed by the policy. The PDF renderer and camera stack are **not found**.
- **Not offered**: AR, Bluetooth meters, sensors.

### 6.5 Storage and export
- **Local storage**: on-device by default; the format is **not found**.
- **Cloud**: Firestore and Storage hold the PDF, measurements, markups and calibration.
- **Export**: PDF, PNG and CSV. Units in the CSV (ft-in text or decimals) are **not found**.

### 6.6 Edge cases (negative, very large, mismatched units)
**Not found.** There are no strings or tests to read. On a plan, lengths are distances, so they are never negative. The calculator's handling of `−` results and `ft × ft` is unknown (the IMP tab only shows `+`).

---

## 7. Compared with our plan (`docs/plans/unit-system.md`)

| Point | FieldScale | Ours | Note |
|---|---|---|---|
| Where the switch lives | Settings → MEASUREMENTS → Units: Imperial \| Metric | Settings → Measurements → Units: Imperial \| Metric | **Same**. Keep it. |
| Base representation | Geometry + calibration, floating (inferred) | Exact `Rational` inches; other measures in a fixed base unit | Ours is stricter. FieldScale measures, while we compute from typed numbers. |
| Switching | Display-only (inferred) | Display-only, lossless, storage unchanged | Same idea. |
| Imperial format | `13'-8½"`: hyphen, glyph fractions, `0"` kept | `10' 4 3/16"`: space separated, `n/d` typeset by the UI | Worth offering the hyphen style (see §9). |
| Fraction precision | 1/4, 1/8, 1/16; a per-session default | 1/2 … 1/64; global | Ours is broader. Their per-session idea is not needed for a calculator. |
| Inches-only / Auto style | Not seen | `LengthDisplay` Auto / ft-in / in | Ours is richer. |
| Metric options | Not found | Auto / mm / cm / m; 1 / 0.5 / 0.1 mm | Can't compare. Ours stays. |
| Area | `80.0 SF`, one decimal | sq ft / m² | Match the **"SF" / one-decimal** convention or make sure ours reads as well. |
| Rounding honesty | No marker seen | `FeetInches.exact` exists | We could show an exactness mark; FieldScale doesn't help here. |
| Dual display | None | Not planned (metric extras beside AWG and trade sizes only) | Same. |
| Calculator inputs | `ft` / `in` keys plus a fraction row, recent-measurement chips | Keypad with ' and "; m · cm · mm in metric | **Chips of recent results from other tools** fit our "integrated tools" rule. |
| Capture | PDF, AI, camera | None (calculator) | Out of scope; would add permissions (camera, internet). |
| Analytics | On by default | None | Ours is better for the minimal-permissions rule. |

## 8. Open questions for the user

1. **Can you get the APK from your own install?** You have FieldScale on a phone, and `adb shell pm path com.fieldscale.app` followed by `adb pull` copies your own installed APK. With it I could answer the number type, storage, parsing, rounding and metric questions from code and resources. Screenshots of its **Metric** mode would also help: Settings, a measurement label, and the calculator keypad in metric.
2. **Feet-inch separator**: should we offer FieldScale's drawing style `10'-4 3/16"` (hyphen) beside our current `10' 4 3/16"`? Or switch the default to the hyphen?
3. **Rounded vs exact when values move between tools**: our `ResultValue.exact` passes the exact value. Should a result reused in the Foot-Inch calculator insert the exact value (no accumulated rounding, but the sum may differ from the sum of the shown parts) or the shown rounded value (what you see is what you add)? FieldScale's behaviour is unknown.
4. **Recent-values chips**: do you want a row of recent results from other tools (Conduit Bending, Pull Planner…) above the Foot-Inch keypad, like FieldScale's measurement chips?
5. **Area format**: is `80.0 SF` (one decimal, "SF") the label you want for imperial area, or `80.0 sq ft` / `80 ft²`? And for metric, `m²` with how many decimals?
6. **Precision range**: FieldScale stops at 1/16". Keep our 1/2–1/64, or trim it to 1/4–1/32 to keep the setting simple?
7. **Plan measuring** (calibrate on a PDF, then measure) is FieldScale's core. Is anything like it in scope for us? It would need file access and possibly the camera, which goes against the minimal-permissions rule.

## 9. Worth adopting

1. **Settings shape**: one "Units: Imperial | Metric" segmented control at the top of a MEASUREMENTS group, with the precision options right below it. This is what we planned; FieldScale confirms it reads well.
2. **Drawing-style ft-in**: `12'-0"` (zero inches kept) and an optional hyphen separator. Carpenters read prints written this way.
3. **Fraction glyphs and order**: show eighths first (`⅛ ¼ ⅜ ½ ⅝ ¾ ⅞`), then sixteenths, in the fraction row. Use real glyphs or typeset fractions on labels for compactness.
4. **Reuse measured or computed values as one-tap chips** in the calculator. Carry the exact value and show the rounded one, and decide question 3 first.
5. **Area as decimal sq ft with a short unit label** ("SF") in imperial, never ft-in for area.
6. **Imperial never falls back to decimal feet** for a length result, which is FieldScale's whole pitch ("13.6 FT? 12.9 IN?"). We already do this; keep decimals as secondary only.
7. **Not to adopt**: analytics on by default, cloud LLM calls, and the camera for a calculator. They conflict with our minimal-permissions rule.

## 10. Sources

- Google Play listing: https://play.google.com/store/apps/details?id=com.fieldscale.app (fetched 2026-11-21; v3.5.6, updated 2026-11-04)
- Play data safety: https://play.google.com/store/apps/datasafety?id=com.fieldscale.app
- Privacy policy (July 2026): https://fieldscale-7ff69.web.app/privacy.html
- Play screenshots (8), `play-lh.googleusercontent.com`; hashes in §0.2
- Mirror checks (all without a usable file): APKPure (`d.apkpure.com` 404, app page 410), APKMirror (no results), Uptodown (404), APKCombo (Cloudflare Access login)
- Our plan: `docs/plans/unit-system.md`; our formatter: `core/measure/.../Format.kt`, `UnitSystem.kt`, `input/CalcInput.kt`
