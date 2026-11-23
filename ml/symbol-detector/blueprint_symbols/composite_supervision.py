"""Supervision invariants for compositing symbols onto real blueprint backgrounds.

A source's *lack of annotations* is never proof that it lacks symbols.
Synthetic placements on an unreviewed page are verified positives only;
class-wide negative supervision requires an independent source attestation.
"""
from __future__ import annotations

from collections.abc import Iterable


def composite_supervision(
    source: dict,
    inserted: list[dict],
    *,
    generated_families: Iterable[str],
) -> dict:
    """Produce truthful labels for the ACTUAL composited image.

    verified_no_symbols describes final output pixels, not the origin page.
    This function never infers that absence from an empty instances list.
    """
    original = source.get("instances", [])
    if not isinstance(original, list) or not all(isinstance(x, dict) for x in original):
        raise ValueError("source instances must be a list of objects")
    if not isinstance(inserted, list) or not all(isinstance(x, dict) for x in inserted):
        raise ValueError("inserted instances must be a list of objects")
    source_clean = source.get("verified_no_symbols", False)
    if type(source_clean) is not bool:
        raise ValueError("source verified_no_symbols must be boolean")
    if source_clean and original:
        raise ValueError("source claims verified_no_symbols but has active instances")
    original_exhaustive = source.get("exhaustive_families", [])
    if not isinstance(original_exhaustive, list) or not all(
        isinstance(family, str) and family for family in original_exhaustive
    ):
        raise ValueError("source exhaustive_families must be a list of class names")
    families = set(generated_families)
    if not families or any(not isinstance(f, str) or not f for f in families):
        raise ValueError("generated_families must contain class names")
    for inst in inserted:
        if inst.get("symbol_family") not in families:
            raise ValueError("inserted instance outside generated_families")
    # An exhaustively labeled source retains that claim after exact placement.
    # An unverified source may have REAL unseen symbols in any non-exhaustive
    # family: generated boxes do not magically complete those source classes.
    exhaustive = set(original_exhaustive)
    if source_clean:
        exhaustive.update(families)
    instances = original + inserted
    empty_final = source_clean and not instances
    return {
        "instances": instances,
        "verified_no_symbols": empty_final,
        "source_background_verified_no_symbols": source_clean,
        "exhaustive_families": sorted(exhaustive),
        "annotation_completeness": "complete" if source_clean else "partial",
        "annotation_completeness_verified": source_clean,
        "annotation_scope": "verified_composite" if source_clean else "positive_only",
    }
