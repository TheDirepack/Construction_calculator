# FieldScale: measurement, units and PDF plan measuring

Research notes for two pieces of our work:

- our app-wide Imperial/Metric setting (`docs/plans/unit-system.md`), which named FieldScale as the example to follow;
- our next big feature, **PDF plan measuring**.

This records observed behaviour, described in my own words; it is not a spec to copy. No code, art,
layout or long text is reused.

## 0. Scope, sources and confidence

| Item | Detail |
|---|---|
| App | Play id `com.fieldscale.app`, title "FieldScale – Blueprint Measurement & Markup Tool". Developer "TradeTools", one carpenter working alone. The internal Flutter project name is `imperial_measure`. |
| What it is | A **PDF blueprint viewer**. You calibrate a page's scale, then measure, mark up, count and price a takeoff. It also has a small imperial calculator. It is not a general construction calculator. |
| Version inspected | **3.5.6 (versionCode 52)**. minSdk 24, targetSdk 36, compileSdk 36. Splits: base, arm64-v8a, xxhdpi, en, es. |
| Traction | 500+ installs, 5.0★ (32 ratings). Free, no ads, with donation links (PayPal, Venmo, Cash App) and a "Founder" early-adopter program. |
| Framework | **Flutter** (Dart AOT in `libapp.so`), plus a small Kotlin `MainActivity` that hosts six platform channels: `plan_render`, `annotate`, `merge`, `save`, `intent` and `integrity` (all prefixed `com.fieldscale/`). |
| Protection | Play automatic integrity protection is wrapped around the app (`com.pairip` application and license check). The Dart code is **obfuscated**: class and method names are scrambled, so the Dart logic can't be read, only its strings. |

### 0.1 Sources, and how much to trust each

1. **Dart string pool** (`libapp.so`, read as ASCII and as UTF-16). The app ships long in-app help text, settings labels, JSON keys, analytics event names and the AI prompt. **High trust for features and wording.** Because names are obfuscated, the numeric logic (number type, rounding mode, area formula) can't be read. Statements about it are flagged **(inferred)**.
2. **Kotlin side, decompiled with jadx** (only `MainActivity` and the classes it calls). **High trust.** It shows how PDF pages are rendered and how markups, annotations and the data sidecar are written into exported PDFs.
3. **Manifest** (`aapt2 dump badging` and `xmltree`). High trust for permissions and SDKs.
4. **`NOTICES.Z`** (the Flutter licence bundle, gzip). High trust for the list of Dart packages.
5. **Play listing, 8 screenshots, data-safety page, privacy policy.** High trust for what they show. They give the visual formats (`13'-8½"`, `A: 80.0 SF`) and Settings → Measurements.
6. **Not available**: runtime behaviour. Nothing was installed or run. Edge cases are only what the help strings say.

### 0.2 Method and provenance (nothing executed)

**First attempt, from mirrors (no file).**
- APKPure: the download link returns 404 for both XAPK and APK forms, and the app page returns 410.
- APKMirror finds nothing, Uptodown returns 404, and APKCombo sits behind a Cloudflare Access login.
- Requests to APKPure's unofficial app API were refused by this session's permission policy.
- The listing, data-safety page, privacy policy and screenshots were saved instead.

**Binary used: the user's own export.**
- The user exported the app from their own phone with *APK Explorer & Editor*. AEE re-signs the export ("aee-signed"), so the signature is not the developer's; the contents are the installed build.
- Attached file: `…-xapk.xapk`, 21,417,368 bytes, SHA-256 `03c1a0ad723d433898ebeffd9ea8572f3bc2e03786c44398c918c9a8edda0dc2`.
- Copied to `scratchpad/apks/fieldscale/download/fieldscale-aee.xapk` (same hash).

Split SHA-256s:

