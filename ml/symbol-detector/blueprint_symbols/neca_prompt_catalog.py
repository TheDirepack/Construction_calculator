"""Curated hierarchical NECA visual exemplars for SAM 3 image proposals.

Keep unique subtypes in detector proposals. Exclude legends that contain several
symbol alternatives, routing linework, and modifiers used only with another glyph.
Categories describe the electrical discipline; training categories are separate.
"""
from __future__ import annotations

import json
import hashlib
import os
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from pathlib import Path
from blueprint_symbols.neca_visual_groups import classify_neca_artwork, audit_neca_visual_groups

SECTION_CATEGORIES = {
    'Luminaires': 'lighting_fixture',
    'Outlets and Receptacles': 'power_outlet',
    'Switches and Sensors': 'lighting_control',
    'Motor and HVAC Controls': 'motor_and_hvac_control',
    'Motorized and HVAC Equipment': 'motor_and_hvac_equipment',
    'Security': 'security',
    'Fire Alarm Communications and Panels': 'fire_alarm_control',
    'Fire Alarm Indicators': 'fire_alarm_notification',
    'Fire Alarm Sensors': 'fire_alarm_detection',
    'Power Distribution Equipment': 'power_distribution',
    'Communications - Teledata': 'telecom_outlet',
    'Communications - Audio-Visual': 'audio_visual',
    'Communications - Equipment': 'communications_equipment',
    'Nurse Call System': 'nurse_call',
    'Raceways - Boxes and Busways': 'junction_and_service_box',
    'Annex A - Alternate Fire Safety': 'fire_safety_alternative',
}

NO_DEVICE_TERMS = {
    'Raceways - Boxes and Busways': ('cabletray', 'cable tray', 'busway', 'wireway'),
}


@dataclass(frozen=True)
class SamNecaPrompt:
    symbol_id: str
    code: str
    name: str
    section: str
    canonical_class: str
    device_type: str
    subtype: str
    category: str
    subcategory: str
    kind: str
    native_vectors: bool
    file: str
    alpha: object | None = None
    visual_group: str = ''
    prompt_role: str = 'base'
    encoded_attributes: object | None = None


def _slug(value: str, max_words: int = 9) -> str:
    value = re.sub(r'\[[^\]]*\]', ' ', value.lower())
    value = re.sub(r'[^a-z0-9]+', '_', value).strip('_')
    return '_'.join(value.split('_')[:max_words]) or 'unspecified'


