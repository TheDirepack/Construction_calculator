"""Fail-closed readiness check for verified full-page detector validation sources.

The presence of a few annotated boxes (or an electrical sheet title) does not
establish exhaustive annotations. This preflight checks the actual annotation
JSONs (or format-2 ``*_labels.json#pN`` page references) supplied to phase two,
not names/directories.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import glob
import json
from pathlib import Path

from blueprint_symbols.neca_svg_compositor import SAMPLING_GROUPS as CANONICAL_CLASSES
from blueprint_symbols.dataset_manifest import trusted_training_instance
from blueprint_symbols.label_schema import (
    exhaustive_classes,
    expand_page_paths,
    load_training_page,
    read_page,
)


def _expanded_family_set(names: list[str]) -> set[str]:
    return set(names) | exhaustive_classes(names)


def _attestation_matches(doc: dict, families: set[str]) -> bool:
    attestation = doc.get("annotation_completeness_attestation")
    if not isinstance(attestation, dict):
        return False
    if attestation.get("method") != "dataset_or_source_coverage_explicitly_verified_by_user":
        return False
    scope = attestation.get("scope")
    if not isinstance(scope, list) or not all(isinstance(x, str) for x in scope):
        return False
    return families.issubset(_expanded_family_set(scope))


def audit_verified_eval(paths: list[Path], families: set[str] | None = None) -> dict:
    families = set(CANONICAL_CLASSES) if families is None else set(families)
    counts = Counter()
    groups: dict[str, Counter] = defaultdict(Counter)
    problems: list[str] = []
    for path in sorted(set(p.resolve() for p in paths)):
        try:
            doc = read_page(path)
            if not isinstance(doc, dict):
                raise ValueError("annotation must be an object")
            group = doc.get("project_family_id")
            if not isinstance(group, str) or not group.strip() or doc.get("source_group_verified") is not True:
                raise ValueError("source_group_verified and project_family_id required")
            instances = doc.get("instances")
            if not isinstance(instances, list):
                raise ValueError("instances must be a list")
            negative = doc.get("verified_no_symbols", False)
            if type(negative) is not bool:
                raise ValueError("verified_no_symbols must be a boolean")
            if negative and instances:
                raise ValueError("verified_no_symbols conflicts with active instances")
            exhaustive = doc.get("exhaustive_families", [])
            if not isinstance(exhaustive, list) or not all(isinstance(x, str) for x in exhaustive):
                raise ValueError("exhaustive_families must be a list of class names")
            explicit_attestation = _attestation_matches(doc, families)
            if negative:
                counts["verified_empty_pages"] += 1
            elif not families.issubset(_expanded_family_set(exhaustive)) or not (
                doc.get("annotation_completeness_verified") is True or explicit_attestation
            ):
                raise ValueError("missing human-attested exhaustive annotations for all detector classes")
            view = load_training_page(path)
            view_instances = view.get("instances") or []
            real_positive = 0
            for idx, x in enumerate(instances):
                if not isinstance(x, dict) or not trusted_training_instance(x):
                    continue
                if isinstance(x.get("source"), dict) and x["source"].get("kind") == "synthetic_composite":
                    continue
                view_cls = view_instances[idx].get("symbol_family") if idx < len(view_instances) and isinstance(view_instances[idx], dict) else None
                if x.get("symbol_family") in families or view_cls in families:
                    real_positive += 1
            groups[group]["pages"] += 1
            groups[group]["real_positives"] += real_positive
            counts["pages"] += 1
            counts["real_positives"] += real_positive
        except (ValueError, KeyError, TypeError, OSError) as exc:
            problems.append(f"{path}: {exc}")
    real_positive_groups = sum(v["real_positives"] > 0 for v in groups.values())
    if len(groups) < 2 or real_positive_groups < 2:
        problems.append("need at least 2 independent fully reviewed project groups with real target symbols for validation and test")
    return {
        "ready": not problems,
        "classes": sorted(families),
        "verified_groups": len(groups),
        "real_positive_groups": real_positive_groups,
        "counts": dict(counts),
        "problems": problems,
    }


def audit_prepared_evaluation(path: Path) -> dict:
    """Check the ACTUAL held-out assignment, not only candidate source counts."""
    manifest = json.loads(path.read_text(encoding="utf-8"))
    class_ids = manifest.get("class_ids")
    if not isinstance(class_ids, dict) or not class_ids:
        raise ValueError("prepared manifest missing class mapping")
    families = set(class_ids)
    problems: list[str] = []
    counts = {}
    for split in ("val", "test"):
        sources = manifest.get("splits", {}).get(split, [])
        real_positive_count = 0
        projects: set[str] = set()
        for source in sources:
            doc = read_page(source)
            view = load_training_page(source)
            if doc.get("project_family_id"):
                projects.add(doc["project_family_id"])
            explicit_attestation = _attestation_matches(doc, families)
            negative = doc.get("verified_no_symbols") is True
            raw_exhaustive = doc.get("exhaustive_families", [])
            view_exhaustive = view.get("exhaustive_families", [])
            exhaustive_set = set(view_exhaustive)
            if isinstance(raw_exhaustive, list) and all(isinstance(x, str) for x in raw_exhaustive):
                exhaustive_set |= _expanded_family_set(raw_exhaustive)
            full = exhaustive_set >= families
            if not full and not negative:
                problems.append(f"{split}: incomplete evaluation annotation {source}")
            if not negative and not (doc.get("annotation_completeness_verified") is True or explicit_attestation):
                problems.append(f"{split}: evaluation lacks human completeness attestation: {source}")
            raw_instances = doc.get("instances", [])
            view_instances = view.get("instances", [])
            for idx, inst in enumerate(raw_instances):
                if not isinstance(inst, dict) or not trusted_training_instance(inst):
                    continue
                provenance = inst.get("source")
                if isinstance(provenance, dict) and provenance.get("kind") == "synthetic_composite":
                    continue
                view_cls = view_instances[idx].get("symbol_family") if idx < len(view_instances) and isinstance(view_instances[idx], dict) else None
                if view_cls in families or inst.get("symbol_family") in families:
                    real_positive_count += 1
        counts[split] = {"real_verified_positives": real_positive_count,
                         "projects": len(projects), "pages": len(sources)}
        if real_positive_count == 0:
            problems.append(f"{split}: zero reviewed real positive symbols; evaluation would be misleading")
    return {"ready": not problems, "splits": counts, "problems": problems}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    choices = parser.add_mutually_exclusive_group(required=True)
    choices.add_argument("--annotations", nargs="+", help="glob(s) of human-verified full-page annotation JSONs or *_labels.json files")
    choices.add_argument("--prepared-manifest", type=Path, help="check assigned real val/test annotations after dataset export")
    args = parser.parse_args()
    if args.prepared_manifest is not None:
        report = audit_prepared_evaluation(args.prepared_manifest)
    else:
        raw_paths = [
            Path(path) for pattern in args.annotations for path in glob.glob(pattern, recursive=True)
            if path.lower().endswith(".json") or "#p" in path
        ]
        paths = sorted(set(expand_page_paths(raw_paths)))
        report = audit_verified_eval(paths)
    print(json.dumps(report, indent=2))
    if not report["ready"]:
        parser.exit(2, "Cannot launch supervised detector without independently verified real eval sources.\n")


if __name__ == "__main__":
    main()