| File | SHA-256 |
|---|---|
| `base.apk` | `9129bda45a1d043169ab6d738284ff8b8b07f79c977aff3becc4bc0138fb46a5` |
| `split_config.arm64_v8a.apk` | `a0be310a5a5b76226249799e4f5fd52cbd6688ba7c8a901d0e92a5b443d2f892` |
| `split_config.xxhdpi.apk` | `3702c7d58a4574bae39c8903b646fb8af9cc5dee77eb46f3294c475aca856a02` |
| `split_config.en.apk` | `8987514b7a483c6f36650c2c3ab7735a4232f6aafd716340e97c963414a8d37d` |
| `split_config.es.apk` | `c879acf62ad8a066385edfececd6c27fd57ab4e5ead0ffe6f0e22a4c0ed8d875` |

**Steps** (all static; the app and its code were never installed, loaded or run):

1. `unzip` the XAPK into `extract/xapk/`, then the base and arm64 splits into `extract/base/` and `extract/arm64/`.
2. Run `aapt2 dump badging` and `aapt2 dump xmltree --file AndroidManifest.xml` on `base.apk`.
3. Run `strings -n 3` on `libapp.so`. Then run `python3 -I scripts/strings_wide.py`, a script I wrote and kept in a separate `scripts/` folder, to pull UTF-16 and Latin-1 strings. All paths were passed as arguments.
4. Gunzip `flutter_assets/NOTICES.Z` with `python3 -I` and list the package names.
5. Decompile `classes.dex` and `classes2.dex` with **jadx 1.5.7**. It came from the official GitHub release; its SHA-256 `1755e424…cbf3` matches the digest GitHub publishes. jadx ran with `--no-res` into `extract/jadx1` and `extract/jadx2`, and I read only the app's own classes.
6. Read the 8 Play screenshots as images. Their hashes are in the scratchpad listing; the main ones used are s6 (Settings) and s7 (Calculator).

**Terms note.** FieldScale's in-app Terms of Use forbid reverse engineering. This was static reading of
a copy the user exported from their own install, for design research only. Nothing here reproduces
its code, and the doc should stay internal.

---

## 1. Units

| Question | Finding |
|---|---|
| Systems | **Imperial \| Metric**, one global switch in Settings → Measurements → Units. Help text says the switch "applies immediately across open plans". Stored setting key: `unit_system`. |
| Imperial format | Drawing style, always: `13'-8½"`, `12'-0"` (zero inches kept), `7'-3½"`. Fractions use glyphs where Unicode has them (½ ¼ ¾ ⅛ ⅜ ⅝ ⅞) and `1/16`-style text otherwise. There is **no setting** for the space style, inches-only, or decimal feet. |
| Imperial precision | **1/4", 1/8", 1/16"** only. "Default Precision" sets the *starting* precision of each new plan session (key `default_precision`); a `resolution_changed` event shows it can also be changed inside a session. |
| Metric units | Strings ` mm`, ` cm`, ` m`, `0 mm`, `(m)`, "Square meter" and "Linear meter" exist. **Which unit a given length uses was not determinable.** |
| Metric precision | **10 mm, 5 mm, 1 mm**, labelled "Millimetre precision for new sessions". So metric rounds to whole, five or ten millimetres. Nothing finer than 1 mm exists. |
| Does switching convert stored values? | **No, it's display-only (inferred, strong).** Lengths are derived from page geometry and the page scale (§6.1), so the switch only changes formatting, and it takes effect live. |
| Both systems at once | Not offered. |
| Default by locale | No locale logic was found. Imperial appears to be the default. |

## 2. Input

