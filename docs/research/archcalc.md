# ArchCalc / "Feet & Inch Construction Calc": feature and UX inventory

Research notes for designing our own original app. This is an observation of behaviour, not a spec to copy. No code, art or text is reused.

## 0. Scope, sources and confidence

| Item | Detail |
|---|---|
| App | Play id `evanwinograd.archcalc`. Listing name "Feet & Inch Construction Calc"; manifest label "Feet Inch Calculator" (originally "ArchCalc"). Released 30 Dec 2014. |
| Developer | Winograd Apps, LLC (one-developer shop; reviewers note the dev replies to feedback personally). |
| Version inspected | 9.1.0 (versionCode 5300), minSdk 24, target 36, XAPK ~97 MB. |
| Traction | 1M+ installs, 4.6★ (about 3,240 ratings). Free with ads; IAP "$0.99 – $29.99 per item" (ad-free / lifetime upgrade; RevenueCat). |
| Tech | **Flutter** (`libapp.so` AOT Dart). The Java/Kotlin side is only plugins, ad SDKs and Sentry/Firebase. |

**Sources, and how much to trust each**

1. **Play listing**: description, "what's new", reviews and developer replies. High trust.
2. **Play screenshots**: the main keypad (shot 0), Tools grid, Stringer, Rafters, Pipe, Electric and HVAC pages. High trust for layout. They are iOS captures of the same Flutter UI.
3. **Bundled in-app "what's new" images** (`assets/images/app_updates/*`): the tape display, output-unit menu and answer menu. High trust.
4. **Dart string table from `libapp.so`** (`strings`, ASCII and UTF-16). This gives UI labels, setting names, help sentences and changelog lines. **Medium trust**: strings are unordered and carry no behaviour, so key sequences and semantics below are inferred from help text plus screenshots. Inferences are flagged "(inferred)".
5. Dart AOT cannot be decompiled with jadx. I did not run the app, so there is no behavioural testing. Edge cases (chaining after `=`, error messages, rounding ties) are **not verified**.
6. Many `Numpad*`, `Favorite*1`, `Game Button`, `Arrow*` and `Browser*` strings are Flutter's own keyboard-key names, not app features. They are ignored.

**Provenance note.** The target directory `…/7d9659bd-…/scratchpad/apks/archcalc/` was already populated when I started. It held `archcalc.xapk`, `evanwinograd.archcalc.apk`, `config.armeabi_v7a.apk` and an extracted `lib/`, so I did not download from a mirror myself. The APKs were treated as data only. I ran `unzip`, `aapt2 dump badging`, `strings` and python `-I` regex scripts on copies extracted into the session scratchpad, and nothing was installed or executed.

---

## 1. Core concept: colour-coded dual keypad

The Play listing says: **"Blue = Feet, Green = Inches & fractions."** The whole app builds on this.

- Two full 3×4 number pads sit side by side: **blue for feet, green for inches**. A third column of **fraction keys** sits at the right edge, in green.
- A dimension is composed from three independent parts: feet (blue pad), whole inches (green pad) and a fraction (fraction column). There is no `'`, `"` or `/` key to press and no mode switch. The colour of the key you tap is the unit.
- Help text: "Tap green buttons to input inches and fractions"; "The calculator has two sections: the display lines at the top, and the input sections at the bottom"; "The bottom section has 3 columns: two number sections, fractions, and operations."
- Accessibility: an option makes the blue feet keys square-cornered and the green inches keys round-cornered, so the pads do not rely on colour alone. Colours are also customisable.
- Option "Use input rows instead of the number pads" (inline tap-to-edit fields).

### 1.1 Main-screen layout (from the Play screenshot, portrait phone)

```
┌──────────────────────────────────────────────┐
│ 5' 4"·                         5.33 Ft+  [...]│  ← output line: left answer / right answer / menu
│ + [8' 8 3/8"]                         [Clear] │  ← input line (operator + current operand)
├───────────────┬───────────────┬──────────────┤
│  1   2   3    │  1   2   3    │  ←(Frac)     │
│  4   5   6    │  4   5   6    │  1/8         │
│  7   8   9    │  7   8   9    │  1/4         │
│  0   .   ←    │  0   .   ←    │  3/8         │
│ [Ft][Yd][Mi][%]│ [In][m][cm][mm]│  1/2 (bold) │
│               │               │  5/8 3/4 7/8 │
├───────────────┼───────────────┤  1/16 3/16 … │
│ Settings  Help│  +      −     │  (scrolls)   │
│ [N] Review    │  ×      ÷     │              │
│ Tools         │  ×d     ÷d    │              │
│ All clear     │  =            │              │
└───────────────┴───────────────┴──────────────┘
```

