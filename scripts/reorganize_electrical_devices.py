#!/usr/bin/env python3
"""
Reorganize Extracted Devices into Pure Electrical Disciplines.

1. Removes all non-electrical items (plumbing fixtures, piping specialties,
   manual duct dampers, VAV boxes, physical fire extinguishers, key vaults).
2. Keeps every single electrical device that was extracted (104 devices across 11 categories).
3. Reorganizes them into standard electrical engineering divisions / categories:
   - Power Receptacles & Wiring Devices
   - Lighting Controls & Wall Switches
   - Lighting Fixtures & Emergency Egress
   - Occupancy, Daylight & Environmental Sensors
   - Electrical Distribution & Raceways
   - Disconnect Switches & Safety Switches
   - Fire Alarm & Life Safety Systems
   - Access Control & Physical Security
   - Video Surveillance (CCTV)
   - Telecommunications, Data & Network
   - Audio / Visual, Paging & Intercom
4. Produces:
   - scripts/electrical_devices.json
   - scripts/electrical_devices.md
"""

import json
import os
import sys

# Non-electrical items to remove
NON_ELECTRICAL_ITEMS = {
    # Plumbing & Piping
    "Floor Drain",
    "Roof Drain",
    "Cleanout - Floor",
    "Cleanout - Wall",
    "Hose Bibb",
    "Wall Hydrant",
    "Backflow Preventer",
    "Water Heater",
    "Fire Sprinkler Head",
    # Mechanical Sheet Metal & Pure Ductwork Equipment
    "Fire Damper",
    "Volume Control Damper",
    "Variable Air Volume (VAV) Terminal Unit",
    "Smoke Damper",
    "Combination Fire / Smoke Damper",
    "Exhaust Fan",
    "Electric Unit Heater",
    "Motorized Control Damper",
    # Architectural / Physical Storage Equipment
    "Fire Extinguisher / Cabinet",
    "Knox Box (Key Vault)",
}