- **Calculator** (s7, plus the help text). Open it from Calc in a plan's bottom bar, or standalone from the home screen.
  - **Mode**: tabs **IMP / SCI**. A `MET` string sits next to `Metric` and `IMP`, so metric likely shows a MET tab (inferred).
  - **Entry**: type digits, tap **ft** to commit feet, type inches, tap **in**. Example from the help: 7 → ft → 3 → ½ gives `7'-3½"`. Tapping **in** without **ft** enters inches only.
  - **Fraction row**: scrolls sideways and holds the eighths plus the *odd* sixteenths (1/16, 3/16, 5/16…). Even sixteenths are left out because they reduce to eighths. Tapping one appends it.
  - **Measurement chips**: opened from a plan, a strip of that plan's saved measurements sits above the fraction row, and a tap loads one as the next operand. **Whether a chip carries the exact or the rounded value can't be read from the obfuscated Dart.**
  - **"Smart unit math"** keeps a type on every value and only allows type-correct operations:
    - length × length gives area (`× 2' = 40ft²`);
    - length × number scales the length;
    - length ÷ length gives a plain ratio ("how many 2 ft fit in 20 ft");
    - length ÷ number gives a length;
    - area ÷ length gives a length.
    - Anything else, such as adding feet to a plain number, shows a red "Type error: cannot …" (add, multiply or divide these unit types).
- **Bare number** in a length context: inches (from "tap in without ft for inches only"). Metric entry keys are **not found**.
- **On a plan** you mostly *measure* rather than type. Typed values appear in these places:
  - the calibration distance ("Real-world distance between calibration points"; "Enter a valid distance");
  - exact sizes for rectangles and circles (a circle by diameter, radius or circumference);
  - polyline "Set length" for one segment;
  - rotation angle.

## 3. Areas, volumes, angles

- **Area**: decimal square feet with **"SF"**, e.g. `A: 80.0 SF` (one decimal in the screenshot). Perimeter is a length (`P: 39'-1⅛"`). Metric uses square meters. Costs are priced per **LF / SF** (or linear / square meter) or per each.
- **Polygon label**: tapping a polygon's centre cycles through three views: segments + perimeter/area; segments + interior angles; angles only.
- **Circle**: fitted through **3 points** on the circumference; shows radius, diameter, circumference and area.
- **Angle tool**: vertex, then one point on each arm; shows the angle **and its supplement**, in degrees. Rotating a shape snaps at quarter turns and, on vector plans, to the angle of a nearby wall.
- **Volume**: none. **Slope / pitch**: none.

## 4. Other unit settings

None. Measurements settings are:

- Units;
- Default Precision;
- Default Snap, snap filter and snap sensitivity;
- **Placement stability** (Off / Low / High jitter damping);
- live distance readout;
- plan navigation (two-finger vs Pan/Place);
- swipe between pages;
- render quality (Standard / High detail).

Currency format is a separate takeoff setting.

## 5. Features and workflows (compact)

- **Library**: import PDF, JPG, PNG or TIFF from the device, Google Drive (`drive.file` scope) or **Microsoft SharePoint/OneDrive** (MSAL). Nested folders, multi-select. **Merge** plans (the merge is rasterised) and **split** a multi-page PDF. Opening a PDF from another app goes through a VIEW `application/pdf` intent.
- **Photo / Scan mode** (beta): uses the **ML Kit Document Scanner** (edge detection and flattening). It falls back to the CameraX camera, plus "Correcting perspective…". Help warns that photo measurements are approximate: calibrate on a known dimension and measure along the direction you calibrated.
- **Calibrate**: Manual Cal, Auto Cal (AI) or Multi-Scale (§6.2).
- **Measure**: Linear, **Chain** (Done keeps it open; Close makes it a polygon), circle (3-point), angle. Also F/H/V direction lock, snapping (§6.4) and the magnifier.
- **Markup**: rectangle, cloud, arrow, highlight, freehand, text, stamps (APPROVED, REVISED, HOLD, NTS, RFI, FIELD VERIFY), signature, date, image, counter, note pins, polyline with exact segment lengths, and shapes at an exact size with saved presets. **Layers** have visibility; "if you can see it, it's exported and counted".
- **Takeoff**: counters (classes span the whole plan, points are kept per page, grouped by label prefix). Measured lengths and areas, and sized shapes, can be named items. Cost presets are per each / LF / SF, with subtotals and a grand total. A **takeoff PDF** can add a signature and date line.
- **Export**: PDF (markups on a vector layer, plus standard PDF annotations, plus the embedded data sidecar), PNG, **CSV** (optionally with "raw measurements"), a measurement summary page, and a takeoff-only PDF. "Remove FieldScale branding" is an export option.
- **Cloud** (beta, off by default): Firebase Auth/Firestore/Storage/Functions. Real-time shared editing through an operation log, roles (Owner/Admin/Editor/Viewer), invite links with expiry, a backup archive.
- **Notes**: per-plan to-dos with priority, due date, replies and optional pins; job notes per folder.