def _classification(entry: dict) -> tuple[str, str, str, str]:
    """Category, descriptive subcategory, device type, device subtype."""
    section = entry['section']
    category = SECTION_CATEGORIES[section]
    name = entry.get('name', '')
    n = name.casefold()
    if section == 'Outlets and Receptacles':
        if 'junction box' in n: kind = 'junction_box'
        elif 'floor box' in n: kind = 'combination_floor_box'
        elif 'double duplex' in n: kind = 'quad_receptacle'
        elif 'duplex' in n or 'duples' in n: kind = 'duplex_receptacle'
        elif 'single' in n: kind = 'single_receptacle'
        elif 'multi' in n: kind = 'multioutlet_assembly'
        else: kind = _slug(name, 5)
        if 'emergency' in n or 'standby' in n: kind += '_emergency'
        elif 'ceiling' in n: kind += '_ceiling'
        elif 'floor' in n: kind += '_floor'
        dtype = 'junction_box' if 'box' in kind else 'receptacle'
    elif section == 'Switches and Sensors':
        if 'occupancy sensor' in n: kind, dtype = 'occupancy_sensor', 'occupancy_sensor'
        else: kind, dtype = _slug(name, 6), 'switch'
    elif section == 'Luminaires':
        if 'exit' in n: kind = 'exit_sign'
        elif 'emergency battery' in n: kind = 'emergency_battery_light'
        elif 'spotlight' in n: kind = 'spotlight'
        elif 'strip' in n: kind = 'strip_luminaire'
        elif 'track' in n: kind = 'track_light'
        elif 'wallpack' in n: kind = 'wallpack'
        elif 'pole' in n: kind = 'pole_luminaire'
        else: kind = _slug(name, 6)
        dtype = 'light_fixture'
    elif section.startswith('Fire Alarm') or section == 'Annex A - Alternate Fire Safety':
        if any(w in n for w in ('annunciator', 'control panel', 'communicator', 'transponder')):
            dtype = 'fire_alarm_control'
        elif any(w in n for w in ('horn', 'strobe', 'bell', 'gong', 'speaker', 'beacon', 'light / signal')):
            dtype = 'fire_alarm_notification'
        elif any(w in n for w in ('smoke', 'heat detector', 'gas detector', 'flame detector',
                                    'tamper detector', 'flow detector', 'pressure detector')):
            dtype = 'fire_alarm_detector'
        elif 'exit' in n or 'emergency battery-powered light' in n:
            dtype = 'light_fixture'
        else: dtype = 'fire_alarm_device'
        kind = _slug(name, 8)
    elif section == 'Communications - Teledata':
        dtype = 'data_outlet'
        if 'telephone/data' in n: kind = 'telephone_data'
        elif 'telephone' in n: kind = 'telephone'
        else: kind = 'data'
        if 'floor' in n: kind += '_floor'
        elif 'wall' in n: kind += '_wall'
    elif section == 'Communications - Audio-Visual':
        dtype, kind = 'audio_visual_device', _slug(name, 6)
    elif section == 'Raceways - Boxes and Busways':
        dtype = 'junction_box' if 'box' in n else 'service_pole'
        kind = _slug(name, 7)
    else:
        dtype = {
            'security': 'security_device',
            'power_distribution': 'power_distribution_equipment',
            'motor_and_hvac_control': 'motor_control',
            'motor_and_hvac_equipment': 'mechanical_equipment',
            'communications_equipment': 'communications_equipment',
            'nurse_call': 'nurse_call_device',
        }.get(category, category)
        kind = _slug(name, 8)
    return category, kind, dtype, kind


def list_neca_prompt_specs(library_root: Path, *, allow_raster_traces: bool = True):
    """Read prompt candidates from NECA metadata without GPU or rasterization."""
    root = Path(library_root)
    entries = json.loads((root / 'symbols.json').read_text())['symbols']
    alternative_parents = {e['id'].split('__')[0] for e in entries
                           if e.get('kind') == 'printed_alternative'}
    visual_audit = audit_neca_visual_groups(entries)
    found, skipped = [], Counter()
    for e in entries:
        section = e.get('section', '')
        if section not in SECTION_CATEGORIES:
            skipped['non_device_or_modifier'] += 1
            continue
        if any(t in e.get('name', '').lower() for t in NO_DEVICE_TERMS.get(section, ())):
            skipped['routing_or_long_assembly'] += 1
            continue
        if e.get('kind') == 'numbered_row' and e['id'] in alternative_parents:
            skipped['parent_has_individual_alternatives'] += 1
            continue
        if not allow_raster_traces and not e.get('native_vectors', False):
            skipped['raster_traced'] += 1
            continue
        if e.get('kind') == 'annex_a_alternate' and (
                '__basic' in e['id'] or 'basic shape' in e.get('name', '').lower()):
            skipped['generic_basic_shape'] += 1
            continue
        if not (root / e['file']).is_file():
            skipped['missing_svg'] += 1
            continue
        category, subcat, dtype, subtype = _classification(e)
        visual = classify_neca_artwork(e, alternative_parents)
        found.append(SamNecaPrompt(
            e['id'], e.get('code', ''), e.get('name', ''), section,
            category, dtype, subtype, category, subcat, e.get('kind', ''),
            bool(e.get('native_vectors')), e['file'],
            visual_group=visual['visual_group'],
            prompt_role=visual['role'],
            encoded_attributes=visual['attributes']))
    return found, {
        'total_entries': len(entries), 'eligible_entries': len(found),
        'excluded': dict(skipped),
        'eligible_by_category': dict(Counter(x.category for x in found)),
        'unique_subcategories': len({(x.category, x.subcategory) for x in found}),
        'visual_shape_groups': len({x.visual_group for x in found if x.prompt_role=='base'}),
        'reference_roles': dict(Counter(x.prompt_role for x in found)),
        'all_source_roles': visual_audit['roles'],
        'all_source_visual_groups': visual_audit['visual_group_count'],
    }