- **Keys, left to right.** Blue pad: `1-9`, `0`, `.`, `←`. Green pad: `1-9`, `0`, `.`, `←`. Fraction column: `←(Frac)` (deletes only the fraction), then 1/8, 1/4, 3/8, 1/2, 5/8, 3/4, 7/8, 1/16, 3/16, 5/16 and so on, scrolling down to the finest precision allowed (§3).
- **Unit tabs under the pads.** Under blue: `Ft | Yd | Mi | %`. Under green: `In | m | cm | mm` (scrolls). Their visibility is tied to "Advanced" mode (§6).
- **Bottom-left block.** `Settings`, `Help`, **`Review`** (with a badge showing the step count of the current calculation), `Tools`, red `All clear`.
- **Operator block (orange).** `+ − × ÷`, then `×d ÷d` labelled "Dimension" (the plain `× ÷` are labelled "Number"), then `=`.
- **Fraction shading.** Denominators alternate light and dark green, so 1/8, 1/4, 1/2 are easy to find among the 16ths. Setting: "Uses different shades to help distinguish fraction denominators. Disable it to use one colour." 1/2 is bold. The default is to shade, and the fraction column is the only scrolling control on the main screen.

### 1.2 Layout options (Settings → Layout)

- "Alternate Layout" vs "Traditional Layout".
- Number pads on Top / Left / Bottom, and "flip number pads" or "flip number pad rows".
- Feet on Right / Left, and "Feet next to Inches".
- "Inches and Metric on top/left".
- "Flip Output Line" and "Display Swap" (swap the left and right answers).
- Optional **shortcut bar above the output line**. Help: "Shortcut bar allows you to adjust your output line from the main calculator."
- Optional memory buttons (§7).
- Button click sound, vibration, custom button colours, themes (a "Classic" theme exists), language picker (EN, ES, FR, DE, PT, RU and others), and "App keeps phone awake".
- "Force Small Screen" under Settings → General.

---

## 2. Entering dimensions (key sequences)