# New Category Mapping for every kept electrical device
CATEGORY_MAPPING = {
    # 1. Power Receptacles & Wiring Devices
    "Receptacle - Duplex": "Power Receptacles & Wiring Devices",
    "Receptacle - Quadruplex": "Power Receptacles & Wiring Devices",
    "Receptacle - Simplex / Single": "Power Receptacles & Wiring Devices",
    "Receptacle - Special Purpose / Dedicated Voltage": "Power Receptacles & Wiring Devices",
    "Receptacle - Duplex Above Counter": "Power Receptacles & Wiring Devices",
    "Receptacle - GFCI Duplex": "Power Receptacles & Wiring Devices",
    "Receptacle - Floor Mounted": "Power Receptacles & Wiring Devices",
    "Receptacle - USB Charger Duplex": "Power Receptacles & Wiring Devices",
    "Receptacle - Isolated Ground": "Power Receptacles & Wiring Devices",
    "Receptacle - Weatherproof GFCI Duplex": "Power Receptacles & Wiring Devices",
    "Receptacle - Ceiling Mounted": "Power Receptacles & Wiring Devices",
    "Receptacle - Tamper Resistant": "Power Receptacles & Wiring Devices",

    # 2. Lighting Controls & Wall Switches
    "Switch - Single Pole": "Lighting Controls & Wall Switches",
    "Switch - 3-Way": "Lighting Controls & Wall Switches",
    "Switch - 4-Way": "Lighting Controls & Wall Switches",
    "Switch - Dimmer": "Lighting Controls & Wall Switches",
    "Switch - 3-Way Dimmer": "Lighting Controls & Wall Switches",
    "Switch - Key Operated": "Lighting Controls & Wall Switches",
    "Switch - Momentary": "Lighting Controls & Wall Switches",
    "Switch - Time Switch / Timer": "Lighting Controls & Wall Switches",
    "Emergency Power Off (EPO) Pushbutton": "Lighting Controls & Wall Switches",

    # 3. Disconnect Switches & Safety Switches
    "Disconnect Switch": "Disconnect Switches & Safety Switches",
    "Disconnect Switch - Fused": "Disconnect Switches & Safety Switches",
    "Disconnect Switch - Non-Fused": "Disconnect Switches & Safety Switches",

    # 4. Lighting Fixtures & Emergency Egress
    "Light Fixture - Recessed Downlight": "Lighting Fixtures & Emergency Egress",
    "Light Fixture - Linear / Strip": "Lighting Fixtures & Emergency Egress",
    "Light Fixture - Wall Mounted": "Lighting Fixtures & Emergency Egress",
    "Light Fixture - Pole / Floodlight": "Lighting Fixtures & Emergency Egress",
    "Exit Sign": "Lighting Fixtures & Emergency Egress",
    "Exit Sign - Wall Mounted": "Lighting Fixtures & Emergency Egress",
    "Exit Sign - Ceiling Mounted": "Lighting Fixtures & Emergency Egress",
    "Emergency Battery Light Unit": "Lighting Fixtures & Emergency Egress",
    "Lighting Control Panel": "Lighting Fixtures & Emergency Egress",

    # 5. Occupancy, Daylight & Environmental Sensors
    "Occupancy Sensor": "Occupancy, Daylight & Environmental Sensors",
    "Occupancy Sensor - Ceiling Mounted": "Occupancy, Daylight & Environmental Sensors",
    "Occupancy Sensor - Wall Mounted": "Occupancy, Daylight & Environmental Sensors",
    "Vacancy Sensor": "Occupancy, Daylight & Environmental Sensors",
    "Daylight Photosensor / Photocell": "Occupancy, Daylight & Environmental Sensors",
    "Thermostat": "Occupancy, Daylight & Environmental Sensors",
    "Humidistat": "Occupancy, Daylight & Environmental Sensors",
    "Carbon Dioxide (CO2) Sensor": "Occupancy, Daylight & Environmental Sensors",
    "Carbon Monoxide (CO) Sensor": "Occupancy, Daylight & Environmental Sensors",
    "Differential Pressure Sensor": "Occupancy, Daylight & Environmental Sensors",

    # 6. Electrical Distribution & Raceways
    "Junction Box": "Electrical Distribution & Raceways",
    "Pull Box": "Electrical Distribution & Raceways",
    "Panelboard": "Electrical Distribution & Raceways",
    "Switchboard / Switchgear": "Electrical Distribution & Raceways",
    "Transformer": "Electrical Distribution & Raceways",
    "Automatic Transfer Switch (ATS)": "Electrical Distribution & Raceways",
    "Variable Frequency Drive (VFD)": "Electrical Distribution & Raceways",
    "Motor Starter / Controller": "Electrical Distribution & Raceways",

    # 7. Fire Alarm & Life Safety Systems
    "Smoke Detector": "Fire Alarm & Life Safety Systems",
    "Smoke Detector - Duct": "Fire Alarm & Life Safety Systems",
    "Smoke Detector - Ceiling Mounted": "Fire Alarm & Life Safety Systems",
    "Smoke Detector with Sounder Base": "Fire Alarm & Life Safety Systems",
    "Heat Detector": "Fire Alarm & Life Safety Systems",
    "Heat Detector - Rate of Rise": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Manual Pull Station": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Control Panel (FACP)": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Annunciator Panel": "Fire Alarm & Life Safety Systems",
    "Voice Evacuation Panel": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Strobe": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Strobe - Wall Mounted": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Strobe - Ceiling Mounted": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Horn / Strobe": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Horn / Strobe - Wall Mounted": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Horn / Strobe - Ceiling Mounted": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Speaker / Strobe": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Speaker / Strobe - Wall Mounted": "Fire Alarm & Life Safety Systems",
    "Fire Alarm Speaker / Strobe - Ceiling Mounted": "Fire Alarm & Life Safety Systems",
    "Sprinkler Waterflow Switch": "Fire Alarm & Life Safety Systems",
    "Sprinkler Tamper Switch": "Fire Alarm & Life Safety Systems",

    # 8. Access Control & Physical Security
    "Card Reader": "Access Control & Physical Security",
    "Card Reader - Long Range": "Access Control & Physical Security",
    "Card Reader - Proximity": "Access Control & Physical Security",
    "Security Keypad": "Access Control & Physical Security",
    "Electric Door Strike": "Access Control & Physical Security",
    "Magnetic Door Lock": "Access Control & Physical Security",
    "Request to Exit (REX) Device": "Access Control & Physical Security",
    "Door Release Button": "Access Control & Physical Security",
    "Door Contact Switch": "Access Control & Physical Security",
    "Glass Break Detector": "Access Control & Physical Security",
    "Panic / Duress Button": "Access Control & Physical Security",
    "Security Alarm Strobe": "Access Control & Physical Security",
    "Gate Operator": "Access Control & Physical Security",
    "Gate Release Button": "Access Control & Physical Security",

    # 9. Video Surveillance (CCTV)
    "Security Camera": "Video Surveillance (CCTV)",
    "Security Camera - Mini Dome": "Video Surveillance (CCTV)",
    "Security Camera - PTZ": "Video Surveillance (CCTV)",
    "Digital Video Recorder (DVR / NVR)": "Video Surveillance (CCTV)",

    # 10. Telecommunications, Data & Network
    "Data Outlet - Wall Mounted": "Telecommunications, Data & Network",
    "Data Outlet - Floor Mounted": "Telecommunications, Data & Network",
    "Data Outlet - Ceiling Mounted": "Telecommunications, Data & Network",
    "Combination Voice / Data Outlet": "Telecommunications, Data & Network",
    "Telephone Outlet": "Telecommunications, Data & Network",
    "Wireless Access Point (WAP)": "Telecommunications, Data & Network",
    "Television / CATV Outlet": "Telecommunications, Data & Network",
    "Fiber Optic Outlet": "Telecommunications, Data & Network",

    # 11. Audio / Visual, Paging & Intercom
    "Speaker - Ceiling Mounted": "Audio / Visual, Paging & Intercom",
    "Speaker - Wall Mounted": "Audio / Visual, Paging & Intercom",
    "Intercom Station - Weatherproof": "Audio / Visual, Paging & Intercom",
    "Intercom Substation / Push Button": "Audio / Visual, Paging & Intercom",
    "Intercom Master Station": "Audio / Visual, Paging & Intercom",
    "Clock Outlet / Location": "Audio / Visual, Paging & Intercom",
}