# Prefer common plan devices in the cheap first pass. Every subtype remains in
# the catalog; increasing the budget or using an allowlist restores rare shapes.
COMMON_FIRST = {
    'power_outlet': ('duplex', 'double duplex', 'single', 'floor box', 'special receptacle',
                     'multioutlet', 'multi-outlet', 'junction box'),
    'lighting_control': ('single pole switch', 'three way switch', 'dimmer switch',
                         'occupancy sensor', 'four way switch', 'double pole switch',
                         'low voltage switch', 'timer switch'),
    'lighting_fixture': ('luminaire:', 'strip', 'exit luminaire', 'spotlight',
                         'emergency battery', 'wallpack', 'pole mounted'),
    'fire_alarm_detection': ('smoke detector.', 'manual pull station', 'duct smoke',
                             'automatic heat', 'beam smoke', 'carbon monoxide',
                             'tamper switch', 'water flow'),
    'fire_alarm_notification': ('horn and strobe', 'strobe unit', 'horn unit',
                                'speaker and strobe', 'bell and strobe', 'door holder'),
    'fire_alarm_control': ('fire alarm control panel', 'annunciator panel',
                           'individual addressable module', 'voice evacuation',
                           'transponder', 'fire alarm master box'),
    'telecom_outlet': ('data outlet.', 'telephone/data outlet', 'telephone outlet'),
    'security': ('camera', 'card reader', 'motion detector', 'security door contacts',
                'security control panel', 'electric door strike'),
    'power_distribution': ('lighting or power panel', 'distribution panel',
                           'transformer', 'generator', 'transfer switch'),
    'motor_and_hvac_control': ('disconnect switch', 'magnetic motor starter',
                               'adjustable speed drive', 'thermostat', 'photocell',
                               'flow switch', 'pressure switch'),
    'motor_and_hvac_equipment': ('motor ', 'ceiling fan', 'wall fan',
                                 'motorized damper', 'heater'),
    'junction_and_service_box': ('junction box', 'pull box', 'power pole', 'telecom pole'),
    'audio_visual': ('speaker', 'intercom', 'cable antenna', 'microphone', 'volume control'),
    'communications_equipment': ('equipment rack', 'equipment cabinet', 'terminal cabinet'),
    'nurse_call': ('nurse call', 'emergency pull cord', 'dome light',
                   'duty station', 'staff station'),
    'fire_safety_alternative': ('photoelectric smoke detector', 'pull station',
                                'heat detector', 'speaker/horn', 'illuminated exit',
                                'fire alarm control panel'),
}


def _common_rank(category: str, name: str) -> int:
    name = name.lower()
    for i, phrase in enumerate(COMMON_FIRST.get(category, ())):
        if phrase in name:
            return i
    return 1000


