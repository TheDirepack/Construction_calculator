"""Two-stage SAM exemplar query planner.

Stage 1: one or a few representative visual prompts for every device family.
Stage 2: fine-grained visual prompts only for families proposed in that tile.

The same image embedding is used for both stages. Coarse output is only a
provisional candidate: no subtype, voltage or other attribute is inferred from
the exemplar used to find it.
"""
from __future__ import annotations

from collections import defaultdict
from typing import Any


def _family(glyph: Any) -> str:
    return str(glyph.device_type)


def build_hierarchical_prompt_plan(
    glyphs: list[Any],
    *,
    coarse_per_family: int = 2,
    fine_per_family: int = 0,
) -> dict[str, Any]:
    """Return index-only plan aligned to the exact pre-encoded glyph order.

    fine_per_family=0 retains all available fine variants. Coarse prompts
    remain in the fine pool too: a common exemplar can provide a subtype
    hypothesis, but the coarse result itself never claims that subtype.
    """
    if coarse_per_family < 1 or fine_per_family < 0:
        raise ValueError('coarse_per_family must be positive; fine_per_family must be nonnegative')
    by_family: dict[str, list[int]] = defaultdict(list)
    for idx, glyph in enumerate(glyphs):
        by_family[_family(glyph)].append(idx)
    if not by_family:
        raise ValueError('cannot build SAM plan from empty prompt bank')

    coarse: list[int] = []
    fine: dict[str, list[int]] = {}
    for family in sorted(by_family):
        indices = by_family[family]
        seen_subtypes: set[str] = set()
        representative: list[int] = []
        for idx in indices:
            subtype = str(glyphs[idx].subtype)
            if subtype not in seen_subtypes:
                representative.append(idx)
                seen_subtypes.add(subtype)
            if len(representative) >= coarse_per_family:
                break
        # If there are fewer distinct subtype exemplars, fill from remaining
        # geometry so a family is not accidentally lost.
        if len(representative) < coarse_per_family:
            representative += [i for i in indices if i not in representative][
                :coarse_per_family - len(representative)]
        coarse.extend(representative)
        fine[family] = indices[:fine_per_family] if fine_per_family else indices

    return {
        'coarse_indices': coarse,
        'fine_indices_by_family': fine,
        'families': sorted(by_family),
        'coarse_count': len(coarse),
        'fine_catalog_count': sum(len(v) for v in fine.values()),
    }


def active_fine_indices(plan: dict[str, Any], proposed_families: set[str],
                        *, fallback_full: bool = False) -> list[int]:
    """Select only subtype queries that could refine observed coarse families."""
    if fallback_full:
        families = plan['families']
    else:
        families = sorted(set(plan['families']) & set(proposed_families))
    return sorted({idx for family in families
                   for idx in plan['fine_indices_by_family'][family]})


def accepted_fine_match(
    fine: dict[str, Any],
    coarse: list[dict[str, Any]],
    *,
    min_iou: float = 0.10,
) -> bool:
    """Avoid publishing a subtype prediction unrelated to the coarse region."""
    from blueprint_symbols.proposal_fusion import _iou
    box = fine['box_px']
    for anchor in coarse:
        if anchor['device_type'] != fine['device_type']:
            continue
        c = anchor['box_px']
        if _iou(box, c) >= min_iou:
            return True
        cx = (box[0] + box[2]) * 0.5
        cy = (box[1] + box[3]) * 0.5
        cw = c[2] - c[0]
        ch = c[3] - c[1]
        if c[0] - cw * 0.25 <= cx <= c[2] + cw * 0.25 and \
           c[1] - ch * 0.25 <= cy <= c[3] + ch * 0.25:
            return True
    return False


def explain_query_savings(plan: dict[str, Any], active_families: set[str]) -> dict[str, int]:
    fine = active_fine_indices(plan, active_families)
    # Counts are *prompt queries*, not measured GPU time.
    return {
        'coarse_queries': plan['coarse_count'],
        'fine_queries': len(fine),
        'total_queries': plan['coarse_count'] + len(fine),
        'flat_queries': plan['fine_catalog_count'],
    }