def main():
    src_json_path = "scripts/extracted_legend_devices.json"
    out_json_path = "scripts/electrical_devices.json"
    out_md_path = "scripts/electrical_devices.md"

    if not os.path.exists(src_json_path):
        print(f"Error: {src_json_path} not found.")
        sys.exit(1)

    with open(src_json_path, "r", encoding="utf-8") as f:
        orig_data = json.load(f)

    # Re-categorize all kept devices
    new_categories = {
        "Power Receptacles & Wiring Devices": [],
        "Lighting Controls & Wall Switches": [],
        "Disconnect Switches & Safety Switches": [],
        "Lighting Fixtures & Emergency Egress": [],
        "Occupancy, Daylight & Environmental Sensors": [],
        "Electrical Distribution & Raceways": [],
        "Fire Alarm & Life Safety Systems": [],
        "Access Control & Physical Security": [],
        "Video Surveillance (CCTV)": [],
        "Telecommunications, Data & Network": [],
        "Audio / Visual, Paging & Intercom": [],
    }

    total_kept = 0
    removed_items_list = []

    for orig_cat, devices in orig_data["categorized_devices"].items():
        for d in devices:
            dev_type = d["device_type"]
            if dev_type in NON_ELECTRICAL_ITEMS:
                removed_items_list.append({
                    "device_type": dev_type,
                    "original_category": orig_cat,
                    "source_count": d["source_count"],
                    "reason": "Non-electrical (plumbing, mechanical duct/piping, or architectural cabinet)"
                })
                continue

            target_cat = CATEGORY_MAPPING.get(dev_type)
            if not target_cat:
                print(f"Warning: {dev_type} not in CATEGORY_MAPPING!")
                continue

            # Update category on device object
            d_updated = dict(d)
            d_updated["category"] = target_cat
            new_categories[target_cat].append(d_updated)
            total_kept += 1

    # Sort devices within each category by source_count descending
    for cat in new_categories:
        new_categories[cat].sort(key=lambda x: x["source_count"], reverse=True)

    # Filter single-source excluded items (keep only electrical ones in the audit log)
    excluded_electrical_single_source = []
    for item in orig_data.get("excluded_single_source_devices", []):
        dev_type = item["device_type"]
        if dev_type not in NON_ELECTRICAL_ITEMS:
            excluded_electrical_single_source.append(item)

    # Build summary
    summary = {
        "total_pdfs_scanned": orig_data["summary"]["total_pdfs_scanned"],
        "total_legend_sheets_found": orig_data["summary"]["total_legend_sheets_found"],
        "min_sources_threshold": orig_data["summary"]["min_sources_threshold"],
        "total_electrical_devices_kept": total_kept,
        "total_non_electrical_items_removed": len(removed_items_list),
        "total_single_source_electrical_excluded": len(excluded_electrical_single_source),
        "categories_count": len(new_categories),
        "categories_breakdown": {cat: len(devs) for cat, devs in new_categories.items()}
    }

    output_payload = {
        "summary": summary,
        "categories": list(new_categories.keys()),
        "categorized_devices": new_categories,
        "removed_non_electrical_items": removed_items_list,
        "excluded_single_source_electrical_devices": excluded_electrical_single_source
    }

    # Write JSON
    with open(out_json_path, "w", encoding="utf-8") as f:
        json.dump(output_payload, f, indent=2)
    print(f"Wrote electrical JSON to {out_json_path} ({total_kept} devices kept, {len(removed_items_list)} removed)")

    # Generate Markdown
    md_lines = [
        "# Curated Construction Electrical Devices & Categories",
        "",
        f"**Total Blueprint PDFs Scanned:** {summary['total_pdfs_scanned']}  ",
        f"**Device Legend Sheets Analyzed:** {summary['total_legend_sheets_found']}  ",
        f"**Cross-PDF Filter Threshold:** Minimum {summary['min_sources_threshold']} distinct PDF legends  ",
        f"**Total Curated Electrical Device Types:** {total_kept}  ",
        f"**Non-Electrical Items Removed:** {len(removed_items_list)}  ",
        "",
        "## Category Summary",
        "",
        "| Category | Device Types Count |",
        "| :--- | :---: |",
    ]

    for cat, devs in new_categories.items():
        md_lines.append(f"| {cat} | {len(devs)} |")
    md_lines.append(f"| **Total** | **{total_kept}** |")

    md_lines.extend([
        "",
        "---",
        "",
        "## Curated Electrical Device Types by Category",
        ""
    ])

    for cat, devs in new_categories.items():
        md_lines.extend([
            f"### {cat} ({len(devs)} types)",
            "",
            "| Specific Device Type | Source Count | Sample Raw Legend Texts | Sample PDF Sources |",
            "| :--- | :---: | :--- | :--- |"
        ])
        for d in devs:
            raw_sample = "; ".join(f"`{s}`" for s in d["sample_legend_texts"][:2])
            sources_sample = ", ".join(d["sources"][:3])
            if len(d["sources"]) > 3:
                sources_sample += f" (+{len(d['sources']) - 3} more)"
            md_lines.append(f"| **{d['device_type']}** | {d['source_count']} | {raw_sample} | {sources_sample} |")
        md_lines.append("")

    # Removed Items Table
    md_lines.extend([
        "---",
        "",
        "## Removed Non-Electrical Items",
        "",
        "> [!NOTE]",
        "> The following items were identified in multi-trade (MEPT) legends but removed because they are non-electrical (plumbing fixtures, piping valves, mechanical sheet metal ductwork, and architectural cabinets).",
        "",
        "| Removed Item | Original Category | Source Count | Reason Removed |",
        "| :--- | :--- | :---: | :--- |"
    ])
    for r in removed_items_list:
        md_lines.append(f"| {r['device_type']} | {r['original_category']} | {r['source_count']} | {r['reason']} |")

    # Excluded Single-Source Table
    md_lines.extend([
        "",
        "---",
        "",
        "## Excluded Single-Source Electrical Items (Audit Log)",
        "",
        "> [!NOTE]",
        "> These electrical items appeared in only a single PDF legend and were excluded per the multi-source requirement.",
        "",
        "| Excluded Device Type | Category | Source PDF | Raw Text |",
        "| :--- | :--- | :--- | :--- |"
    ])
    for ex in excluded_electrical_single_source:
        pdf_name = ex["sources"][0] if ex["sources"] else "Unknown"
        sample_txt = f"`{ex['sample_legend_texts'][0]}`" if ex["sample_legend_texts"] else ""
        md_lines.append(f"| {ex['device_type']} | {ex['category']} | {pdf_name} | {sample_txt} |")

    md_lines.append("")

    with open(out_md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md_lines))
    print(f"Wrote Markdown report to {out_md_path}")

if __name__ == "__main__":
    main()
