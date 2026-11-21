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

## Observed Android measuring method

The manual-entry wizard in Android 2.3.4 illustrates the flange diameter. Its alternate drum
method asks for the radial distance from the flange rim to the bare drum. Construction Calc now
offers that input alongside direct diameter and circumference. The depth method derives the
drum diameter as flange diameter minus twice the depth. The distance to the cable surface is
a separate measurement.

## Gap vs. our app
Construction Calc's `feature/reellength` estimates cable remaining or reel capacity. Enter the core diameter directly or derive it from circumference or radial depth. Results include length, wound depth, layers and turns per layer and can be saved to a project.

**They have, we lack**
- A built-in library of real reel types (flange/drum/traverse presets) and cable types (OD presets), so users pick instead of measuring.
- User-saved favorite reel+cable combinations.
- Illustrated guidance for locating each measurement on the reel.

**We have, they lack**
- Capacity mode, packing factor, layers/turns output, wound-depth output.

**Suggested follow-up**: user-saved reel profiles first (no vendor data needed), then a small preset list of common reel sizes sourced independently.