These sequences are inferred from help text and screenshots (the screenshot's input line reads `8' 8 3/8"` with a blue 8, a green 8 and a 3/8 key).

| Goal | Keys |
|---|---|
| 5' 3-1/2" | blue `5`, green `3`, fraction `1/2` |
| 8' 8 3/8" | blue `8`, green `8`, fraction `3/8` |
| 6 1/4" only | green `6`, fraction `1/4` (feet stays 0) |
| 0' 0" | Empty state of the input line. It always shows `0' 0"`, so no digits means zero. |
| Decimal inches | green digits with `.` (e.g. `6.25`) |
| Decimal feet | blue digits with `.` (e.g. `5.33`) |
| Fix a mistake in the fraction only | `←(Frac)` |
| Fix a mistake in feet or inches | `←` on the matching pad |

- Entering is **additive across the three zones**. You can enter inches before feet, or fractions at any point (inferred from independent pads).
- **Out-of-range inches.** The inches pad accepts e.g. 24. The output example "Feet and inches: 24" → 2'" shows it is normalised in the display, while "Inches only: 24" → 24"" shows the other format (§3.3).
- **Custom fractions.** Settings → Numbers → Custom Fractions allows e.g. 3/5. A tip reads "For custom fractions like 3/5, go to Settings → Numbers → Custom Fractions", and "Number pads for multiplying by custom fractions" exists.
- **Clipboard and saved values.** Long-pressing the `0' 0"` input line opens an edit menu: Copy, Paste (it shows the clipboard value, e.g. `Paste 3 17/32"`), Enable clipboard in Favorites, Save As, Select (pick from Copied, Clipboard, Favorites, Saved), and a "Clear inputs on edit popup" toggle. The paste privacy line reads "App only reads from clipboard when paste is selected."
- **Metric.** Tap a metric tab (`m`, `cm`, `mm`) under the green pad. Without Advanced mode a notice reads "Turn on Advanced mode to input metric." A zero-dimension notice exists ("A zero dimension was changed to 10′") for tools that need a non-zero value.
- **Quick area input.** "Tap to enter Length x Width", "Tap to enter Area x Height", "Tap to enter Pounds & Ounces". When the calculator is cleared you can choose to start with a number, dimension, area or volume.

---

## 3. Display, fractions, precision, rounding

### 3.1 The output line

- Two answers share one row, with a `…` menu at the right:
  - **Left answer**: feet, inches and fraction (e.g. `19' 2 7/8"`).
  - **Right answer**: a user-selectable alternate unit (e.g. `19.24 Ft`).
- Help: "Tap left and right answers for actions and settings." Tapping an answer **copies** it. A long-press gives the alternate-format actions (`longPressedImperialOutput`, `longPressedAlternateOutput`, inferred).
- **Right-answer choices** ("Select Output"): Feet (decimal), Decimal Inches, Inches + Fractions, Meters, Centimeters and more (list scrolls). There are options to cycle with decimal inches, fractional inches or both ("Adds both decimal inches and fractional inches to the right-output cycle", and "You can cycle through inches as a decimal and inches as a fraction").
- **Answer menu** (the `…` button), in order:
  1. **Quick Math**
  2. **Copy** `6 1/4"` (the value is shown in the label)
  3. **Save** `6 1/4"` (to Favorites/saved)
  4. **Series** (`6 1/4"` increments)
  5. **Output Settings**
- **Series** tool: it shows multiples of the answer on the output line. It has an offset, which can be negative. If the answer falls between fraction marks it asks "Which increment should Series use?" The intended use is layout marks such as balusters and studs.
- **Custom unit outputs** (new in 9.1): the right answer can be a material unit. Examples in the bundled screenshot: `0.250 Trim (16')`, `0.500 4x8 Sheet`, `11.852 Bag (60 lb)`. A dimension times a dimension times a dimension (area then volume) can be read out as sheets or concrete bags directly.

### 3.2 Rounding indicator

- A suffix after the number tells you whether the on-screen value is exact:
  - `•` means exact, with no rounding (e.g. `0.250•`, `1.00"•`, `10' 7 1/4"•`).
  - `+` means the true value is slightly higher than shown (e.g. `5.33+ Ft`, because 5' 4" is 5.3333… ft).
  - `−` means the true value is slightly lower than shown (e.g. `19.24− Ft`).
  - This reading is inferred from screenshot examples and the setting "Shows how the exact result compares with the rounded value on screen". It is a toggle (Settings → Rounding Indicator).
- Setting: "Show a fraction below the original inch decimal only when it is within 0.00005" of a 1/64" fraction." The 9.1.0 changelog says "Added optional fractional equivalents for exact inch dimensions".

### 3.3 Precision and format settings (Settings → Numbers)

- **Input Fraction Precision**: "Sets the smallest fraction available when entering dimensions." This decides which keys the fraction column shows. Choices go **up to 1/128** (a reviewer asked for 1/32 and the developer pointed to this setting; the 1/32 and 1/64 hints say "rounds to the nearest thirty-second").
- **Output Fraction Precision**: "Controls how precisely fractional inches are rounded in the left output." It is separate from input precision, and a warning reads "Precision setting affects output for entire app".
- **Decimal precision** (digits) and a separate setting for metric rounding.
- **Inches Only**: "Choose whether the left output uses inches only or feet and inches". It is **the most-asked-about setting** in reviews. With it on, 96" − 55 7/8" shows `40 1/8"` instead of `3' 4 1/8"`. A decimal-output setting for the triangle and converter tools is now separate from it.
- **Non-reduced fractions**: "Setting to keep fractions non-reduced", plus "Simplifies entered fractions to their lowest terms" and "Simplifies fractional results without changing their value". Example: "Reduced: 2/16 → 1/8".
- **Round to nearest** x/N: "Enable to round calculated values to nearest x/". A note says "(Disable rounding under settings for precise values when distributing heights)".
- **Architectural notation** (optional): `2' 3 1/2"` can show as `2-03-08` (feet-inches-eighths, a survey and drafting style).
- A "Decimal Units" setting also exists (Decimal Feet, Decimal Meters, Decimal Centimeters, and so on).
- **Colour-coding of text**: three settings, "Color code input line", "Color code output line" and "Color code steps". Feet render blue and inches and fractions render green, and plain numbers and metric stay normal.

---

## 4. Operator chaining, tape and errors

- **Tape display.** A scrolling tape of lines above the input:
  ```
  5' 4"                                     5.33+ Ft
  + 5' 3 1/4"  = 10' 7 1/4"•                10.60+ Ft
  + 8' 7 5/8"  = 19' 2 7/8"•                19.24− Ft
  [ 19' 2 7/8"• ]        [ 19.24− Ft ]  [...]
  +  [0' 0"]                                [Clear]
  ```
  Every step shows the operand, the running total in feet-inches-fractions **and** a decimal in the chosen alternate unit. 9.1.0 added "Preview calculation steps on home screen", and the default view shows the last few steps (older ones are in Review).
- **Chaining.** Press an operator, enter the next dimension, and the previous running total is shown. `=` gives the final result. You can keep operating on a result ("Continue calculations" tutorial). The `+` or `−` between lines is the operator the next operand will use.
- **Four operator kinds:**
  - `+` and `−` between dimensions.
  - `×` and `÷` by a plain **number** (scales a length: 4' ÷ 3, 5' × 2.5).
  - **`×d`** multiplies by another **dimension**: length × length gives **area**, then × length gives **volume**. Tutorial: "Enter the first dimension, press xd, enter the second dimension, then press =. Example: 1' 6" xd 2' gives an area result of 3 square feet." "Use xd more than once when you need volume."
  - **`÷d`** divides by a dimension and gives a dimensionless ratio. Example from a reviewer: 2'1" ÷ 4" = 6.25. It needed "Advanced" (see §6).
- **Area and volume in the tape**: "Area / Volume is shown for each step in addition to cumulative area". The answer can be shown in square or cubic feet and yards, or converted to materials via custom units.
- **Percent tab** `%` sits under the blue pad (inferred as a percentage input).
- **Errors.** There are no "Err" screens. The app **blocks invalid edits** with specific messages when you delete or change a step:
  - "Deleting this step would combine incompatible units."
  - "…would leave an invalid division / operation / result."
  - "…would leave a root with incompatible units."
  - "…would leave division by zero."
  - "…would make the current input's units incompatible."
  Roots (√) and cube roots exist (fixed in the Review page notes) but I did not find where the key is, so treat the exact entry method as unknown.
- **Starting a calculation**: after All clear you may begin with a plain number, dimension, area or volume, which sets up the right kind of operand.

---

## 5. Review (history), favorites and editing

- **Review** is a full-screen page opened from the main keypad. The key carries a badge with the step count of the current calculation.
  - Help text: "Use the Review button to see previous calculation steps and saved calculations. From Review, you can navigate older calculations, inspect each step, and go back to the calculator when you are done."
  - "Use Review when you need to fix a mistake in a previous calculation. Press the edit icon on the step or dimension, change it with the number pads, then use the back arrow to return."
  - "Tap an input to edit. Tap an answer to copy. Swipe left to delete a step." "Long press a step in the review page to name it."
  - "Use Review to return to an earlier calculation, then continue from that result instead of starting over."
- **History** keeps the **last 100 calculations plus favorites** ("Saves last 100 calculations + favorite calculations"). A star on the Recent calculations page adds a calculation to Favorites. Cards are compact so history is easy to scan. History and favorites are deleted together or separately ("Your favorite calculations are safe!").
- **Favorites / saved dimensions**: Save a dimension from the answer menu and pick it back into any input via Select (Copied, Clipboard, Favorites, Saved). A **Copy dimension also copies to phone clipboard** option exists.
- **PDF export** of calculations: "Export calculations to PDF (recent, favorites, or individual)". The free version stamps "Created with Feet & Inches Calculator – Upgrade for unbranded PDF exports".
- **Backup**: a "Settings Only" backup (layout and formatting), a full prefs backup (also tools and projects), restore, and a full reset ("This will delete all settings, saved tools, and calculation history").
- **Edit popups** accept the same blue and green pads, with "Auto-Clear dimensions on edit popup".

---

## 6. "Advanced" mode and unit conversion

- A setting named **Advanced** gates metric entry and extra units: "Enables metric units and advanced input options on the calculator number pads." "With Metric / Advanced on, other enabled dimensions are also available on the number pads." Disabled units are hidden from the pads and from output selection ("Enabled units appear as input and output choices throughout the app").
- A separate setting "Show all units even with Advanced turned off" exists, and a "hide metric/advanced toggle" flag.
- Unit tabs (below the pads): Ft, Yd, Mi and % for the left pad. In, m, cm and mm for the right pad, and more behind a scroll (e.g. Metric Ruler values).
- **Unit Converter tool** (v2): linear, area, volume, pressure, temperature, weight, angle and **custom units**. Notes: "Quickly convert between two units", "Convert custom units".
- Pitfall reported by users (see §10): the key hint "Turn on Advanced and use the [÷d] button" needed a developer reply, so the dimension-divide feature was not discoverable.

---

## 7. Memory

- Optional **memory line** (Settings → Layout): "Enable memory under Settings → Layout to use this feature". The buttons are **MS** (store), **MR** (recall), **M+**, **M−**, **MC**, and per UI strings also **M×** and **M÷**. A setting hides them ("Hide MR, M+, M−, MC"). "Using the MS button will save entries to memory."
- Memory entries are dimension values (a `MemoryEntry` model exists). Long-pressing a memory slot has a special action (`longPressedMemory`, inferred).
- Memory is off by default, so most users never see it. Favorites and the saved-dimension picker serve the same need more discoverably.

---

## 8. Tools (the "toolbox")

Opened with the teal **Tools** key. The page is a searchable grid or list (a toggle, a Sort control and a search box), and tools can be hidden ("Option to hide unused tools from Tools page"). Double-tapping a tool adds a **shortcut to the home screen**. Each tool page has a PDF export (top right), an info (i) button with instructions, and often a 2D, 3D and 3D+ viewer. Tool state is saved as named **projects**.

Groups seen in the grid and string table (v9.1.0):

- **Framing**: Triangle (90°, general and 3D; pick any 2 inputs from X, Y, R, θ; "SOH CAH TOA" help; slope as pitch such as 16/12), **Stair Stringers** (rise and run references, riser or stringer face, overhang, nosing, flush or dropped top step, 2D/3D, optional code-profile check on step height and tread depth, series marks), **Rafters** (common, hip and valley, jacks, sheathing; single or dual pitch; Span or Run input; 3D view; rafter spacing chips 12/16/19.2/24; a quick field calculator), Rake wall, Plumb, Slope (ratio, % grade, total drop or rise), Studs, Floor Plans.
- **Floor Plans / takeoff** (biggest module): draw or trace walls, doors and windows, import a PDF or image plan, auto-detect walls, set scale from two points, multi-floor. Takeoffs for studs, wall framing, sheet goods, flooring, paint, trim, insulation, roofing, lighting, wiring and grid ceiling, with PDF export.
- **Cabinets**: boxes, doors, drawers, front layouts, a room layout editor, and **cut lists** (grouped and individual) with JSON import and export. A reviewer said the Cabinet tool speeds up planning and quotes.
- **Cut Optimizers**: 1D linear (single and multi stock) and 2D sheet nesting. Also a Cut List tool with completion tracking.
- **Concrete & Fill** (slab, footing, bell footing, stairs, cylinder, grid), Board Feet (price, waste, take-off list), Circle / Column, Chords / Arcs (advanced circles), Area → Count, Baluster / Fence, Equal Spacing, Intervals, Scale, Miter and compound miter, Kerf bending, Tile and grout, Grid layout and ceiling, Paint, Weight / load, Level, Ruler (calibrated on-screen), Camera measure (reticle A→B, perimeter) and Room scan.
- **Trades**: **Pipe fitting** (butt-weld elbow takeout, equal-spread offset, offsets and rolling offsets, pipe-size tables, flange and B16.9 catalogues, conduit and pipe bending (saddle, single 90, SAG), **Electric** (ampacity, box fill, conduit fill, conduit bending, electricity cost, motor FLC, Ohm's law, voltage drop, wire-size converter), **HVAC** (air changes, CFM↔FPM, duct sizing and wrap, friction loss, load converter, pressure and temperature, psychrometrics, superheat and subcool, refrigerant, fan laws), Welding (fillet and bevel groove), Steel / Fabrication (beam and HSS tables), Surveying (traverse, 3D triangle), Low voltage.
- **Business tools**: Invoice generator, time tracker, to-do list, **Punch list** (separate app "Punch List Portal" by the same developer), project management, currency.

---

## 9. Monetisation, permissions, platform

- Ads (AdMob-style banner, app-open ad, rotating sponsor cards from partners such as a plan supplier and a field-service vendor). The upgrade card is promoted in-app. A "lifetime upgrade" removes ads and branding on PDFs.
- Permissions requested: internet, billing, vibration, **camera** (measure and scan), **microphone** and storage. The Play data-safety section says the app may share App activity, App info and performance, and Device or other IDs with third parties.
- Libraries: Firebase Analytics, Sentry, RevenueCat, ad mediation SDKs, a PDF engine, a 3D GLB viewer.

---

## 10. What users like and criticise (reviews)

**Liked**
- "Most helpful app on the market", "no need for my other apps", and "better than a $30 physical calculator". Free tier is viable and people pay to support the developer.
- Dual-colour pad and quick feet-inch-fraction entry; being able to pick the result format (fraction, decimal, metric).
- Developer responsiveness.

**Criticised**
- **Output format does not follow input style.** A user subtracted 55 7/8" from 96" and got `3' 4 1/8"`. They wanted it to detect that they were working in inches. The fix is a global Inches Only setting, which is a hidden toggle in Settings. Another wanted `29 3/16"` instead of 29.19 (same fix).
- **`÷d` and Advanced mode confusion.** A user lost fraction-of-feet results after an update and the cure was "turn on Advanced and use [÷d]".
- **Precision discoverable only in Settings** (two reviewers could not find 1/32 or 1/128 and edited their ratings).
- **Rafter tool redesign** triggered a 1-star review ("changed the entire setup").
- **Unlabelled steps**: a plumber wanted labels on measurements and totals to re-check how a centre was derived. This is now being built (step naming exists in Review).
- **Feature sprawl**: HVAC, welding, invoices, punch list and floor-plan tooling are well beyond a calculator, so the app is 97 MB with more than 100 modules. A reviewer thinks 'rarely needed' tools should not crowd the product.
- **Ads and permissions** (camera, mic) for a calculator. A reviewer also wants a desktop (Windows or Linux) version.

---

## 11. UX takeaways for our own design (top 10)

1. **Colour = unit.** Two same-shaped pads, blue for feet and green for inches (plus fractions), remove the need for `'`, `"` and `/` keys. Pair it with shape or label so it works for colour-blind users.
2. **Fractions are first-class keys**, in a dedicated column with alternating shades per denominator, and a separate `⌫` for fraction only. The visible set follows an input-precision setting up to 1/128.
3. **Two-line answer**: always show a primary construction format and a configurable secondary (decimal feet or inches, metric, material units). One tap copies. Decouple *input* precision from *output* precision.
4. **Rounding honesty**: an inline marker (exact `•`, slightly high `+`, slightly low `−`) beats silently rounding to 1/16.
5. **A visible tape with running totals** (operand, result and decimal on each line), tap to edit any line, swipe to delete, long-press to name. History is for fixing mistakes, not only for reading.
6. **Dimension-aware math with explicit operators**: `×`/`÷` by a number versus `×d`/`÷d` by a dimension. Area, volume and ratio fall out naturally, with units tracked through the chain. Make them visible by default, not behind a mode.
7. **Validate edits instead of showing "Error"**: say why a change is invalid ("would combine incompatible units").
8. **Surface the big toggles near the answer.** Inches Only (feet+inches vs inches), precision and decimal/fraction cycling were the top support questions, so put them on the output line or its menu and consider auto-sensing input style.
9. **Saved values with a picker** (Copied / Clipboard / Favorites / Saved) and a "continue from history" path remove most need for a memory register. Keep MS/MR optional.
10. **Stay a calculator first.** Keep the home screen to the keypad plus one tools entry, make the trade tools a searchable grid with home-screen shortcuts, and keep each tool as a small input form with a diagram and a result block. Avoid the sprawl, ads and permission creep that this app's own reviews flag.