---

## 6. How measurement is implemented

### 6.1 Internal representation

- **Number type: Dart `double` throughout (inferred, strong).** There is no rational or decimal package in `NOTICES` (only `fixnum`, a protobuf helper). The calculator's unit math works on types (length, area, number), not on exact fractions. Points, the scale and lengths are therefore all floating-point.
- **Measurements are geometry, not lengths.**
  - Each mark stores its points in the coordinate space of the page as the app renders it: page keys `pageWidthPx` / `pageHeightPx`, `planLogicalSize`; points as `x1,y1,x2,y2` or flat `points` lists.
  - The real length is computed at display time from the points and the page scale.
  - The help text confirms it: "Quantities are measured off the drawing and update themselves if you recalibrate", and "Existing measurements will be rescaled automatically when you set the new calibration".
  - The export code converts these pixel coordinates to PDF points by multiplying by (MediaBox width ÷ `pageWidthPx`) and flipping the y-axis.
- **Exact-size items are the exception.** A rectangle or circle given an exact size, or a polyline segment given a typed length, **keeps its real-world size**: "recalibrate the page and it resizes to stay true". In the takeoff, "the quantity is the size you typed, so it stays right whatever the page scale". So there are two kinds of mark:
  - *measured* (drawing units; follows the scale);
  - *specified* (real units; drawn from the scale).
- **Calibration storage.** Stored per page:
  - the two calibration points (`calAx`, `calAy`, `calBx`, `calBy`);
  - the typed real distance (`knownDist` / `inputDist`);
  - the derived `scale`;
  - a `scaleLabel` and `scaleSource` (manual, auto or copied);
  - optional `scaleZones` with `isMultiScale`.

  The scale is probably real units per drawing unit, as a double (inferred). Each page keeps its own calibration, measurements and markups.
- **Units of the stored real distance**: not determinable. Imperial-first strings and the `feet`, `inches`, `fraction` keys suggest it is held as an imperial value (inferred).

### 6.2 Calibration methods