def choose_neca_prompts(specs: list[SamNecaPrompt], *, per_subcategory: int = 2,
                        per_category: int = 16, total: int = 192,
                        allowlist: set[str] | None = None) -> list[SamNecaPrompt]:
    """Fair category/subcategory round robin; 0 disables each budget.

    Explicit allowlists bypass budgets; users can run a full slow pass for
    rare device types without sacrificing the default fast pass.
    """
    if min(per_subcategory, per_category, total) < 0:
        raise ValueError('prompt budgets must be nonnegative')
    if allowlist is not None:
        unknown = allowlist - {x.symbol_id for x in specs}
        if unknown: raise ValueError(f'unavailable NECA prompts: {sorted(unknown)[:10]}')
        if not allowlist: raise ValueError('empty prompt allowlist')
        return [x for x in specs if x.symbol_id in allowlist]
    by_cat = defaultdict(lambda: defaultdict(list))
    for x in specs: by_cat[x.category][x.subcategory].append(x)
    if not by_cat: raise ValueError('no eligible NECA prompts')
    if total and total < len(by_cat):
        raise ValueError('total budget would exclude at least one category')
    queues = {}
    for cat, subs in by_cat.items():
        for entries in subs.values():
            entries.sort(key=lambda x: (not x.native_vectors,
                                        x.kind != 'printed_alternative', x.symbol_id))
        queue = []
        for round_id in range(max(len(v) for v in subs.values())):
            if per_subcategory and round_id >= per_subcategory: break
            priority_subs = sorted(subs,
                                   key=lambda sub: (_common_rank(cat, subs[sub][0].name), sub))
            for sub in priority_subs:
                if round_id < len(subs[sub]): queue.append(subs[sub][round_id])
        queues[cat] = queue[:per_category] if per_category else queue
    chosen = []
    for depth in range(max(len(v) for v in queues.values())):
        for cat in sorted(queues):
            if depth < len(queues[cat]):
                chosen.append(queues[cat][depth])
                if total and len(chosen) >= total: return chosen
    return chosen


def load_neca_sam_prompts(
    specs: list[SamNecaPrompt], root: Path, *,
    render_height: int = 96,
    cache_dir: Path | None = None,
    check_quality: bool = True,
    reference_stats: Counter | None = None,
) -> list[SamNecaPrompt]:
    """Rasterize selected exemplars with content-addressed local PNG caching.

    Quality checks are applied after loading the cache, so tuning the gate
    doesn't require rerasterizing every vector source. An immutable SVG
    content digest protects against changed underlying NECA glyphs.
    """
    import cv2
    from blueprint_symbols.neca_svg_compositor import _rasterize_svg_alpha
    from blueprint_symbols.reference_quality import reference_quality_reason

    root = Path(root)
    if cache_dir is not None:
        cache_dir = Path(cache_dir)
        cache_dir.mkdir(parents=True, exist_ok=True)
    result = []
    for p in specs:
        svg_path = root / p.file
        if not svg_path.is_file():
            if reference_stats is not None:
                reference_stats['missing_source'] += 1
            continue
        cached = None
        if cache_dir is not None:
            digest = hashlib.sha256(
                b'neca-alpha-v1/' + str(render_height).encode() + b'/' +
                svg_path.read_bytes()
            ).hexdigest()
            cached = cache_dir / (digest + '.png')
        alpha = None
        if cached is not None and cached.is_file():
            alpha = cv2.imread(str(cached), cv2.IMREAD_GRAYSCALE)
            if alpha is not None and reference_stats is not None:
                reference_stats['raster_cache_hits'] += 1
        if alpha is None:
            alpha = _rasterize_svg_alpha(svg_path, render_height=render_height)
            if alpha is not None and cached is not None:
                temp = cached.with_name(f'{cached.stem}.{os.getpid()}.tmp.png')
                if cv2.imwrite(str(temp), alpha):
                    temp.replace(cached)
            if reference_stats is not None:
                reference_stats['rasterized'] += 1
        if alpha is None:
            if reference_stats is not None:
                reference_stats['unrenderable'] += 1
            continue
        if check_quality:
            reason = reference_quality_reason(alpha)
            if reason is not None:
                if reference_stats is not None:
                    reference_stats['reference_rejected_' + reason] += 1
                continue
        result.append(replace(p, alpha=alpha))
    return result
