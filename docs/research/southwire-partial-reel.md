# Southwire Partial Reel Calc: feature inventory

Feature-inventory notes only; paraphrased.

| Item | Detail |
|---|---|
| App | Southwire Partial Reel Calculator |
| Play id | `com.southwire.partialReelCalculator` |
| Developer | Southwire Company; free |
| Date | 2026-11-20 |

**Sources actually read**
- https://play.google.com/store/apps/details?id=com.southwire.partialReelCalculator : fetched but truncated; only the title was readable.
- https://www.southwire.com/apps : vendor page.
- https://apps.apple.com/us/app/id489177969 : iOS listing and version history. Android parity **not verified**. The iOS ratings are poor (about 2.4 from 14 ratings).

## Feature inventory
- Estimates the footage of wire or cable left on a reel.
- Preloaded library of many cable types and reel types.
- Users can build their own favorite cable and reel entries; a Favorites list stores combined reel + cable data (specific Southwire reel codes were added to it over time).
- Reel dimension input screens with illustrative images.
- A drum-diameter screen that supports more than one measuring method (for example measuring in different ways, with the title adapting to the method). Exact methods not stated.
- Input validation on fields.
- A patent number is displayed (per release notes), so the method is vendor-proprietary.
- Review themes: needs more explanation for one field; freezes during entry (older reviews).
- No export/share, unit toggle or capacity mode is mentioned.

## Gap vs. our app
Ours: `feature/reellength`. Two modes (cable on reel, reel capacity) from flange diameter, drum diameter, traverse, cable OD, distance from flange edge (or clearance), packing factor; outputs wound depth, length (in, ft), layers and turns per layer; unit-aware length entry; input validation messages; saved items.

**They have, we lack**
- A built-in library of real reel types (flange/drum/traverse presets) and cable types (OD presets), so users pick instead of measuring.
- User-saved favorite reel+cable combinations.
- Multiple drum-diameter measuring methods (for example from a circumference reading) with guidance images.

**We have, they lack**
- Capacity mode, packing factor, layers/turns output, wound-depth output.

**Suggested follow-up**: user-saved reel profiles first (no vendor data needed), then a small preset list of common reel sizes sourced independently.