1. **Manual Cal**: place point A and point B on a known dimension (magnifier, snapping, F/H/V lock), then type the real distance. The help advises calibrating on the **longest** dimension, because "longer calibration lines cut placement error across the whole drawing". After calibrating, the app warns: verify with a known dimension.
2. **Auto Cal (AI)**:
   - The user draws a box around the title-block scale note. The crop is sent as a PNG through a Cloudflare Worker proxy to Anthropic's API (model string `claude-sonnet-4-6`).
   - The prompt asks for JSON: either a ratio `1:N` or an architectural pair (paper inches = real feet).
   - The help warns, rightly, that **title-block scales are often placeholders** or don't match the PDF's true scale (a sheet printed to fit, or a "1"=1'-0"" placeholder). The user must verify, and use Manual Cal when the result is off.
3. **Multi-Scale (zones)**: for sheets that mix scales (plan plus details). Calibrate the first scale, box the regions it covers, then "Add Scale" for each extra scale. **The app picks the scale by where the points land.** Exact-size shapes and polylines must sit inside a zone; outside a zone the page scale is used. "Set a new scale; its areas stay put" recalibrates a zone without moving its box.
4. **Copy a scale**: apply a calibrated page's scale to this page, or apply this page's scale to **all uncalibrated pages** without touching pages that already have their own.

- **Presets**: the strings include the common architectural scales (1/16", 1/8", 1/4", 1/2", 3/4", 1" = 1'). I found **no preset-picker UI** (no list of engineering or metric ratios), so these likely only serve as labels for AI results (inferred).
- **Ratio scales** (1:N) come only from Auto Cal.
- **Non-uniform scale**: a single scale per page or zone; X and Y are never calibrated separately. Photos rely on the scanner's flattening.

### 6.3 Rounding and formatting

- **Display rounding**: to the session precision (1/4, 1/8 or 1/16"; 10, 5 or 1 mm). Fractions are reduced (`½`, `⅛`). Hand-resizing a shape **snaps the size to the precision setting**, so precision also quantises editing, not only display.
- **Rounding mode** (half-up, nearest-even): **not determinable** (obfuscated). There is no rounding or "≈" marker on labels.
- **Accumulation**:
  - Perimeter and area are computed from the geometry, so they don't accumulate the rounding of the displayed sides. In s3 the four displayed sides happen to sum exactly to the displayed perimeter.
  - In the calculator, chained operations are doubles, so binary floating-point error can creep in. It is hidden by rounding to 1/16".
  - Whether chips feed the rounded or the raw value is not determinable.
- **Area**: decimal with one place (`80.0 SF`).
- **CSV**: there is an option to **include raw measurements**, read as unrounded decimals (inferred from "Raw Measurements" and `csv_include_raw`). The default CSV likely carries the formatted values.

### 6.4 Placement precision on a phone

These are the parts that make finger measuring usable, and what we will need too:

- **Magnifier (loupe)** with a crosshair offset from the finger, at 1x/2x/5x. Help: "pinch-zoom to the area first, then use 5x". In the default mode the loupe appears on first touch and stays after lifting, so you can confirm. Points are committed with explicit **Confirm A / Confirm B** (or Set point), never on finger-up.
- **Placement stability** (Off/Low/High): damps small finger jitter while aiming.
- **Snapping**:
  - **SNAP** uses vector data from CAD-exported PDFs (the app's own Dart PDF vector parser; the "VEC" badge in the top bar likely shows that vectors were found, inferred).
  - **EDGE** uses raster edge detection for scans.
  - Targets are END, MID, LINE and EDGE, with a teal label and a light haptic tick when it catches, and a search radius of Low/Med/High.
- **Direction lock**: F/H/V (free, horizontal, vertical) for the second point.
- **Live distance readout** above the loupe while dragging.
- **Gestures**: one finger aims or draws, two fingers pan and zoom at any time, even mid-measurement. Swiping to the next page only works when zoomed out to fit and no tool is armed, so a swipe never turns the page by accident.

### 6.5 Rendering

- Pages are rendered by **Android's platform `PdfRenderer`**, driven from Kotlin over the `plan_render` channel:
  - `pageSize` returns the page size in points;
  - `renderPage` renders the whole page to a PNG at a requested size with a minimum long side;
  - `renderTile` renders a sub-rectangle at 2× for sharp zoomed views;
  - `renderThumbnail` makes previews.
- On `OutOfMemoryError` it **halves the bitmap and retries** down to a floor.
- `deviceProfile` reports available RAM, low-RAM status and the GPU's max texture size (default 4096). It drives the "Standard" vs "High detail" render modes; "High detail" re-renders the visible area sharply after movement stops.
- Pan and zoom use `photo_view`.
- **PdfBox-Android** is used for writing and merging (not for viewing). PDFs are opened with a mixed memory/temp-file budget of about 24 MB.

### 6.6 Storage and export

- **Library**: a local `manifest.json` (with a "moved aside" recovery path if it's unreadable), PDFs under app storage, and a cloud transfer queue.
- **Plan data travels inside the PDF**: each plan's data (calibration, measurements, markups, notes) is a JSON **embedded file** named `fieldscale.json`, the "sidecar". It is written with `embedSidecar` and read back with `readSidecar`. It has a `schemaVersion`, and newer data is refused for editing ("uses features from a newer version").
  - Merging PDFs keeps calibration, measurements and markups for PDF pages. Images are embedded without marks.
- **Markups on export**:
  - drawn into the page content on a named optional-content layer, **"FieldScale Markup"**: vector rectangles, ellipses and paths, plus a transparent PNG overlay for the rest;
  - the content streams are tagged so a later export can **strip the old layer first**, which avoids doubled markup (the log warns when another tool has flattened it);
  - basic marks are also written as **standard PDF annotations** (Square, Line with arrow, Highlight, FreeText), and images as file attachments.
- **Units in exports**: labels as displayed (ft-in or metric, SF or m²). The CSV can add raw values. The takeoff uses LF, SF and each.

### 6.7 Edge cases visible in strings

- Type errors in calculator arithmetic.
- "Enter a valid distance" for calibration input.
- "Draw the polyline inside a scale zone so its lengths are real".
- "The closing segment is set by the others" (a closed polyline can't set its last segment).
- "PDF needs more memory than the device can give it" (out-of-memory on export).
- A zero or negative length is never mentioned. Lengths are distances, so they're non-negative.
- Very large values: nothing found.

---

## 7. Permissions and SDKs

| Permission | Needed for |
|---|---|
| `CAMERA` | Photo Mode via CameraX; the ML Kit scanner doesn't itself need it |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Firebase, Auto Cal AI, Drive/SharePoint import, cloud |
| `READ_EXTERNAL_STORAGE` (no max SDK), `WRITE_EXTERNAL_STORAGE` (≤ API 28) | Legacy file import, and export to Downloads on old Android |
| `VIBRATE` | Haptic ticks (snap, confirm) |
| `WAKE_LOCK`, `c2dm.RECEIVE`, `READ_GSERVICES`, install-referrer | Firebase / Play services SDKs |
| `NFC`, `usb.host` (optional features) | Library-merged, most likely from FIDO/credential sign-in; no app feature uses them |
| `CHECK_LICENSE` | Play integrity and licence wrapper |

**SDKs**:

- **Firebase**: Analytics (**on by default**, opt-out), Crashlytics, Auth, Firestore, Storage, Functions, App Check (Play Integrity and SafetyNet).
- **Sign-in and import**: Google Sign-In / Credential Manager, `googleapis` (Drive), **MSAL** (Microsoft).
- **Camera and scanning**: ML Kit Document Scanner, CameraX.
- **PDF**: `pdfx`, the Dart `pdf` package (inferred from its classes, used for takeoff and summary pages), PdfBox-Android.
- **Other**: `flutter_inappwebview` (a Drive picker page on the developer's site), `photo_view`, `share_plus`, `file_picker`, `image_picker`, `in_app_review`, `permission_handler`, `vibration`, Anthropic API through a Cloudflare Worker.

---

## 8. Compared with our decisions

| Point | FieldScale | Ours (decided or planned) |
|---|---|---|
| Switch | Settings → Measurements → Units, global and live | Same |
| Storage | Geometry + per-page scale as doubles; specified sizes in real units | Exact `Rational` inches for typed values; base units everywhere |
| Switching | Display-only | Display-only, lossless |
| ft-in style | Drawing style only (`7'-3½"`) | Setting: `10' 4 3/16"` (default) or drawing style `10'-4 3/16"` |
| Imperial precision | 1/4–1/16, per session with a default | 1/2–1/64, global |
| Metric display | mm/cm/m strings; rule unknown | Auto: mm under 1 m, m above; or fixed mm/cm/m |
| Metric precision | 10 / 5 / 1 mm | 1 / 0.5 / 0.1 mm |
| Reused values | Plan chips → calculator; exact vs rounded not determinable | **Exact value carried**; rounding display-only |
| Rounding marker | None | `FeetInches.exact` is available to show one |
| Calculator types | length, area, number with type errors | Same idea (Dimension-aware expression engine) |
| Area display | `80.0 SF` | sq ft / m² through the formatter |

## 9. For our PDF plan measuring feature

### 9.1 Design points (recommended)

1. **Store geometry in PDF user space (points, 1/72 in), not render pixels.**
   - Keep each page's points independent of render resolution, after applying `/Rotate` and the CropBox.
   - FieldScale stores render-pixel coordinates plus the pixel size of the page and must rescale on export. Using PDF points removes that whole class of bugs.
2. **Use two kinds of mark, explicitly.**
   - *Measured* marks: geometry; their length = geometry × scale, recomputed on every recalibration.
   - *Specified* marks: typed real size as an exact `Rational`; drawn using the scale and kept true on recalibration.
3. **Keep the scale exact where it can be.**
   - A preset or typed scale is an exact ratio. 1/4" = 1'-0" means 48 real inches per paper inch, so 48/72 real inches per point. Store it as a `Rational` with its source (preset, two-point or copied).
   - A two-point calibration divides an exact typed length by a floating-point distance. Keep that scale as a double, but remember the typed length exactly.
   - A measured length is then floating-point by nature. When it enters our exact pipeline (the user's "carry the exact value" decision), convert it once to a `Rational` at a fine grid such as 1/1024" or 0.01 mm. It should then show as *measured*, not exact (see Q2).
4. **Per-page scale with copy-to-pages and zones.**
   - Give every page its own scale.
   - Offer "apply to all uncalibrated pages".
   - Add zones later: rectangles, each with its own scale, chosen by where the points fall. A measurement crossing zones should be refused or warned about.
5. **Offer scale presets offline, plus two-point calibration, and always a verify step.**
   - Presets: architectural (1/16"…3" = 1'-0"), engineering (1" = 10'…100'), metric ratios (1:1…1:500).
   - After calibrating, prompt the user to measure a second known dimension and show the percentage difference. This catches PDFs printed to fit, which FieldScale only warns about.
   - Skip AI auto-calibration: it needs internet and an API, against our minimal-permissions rule.
6. **Make placement precise on a phone.**
   - Loupe with an offset crosshair, explicit Confirm per point, 1x/2x/5x, jitter damping.
   - H/V direction lock and a live readout.
   - Advise calibrating on the longest dimension, and show the calibration line's length in drawing units so a short one can be flagged.
7. **Snapping** in two tiers.
   - (a) Vector snapping (endpoint, midpoint, on-line) needs the page's path geometry. Platform `PdfRenderer` gives pixels only, so this needs a parser such as PdfBox-Android (Apache-2.0, as FieldScale uses) or our own content-stream reader.
   - (b) Raster edge snapping for scans can come later.
   - Show what snapped (END/MID/LINE) and give a haptic tick.
8. **Geometry math.**
   - Polyline length is the sum of segments.
   - Closed area uses the shoelace formula on points × scale² (absolute value). Perimeter includes the closing segment.
   - Circle by 3-point fit (circumcircle).
   - Angle at a vertex, plus its supplement.
   - Compute in doubles from points; convert once for display or export.
9. **Rendering.**
   - Render the whole page at a device-dependent long side, and halve on `OutOfMemoryError`.
   - Render tiles at higher density for the visible area after the view settles, capped by the GPU's max texture size.
   - Cache renders on disk.
10. **Storage.**
    - Keep plan data as our own versioned JSON in app storage, in base units: geometry in points, specified sizes and scale as exact values. Add a `schemaVersion`, and refuse to edit data newer than the app understands.
    - Embedding the same JSON in exported PDFs (as FieldScale does) is a nice round-trip, but optional.
11. **Export.**
    - PDF with marks drawn on a named optional-content layer, so they can be toggled or stripped, and so re-exporting doesn't double them.
    - CSV with the formatted value *and* the base-unit value columns, always, rather than an option.
    - A summary page of measurements.
12. **Connect to the calculator and other tools** through our existing exact `ResultValue`. Each saved measurement becomes a chip in the Foot-Inch calculator, as FieldScale does, and can feed Conduit Fill / Pull Planner run lengths.
13. **Precision on plans.** Plan reading needs coarser rounding than shop work (FieldScale tops out at 1/16" and 1 mm). Use the global setting, or give a plan its own default (see Q1).

### 9.2 Pitfalls seen or implied

- **Placeholder title-block scales** and sheets printed to fit. AI or preset scales are wrong more often than you'd think, so always verify.
- **Recalibration semantics.** Measured marks must rescale while specified sizes must not. Mixing the two silently produces wrong takeoffs.
- **Render-space coordinates** tie data to render resolution. Avoid them (point 1).
- **Page rotation and CropBox offsets** break coordinate mapping on export if they're ignored.
- **Memory.** Large architectural sheets out-of-memory easily on mid-range phones, both for rendering and in PDF libraries during export (FieldScale has dedicated out-of-memory errors for both).
- **Photos aren't uniformly scaled** even after flattening. Treat photo measuring as approximate, along the calibrated direction only.
- **Swipe-to-page vs drawing gestures.** Only allow page swipes when zoomed out to fit and no tool is armed.
- **Doubled markup** when re-exporting a PDF that already carries your layer. Tag and strip it.
- **Floating-point accumulation** in chained arithmetic. Our exact `Rational` calculator avoids it once values enter the exact domain.

## 10. Worth adopting (units, whole app)

1. One global Units switch that takes effect live, display-only. This is already our plan.
2. Typed values everywhere (length, area, number) with clear type errors, plus the "length ÷ length = ratio" and "area ÷ length = length" rules.
3. A fraction row of eighths plus odd sixteenths, since even sixteenths reduce to eighths.
4. Chips of saved or measured values above the calculator keypad. They carry the exact value, per the user's decision.
5. Area as decimal SF / m² with a short label.
6. Not to adopt: analytics on by default, AI calibration, broad storage permission, and the precision ceiling of 1/16" and 1 mm for our shop-oriented tools.

## 11. Open questions for the user

1. **Plan precision**: should a plan use the global precision, or have its own default as in FieldScale ("for new sessions")? Plan reading rarely needs finer than 1/16" or 1 mm.
2. **Measured vs exact**: a length measured off a PDF is inherently approximate. When it is reused (calculator chips, other tools), should it carry the full measured value, converted once to a fine grid, and be marked "measured" (e.g. a `≈`)? Or should it be snapped to the display precision first?
3. **Scale sources for v1**: presets (architectural, engineering, metric ratios) plus two-point, with a verify step, and no AI auto-cal (which needs internet). Agreed?
4. **Multi-scale zones and copy-scale-to-pages**: in v1, or later?
5. **Vector snapping** needs a PDF parsing library (e.g. PdfBox-Android, a few MB). Is that acceptable for v1, or should v1 ship with the loupe and direction lock only?
6. **Photo/scan plans** need the camera permission (or the ML Kit scanner via Play services). In scope at all?
7. **Export format**: should markups and data go back into the PDF (layer plus embedded JSON), into our own project file, or both?
8. **Takeoff / counts / cost estimating**: in scope for the plan feature, or measuring only?
9. FieldScale's Terms forbid reverse engineering. This doc records behaviour only. Is it fine to keep it in the repo, or should it stay out of version control?

## 12. Sources

- User-supplied export of the installed app, v3.5.6 (52); hashes in §0.2
- Google Play listing: https://play.google.com/store/apps/details?id=com.fieldscale.app (fetched 2026-11-21)
- Play data safety: https://play.google.com/store/apps/datasafety?id=com.fieldscale.app
- Privacy policy (July 2026): https://fieldscale-7ff69.web.app/privacy.html
- jadx 1.5.7: https://github.com/skylot/jadx/releases/tag/v1.5.7
- Our docs and code: `docs/plans/unit-system.md`; `core/measure/.../Format.kt`, `UnitSystem.kt`, `input/CalcInput.kt`
