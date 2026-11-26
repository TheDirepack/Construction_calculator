#!/usr/bin/env python3
"""
Extract device types and categories from construction blueprint device legends.

Scans local blueprint PDFs for legend sheets (Device Legend, Symbol Legend,
Electrical Legend, Security Symbols, Fire Alarm Legend, Mechanical Symbols, etc.),
extracts specific device types preserving fine distinctions (e.g., 'Card Reader'
vs 'Card Reader - Long Range' vs 'Card Reader - Proximity'), categorizes each device,
and filters by cross-document occurrence frequency (excluding one-offs that appear
in only a single PDF, while including devices appearing in 2, 3, or more PDFs).

Usage:
    python scripts/extract_legend_device_types.py [options]

Options:
    --pdf-dir DIR        Root directory to scan for blueprint PDFs
                         (default: blueprints_and_libraries)
    --min-sources N      Minimum distinct PDF sources required for inclusion (default: 2)
    --output JSON_PATH   Path to write JSON results (default: scripts/extracted_legend_devices.json)
    --markdown MD_PATH   Path to write Markdown summary (default: scripts/extracted_legend_devices.md)
    --verbose            Enable detailed logging
"""

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from typing import Any, Dict, List, Optional, Set, Tuple

import fitz  # PyMuPDF


# ---------------------------------------------------------------------------
# Legend Sheet Detection Regexes
# ---------------------------------------------------------------------------

LEGEND_TITLE_RE = re.compile(
    r"(device\s+legend|"
    r"symbol\s+legend|"
    r"electrical\s+(symbol\s+)?legend|"
    r"symbols\s+(&|and)\s+abbreviations|"
    r"legend\s+(&|and)\s+abbreviations|"
    r"security\s+(&\s+comm\.\s+)?(legend|symbols|notes)|"
    r"security\s+symbols(\s+list)?|"
    r"fire\s+alarm\s+(symbol\s+)?legend|"
    r"fire\s+alarm\s+(&\s+detection\s+)?symbols(\s+list)?|"
    r"mechanical\s+(symbol\s+)?legend|"
    r"mechanical\s+symbols(\s+list)?|"
    r"plumbing\s+symbols(\s+list)?|"
    r"electrical\s+symbols\s+list|"
    r"wiring\s+devices|"
    r"lighting\s+symbols(\s+list)?|"
    r"standard\s+symbols\s+electrical|"
    r"access\s+control\s+legend|"
    r"communications?\s+symbols?)",
    re.IGNORECASE,
)

# Directories to skip (non-construction blueprints, scans of antique books, 3D meshes)
SKIP_DIR_KEYWORDS = [
    "internet_archive",
    "wikihouse",
    "opendesk",
    "cad_library",
    "open_source_ecology",
    "aker_colorado",
]

# Lines that indicate title blocks, notes, boilerplate, or code citations (not devices)
BOILERPLATE_LINE_RE = re.compile(
    r"^(project|date|scale|client|architect|engineer|consultant|drawn\s+by|chked\s+by|"
    r"revisions?|sheet\s+index|sheet\s+name|sheet\s+number|not\s+for\s+construction|"
    r"all\s+work\s+shall|general\s+notes?|california\s+code|building\s+code|"
    r"national\s+electrical\s+code|keyed\s+notes?|contractor\s+shall|"
    r"provide\s+and\s+install|refer\s+to\s+specifications|unless\s+otherwise\s+noted|"
    r"all\s+circuits|finish\s+floor|maximum\s+reach|mounting\s+height|"
    r"all\s+devices|section|detail|callout|legend\s+notes|title\s+24|copyright|"
    r"standard\s+plans|page\s+\d+|sheet\s+[a-z0-9\.\-]+|\d+\s+of\s+\d+|"
    r"www\.[a-z0-9\-]+\.com|telephone\s+terminal\s+cabinet\s+note|"
    r"coordinate\s+all|furnish\s+and\s+install|these\s+notes\s+apply)\b",
    re.IGNORECASE,
)


# ---------------------------------------------------------------------------
# Device Normalization Rules
# Preserves specifics (e.g., 'Card Reader' vs 'Card Reader - Long Range'
# vs 'Card Reader - Proximity' vs 'Card Reader - Mullion Mount').
# Order matters: more specific patterns come before general patterns.
# ---------------------------------------------------------------------------

SPECIFIC_RULES: List[Tuple[re.Pattern, str, str]] = [
    # ---------------- Access Control & Security ----------------
    (re.compile(r"\bcard\s+reader\b.*?\blong\s+range\b|\blong\s+range\b.*?\bcard\s+reader\b", re.I),
     "Card Reader - Long Range", "Access Control & Security"),
    (re.compile(r"\bproximity\s+card\s+reader\b|\bprox\s+card\s+reader\b", re.I),
     "Card Reader - Proximity", "Access Control & Security"),
    (re.compile(r"\bmullion(\s+mount)?\s+card\s+reader\b", re.I),
     "Card Reader - Mullion Mount", "Access Control & Security"),
    (re.compile(r"\bcard\s+reader\b.*?\bkeypad\b|\bkeypad\b.*?\bcard\s+reader\b", re.I),
     "Card Reader with Keypad", "Access Control & Security"),
    (re.compile(r"\b(smart\s+card|contactless\s+smart\s+card|biometric)\s+reader\b", re.I),
     "Card Reader - Biometric / Smart Card", "Access Control & Security"),
    (re.compile(r"\b(door\s+)?card\s+reader\b|\bsecurity\s+card\s+reader\b", re.I),
     "Card Reader", "Access Control & Security"),
    (re.compile(r"\brequest\s+to\s+exit\b.*?\b(motion|sensor)\b|\b(wall|ceiling).*?request\s+to\s+exit\b", re.I),
     "Request to Exit (REX) Sensor", "Access Control & Security"),
    (re.compile(r"\brequest\s+to\s+exit\b|\brex\s+device\b", re.I),
     "Request to Exit (REX) Device", "Access Control & Security"),
    (re.compile(r"\bpush\s+to\s+exit\b|\bexit\s+push\s+button\b", re.I),
     "Push to Exit Button", "Access Control & Security"),
    (re.compile(r"\bdoor\s+contact(\s+switch)?\b|\bsecurity\s+door\s+contact\b|\bmagnetic\s+door\s+contact\b", re.I),
     "Door Contact Switch", "Access Control & Security"),
    (re.compile(r"\belectric(ified)?\s+(door\s+)?strike\b", re.I),
     "Electric Door Strike", "Access Control & Security"),
    (re.compile(r"\bmagnetic\s+(door\s+)?lock\b|\bmag\s+lock\b", re.I),
     "Magnetic Door Lock", "Access Control & Security"),
    (re.compile(r"\bdoor\s+release\s+(control\s+)?button\b", re.I),
     "Door Release Button", "Access Control & Security"),
    (re.compile(r"\bgate\s+release\s+(control\s+)?button\b", re.I),
     "Gate Release Button", "Access Control & Security"),
    (re.compile(r"\bpanic\s+(call\s+)?(control\s+)?button\b|\bduress\s+button\b|\bpanic\s+button\b", re.I),
     "Panic / Duress Button", "Access Control & Security"),
    (re.compile(r"\bglass\s+break\s+detector\b|\bglass\s+break\s+sensor\b|\bglass\s+break\b", re.I),
     "Glass Break Detector", "Access Control & Security"),
    (re.compile(r"\bmini\s+dome\s+camera\b|\bdome\s+camera\b", re.I),
     "Security Camera - Mini Dome", "Access Control & Security"),
    (re.compile(r"\bptz\s+camera\b|\bcamera\b.*?\bpan\s*,\s*tilt\b", re.I),
     "Security Camera - PTZ", "Access Control & Security"),
    (re.compile(r"\b360\s*(deg)?\s+camera\b|\bfish-?eye\s+camera\b|\bmulti\s+lens\s+camera\b", re.I),
     "Security Camera - 360 / Multi-Lens", "Access Control & Security"),
    (re.compile(r"\bsecurity\s+camera\b|\bcctv\s+camera\b|\bvideo\s+camera\b", re.I),
     "Security Camera", "Access Control & Security"),
    (re.compile(r"\bsecurity\s+keypad\b|\bkeypad\b", re.I),
     "Security Keypad", "Access Control & Security"),
    (re.compile(r"\bsecurity\s+control\s+panel\b", re.I),
     "Security Control Panel", "Access Control & Security"),
    (re.compile(r"\bdigital\s+video\s+recorder\b|\bdvr\b|\bnvr\b", re.I),
     "Digital Video Recorder (DVR / NVR)", "Access Control & Security"),
    (re.compile(r"\bgate\s+operator\b", re.I),
     "Gate Operator", "Access Control & Security"),
    (re.compile(r"\btelephone\s+entry\s+system\b", re.I),
     "Telephone Entry System", "Access Control & Security"),
    (re.compile(r"\bwander\s+guard\b", re.I),
     "Wander Guard System Device", "Access Control & Security"),
    (re.compile(r"\bpedestal\b.*?\bcard\s+reader\b|\bcard\s+reader\s+pedestal\b", re.I),
     "Card Reader Pedestal", "Access Control & Security"),
    (re.compile(r"\bsecurity\s+alarm\s+strobe\b", re.I),
     "Security Alarm Strobe", "Access Control & Security"),

    # ---------------- Fire Alarm & Life Safety ----------------
    (re.compile(r"\bduct\s+smoke\s+detector\b|\bsmoke\s+detector\s*\(?duct\)?\b", re.I),
     "Smoke Detector - Duct", "Fire Alarm & Life Safety"),
    (re.compile(r"\bsmoke\s+detector\b.*?\b(ceiling|flush)\b", re.I),
     "Smoke Detector - Ceiling Mounted", "Fire Alarm & Life Safety"),
    (re.compile(r"\bsmoke\s+detector\b.*?\bsounder\s+base\b", re.I),
     "Smoke Detector with Sounder Base", "Fire Alarm & Life Safety"),
    (re.compile(r"\bbeam\s+smoke\s+detector\b|\bprojected\s+beam\s+smoke\s+detector\b", re.I),
     "Smoke Detector - Beam", "Fire Alarm & Life Safety"),
    (re.compile(r"\bcombination\s+smoke/co\s+detector\b|\bsmoke/co\s+detector\b", re.I),
     "Combination Smoke / CO Detector", "Fire Alarm & Life Safety"),
    (re.compile(r"\bsmoke\s+detector\b|\bsmoke\s+alarm\b", re.I),
     "Smoke Detector", "Fire Alarm & Life Safety"),
    (re.compile(r"\bheat\s+detector\b.*?\brate\s+of\s+rise\b|\brate\s+of\s+rise\s+heat\s+detector\b", re.I),
     "Heat Detector - Rate of Rise", "Fire Alarm & Life Safety"),
    (re.compile(r"\bheat\s+detector\b.*?\bfixed\s+temp\b|\bfixed\s+temperature\s+heat\s+detector\b", re.I),
     "Heat Detector - Fixed Temperature", "Fire Alarm & Life Safety"),
    (re.compile(r"\bheat\s+detector\b", re.I),
     "Heat Detector", "Fire Alarm & Life Safety"),
    (re.compile(r"\b(fire\s+alarm\s+)?(manual\s+)?pull\s+station\b|\bmanual\s+fire\s+pull\s+box\b", re.I),
     "Fire Alarm Manual Pull Station", "Fire Alarm & Life Safety"),
    (re.compile(r"\bhorn\s*/?\s*strobe\b.*?\bceiling\b|\bceiling\s+mount(ed)?\s+horn\s*/?\s*strobe\b", re.I),
     "Fire Alarm Horn / Strobe - Ceiling Mounted", "Fire Alarm & Life Safety"),
    (re.compile(r"\bhorn\s*/?\s*strobe\b.*?\bwall\b|\bwall\s+mount(ed)?\s+horn\s*/?\s*strobe\b", re.I),
     "Fire Alarm Horn / Strobe - Wall Mounted", "Fire Alarm & Life Safety"),
    (re.compile(r"\bhorn\s*/?\s*strobe\b|\bcombination\s+fire\s+alarm\s+horn\s+and\s+strobe\b", re.I),
     "Fire Alarm Horn / Strobe", "Fire Alarm & Life Safety"),
    (re.compile(r"\bspeaker\s*/?\s*strobe\b.*?\bceiling\b", re.I),
     "Fire Alarm Speaker / Strobe - Ceiling Mounted", "Fire Alarm & Life Safety"),
    (re.compile(r"\bspeaker\s*/?\s*strobe\b.*?\bwall\b", re.I),
     "Fire Alarm Speaker / Strobe - Wall Mounted", "Fire Alarm & Life Safety"),
    (re.compile(r"\b(fire\s+alarm\s+)?speaker\s*/?\s*strobe\b", re.I),
     "Fire Alarm Speaker / Strobe", "Fire Alarm & Life Safety"),
    (re.compile(r"\bstrobe\s+light\b.*?\bceiling\b|\bceiling\s+mount(ed)?\s+strobe\b", re.I),
     "Fire Alarm Strobe - Ceiling Mounted", "Fire Alarm & Life Safety"),
    (re.compile(r"\bstrobe\s+light\b.*?\bwall\b|\bwall\s+mount(ed)?\s+strobe\b", re.I),
     "Fire Alarm Strobe - Wall Mounted", "Fire Alarm & Life Safety"),
    (re.compile(r"\b(fire\s+alarm\s+)?strobe\s+(light|only)\b|\bflashing\s+strobe\s+light\b", re.I),
     "Fire Alarm Strobe", "Fire Alarm & Life Safety"),
    (re.compile(r"\bfire\s+alarm\s+control\s+panel\b|\bfacp\b", re.I),
     "Fire Alarm Control Panel (FACP)", "Fire Alarm & Life Safety"),
    (re.compile(r"\b(fire\s+alarm\s+)?annunciator\s+panel\b|\bfaap\b", re.I),
     "Fire Alarm Annunciator Panel", "Fire Alarm & Life Safety"),
    (re.compile(r"\bvoice\s+evacuation\s+(and\s+messaging\s+)?panel\b", re.I),
     "Voice Evacuation Panel", "Fire Alarm & Life Safety"),
    (re.compile(r"\bsprinkler\s+tamper\s+switch\b|\btamper\s+switch\b", re.I),
     "Sprinkler Tamper Switch", "Fire Alarm & Life Safety"),
    (re.compile(r"\bsprinkler\s+flow\s+switch\b|\bwater\s*flow\s+switch\b", re.I),
     "Sprinkler Waterflow Switch", "Fire Alarm & Life Safety"),
    (re.compile(r"\bfire\s+extinguisher(\s+cabinet)?\b|\bfec\b", re.I),
     "Fire Extinguisher / Cabinet", "Fire Alarm & Life Safety"),
    (re.compile(r"\bknox\s+box\b|\bkey\s+repository\b", re.I),
     "Knox Box (Key Vault)", "Fire Alarm & Life Safety"),

    # ---------------- Power & Receptacles ----------------
    (re.compile(r"\bduplex\s+(wall\s+)?receptacle\b.*?\bcounter\b", re.I),
     "Receptacle - Duplex Above Counter", "Power & Receptacles"),
    (re.compile(r"\bduplex\s+(wall\s+)?receptacle\b.*?\bweatherproof\b|\bweatherproof\s+duplex\s+receptacle\b", re.I),
     "Receptacle - Weatherproof Duplex", "Power & Receptacles"),
    (re.compile(r"\bgfci\s+duplex\s+receptacle\b|\bduplex\s+ground\s+fault\s+circuit\s+interrupter\s+receptacle\b|\bduplex\s+gfci\b", re.I),
     "Receptacle - GFCI Duplex", "Power & Receptacles"),
    (re.compile(r"\bweatherproof\s+ground\s+fault\b|\bgfci.*?weatherproof\b|\bweatherproof.*?gfci\b", re.I),
     "Receptacle - Weatherproof GFCI Duplex", "Power & Receptacles"),
    (re.compile(r"\bquadruplex\s+receptacle\b|\bquad\s+receptacle\b|\bdouble\s+duplex\s+receptacle\b", re.I),
     "Receptacle - Quadruplex", "Power & Receptacles"),
    (re.compile(r"\bfloor\s+(mounted\s+)?(duplex\s+)?receptacle\b|\bflush\s+floor\s+duplex\s+receptacle\b", re.I),
     "Receptacle - Floor Mounted", "Power & Receptacles"),
    (re.compile(r"\bceiling\s+(mounted\s+)?receptacle\b", re.I),
     "Receptacle - Ceiling Mounted", "Power & Receptacles"),
    (re.compile(r"\b(duplex\s+)?receptacle\b.*?\busb\b|\busb\s+receptacle\b", re.I),
     "Receptacle - USB Charger Duplex", "Power & Receptacles"),
    (re.compile(r"\btamper-?resistant\s+receptacle\b", re.I),
     "Receptacle - Tamper Resistant", "Power & Receptacles"),
    (re.compile(r"\bisolated\s+ground\s+receptacle\b|\big\s+receptacle\b", re.I),
     "Receptacle - Isolated Ground", "Power & Receptacles"),
    (re.compile(r"\bcontrolled\s+receptacle\b|\bsplit-?wired\s+receptacle\b", re.I),
     "Receptacle - Controlled / Split-Wired", "Power & Receptacles"),
    (re.compile(r"\b208v\b.*?\breceptacle\b|\b240v\b.*?\breceptacle\b|\bspecial\s+purpose\s+receptacle\b", re.I),
     "Receptacle - Special Purpose / Dedicated Voltage", "Power & Receptacles"),
    (re.compile(r"\bsimplex\s+receptacle\b|\bsingle\s+receptacle\b", re.I),
     "Receptacle - Simplex / Single", "Power & Receptacles"),
    (re.compile(r"\bduplex\s+(wall\s+)?receptacle\b|\bconvenience\s+receptacle\b|\bduplex\s+outlet\b", re.I),
     "Receptacle - Duplex", "Power & Receptacles"),

    # ---------------- Switches & Lighting Controls ----------------
    (re.compile(r"\bsingle\s+pole\s+keyed\s+switch\b|\bkey-?operated\s+switch\b|\bkeyed\s+switch\b", re.I),
     "Switch - Key Operated", "Switches & Lighting Controls"),
    (re.compile(r"\b3-?way\s+keyed\s+switch\b|\bthree-?way\s+keyed\s+switch\b", re.I),
     "Switch - 3-Way Keyed", "Switches & Lighting Controls"),
    (re.compile(r"\b3-?way\s+(dimmer|dimmable)\s+switch\b", re.I),
     "Switch - 3-Way Dimmer", "Switches & Lighting Controls"),
    (re.compile(r"\b3-?way\s+(toggle\s+)?switch\b|\bthree-?way\s+(toggle\s+)?switch\b", re.I),
     "Switch - 3-Way", "Switches & Lighting Controls"),
    (re.compile(r"\b4-?way\s+(toggle\s+)?switch\b|\bfour-?way\s+(toggle\s+)?switch\b", re.I),
     "Switch - 4-Way", "Switches & Lighting Controls"),
    (re.compile(r"\bdimmer\s+switch\b|\bdimming\s+switch\b|\bwall\s+box\s+dimmer\b", re.I),
     "Switch - Dimmer", "Switches & Lighting Controls"),
    (re.compile(r"\bpilot\s+light\s+switch\b", re.I),
     "Switch - Pilot Light", "Switches & Lighting Controls"),
    (re.compile(r"\bmomentary\s+(contact\s+)?switch\b", re.I),
     "Switch - Momentary", "Switches & Lighting Controls"),
    (re.compile(r"\bdigital\s+time\s+switch\b|\btime\s+switch\b|\btimer\s+switch\b", re.I),
     "Switch - Time Switch / Timer", "Switches & Lighting Controls"),
    (re.compile(r"\bemergency\s+power\s+off\s+pushbutton\b|\bepo\s+pushbutton\b|\bemergency\s+push\s+button\b", re.I),
     "Emergency Power Off (EPO) Pushbutton", "Switches & Lighting Controls"),
    (re.compile(r"\bfused\s+disconnect(ing\s+means)?\s+switch\b", re.I),
     "Disconnect Switch - Fused", "Switches & Lighting Controls"),
    (re.compile(r"\bunfused\s+disconnect\b|\bnon-?fusible\s+disconnect\b", re.I),
     "Disconnect Switch - Non-Fused", "Switches & Lighting Controls"),
    (re.compile(r"\bdisconnect(ing)?\s+switch\b|\bdisconnecting\s+means\b", re.I),
     "Disconnect Switch", "Switches & Lighting Controls"),
    (re.compile(r"\bsingle\s+pole\s+(toggle\s+)?switch\b|\bwall\s+switch\b|\btoggle\s+switch\b", re.I),
     "Switch - Single Pole", "Switches & Lighting Controls"),

    # ---------------- Occupancy & Lighting Sensors ----------------
    (re.compile(r"\bceiling\s+mount(ed)?\s+occupancy\s+sensor\b", re.I),
     "Occupancy Sensor - Ceiling Mounted", "Occupancy & Environmental Sensors"),
    (re.compile(r"\bwall\s+mount(ed)?\s+occupancy\s+sensor\b|\bwall\s+mounted\s+momentary\s+occupancy\s+sensor\b", re.I),
     "Occupancy Sensor - Wall Mounted", "Occupancy & Environmental Sensors"),
    (re.compile(r"\boccupancy\s+sensor\s+wall\s+switch\b", re.I),
     "Occupancy Sensor Wall Switch", "Occupancy & Environmental Sensors"),
    (re.compile(r"\bvacancy\s+sensor\b", re.I),
     "Vacancy Sensor", "Occupancy & Environmental Sensors"),
    (re.compile(r"\bdaylight\s+photo\s*sensor\b|\bphotoelectric\s+control\b|\bphotocell\b", re.I),
     "Daylight Photosensor / Photocell", "Occupancy & Environmental Sensors"),
    (re.compile(r"\boccupancy\s+sensor\b", re.I),
     "Occupancy Sensor", "Occupancy & Environmental Sensors"),

    # ---------------- Lighting Fixtures & Egress ----------------
    (re.compile(r"\bceiling\s+mount(ed)?\s+exit\s+sign\b|\bsingle\s+face\s+ceiling.*?exit\s+sign\b|\bdouble\s+face\s+ceiling.*?exit\s+sign\b", re.I),
     "Exit Sign - Ceiling Mounted", "Lighting Fixtures & Egress"),
    (re.compile(r"\bwall\s+mount(ed)?\s+exit\s+sign\b", re.I),
     "Exit Sign - Wall Mounted", "Lighting Fixtures & Egress"),
    (re.compile(r"\bexit\s+sign\b|\bexit\s+light\s+fixture\b", re.I),
     "Exit Sign", "Lighting Fixtures & Egress"),
    (re.compile(r"\bemergency\s+battery\s+pack\b|\bemergency\s+light(ing\s+unit)?\b|\bbattery\s+unit\b", re.I),
     "Emergency Battery Light Unit", "Lighting Fixtures & Egress"),
    (re.compile(r"\brecessed\s+troffer\s+fixture\b|\b[12]'x[24]'\s+recessed\s+(troffer|led)\b", re.I),
     "Light Fixture - Recessed Troffer", "Lighting Fixtures & Egress"),
    (re.compile(r"\bsurface\s+mount\s+troffer\b|\b[12]'x[24]'\s+surface\s+mount\b", re.I),
     "Light Fixture - Surface Mount Troffer", "Lighting Fixtures & Egress"),
    (re.compile(r"\brecessed\s+downlight\b|\bdownlight\s+fixture\b|\bcan\s+light\b", re.I),
     "Light Fixture - Recessed Downlight", "Lighting Fixtures & Egress"),
    (re.compile(r"\blinear\s+(light|pendant|recessed|strip)?\s+fixture\b|\bsuspended\s+linear\b", re.I),
     "Light Fixture - Linear / Strip", "Lighting Fixtures & Egress"),
    (re.compile(r"\bwall\s+mount\s+fixture\b|\bwall\s+sconce\b|\bbracket\s+mounted\s+fixture\b", re.I),
     "Light Fixture - Wall Mounted", "Lighting Fixtures & Egress"),
    (re.compile(r"\bpole\s+mount(ed)?\s+(area\s+)?fixture\b|\bparking\s+lot\s+area\s+fixture\b|\bflood\s+light\b", re.I),
     "Light Fixture - Pole / Floodlight", "Lighting Fixtures & Egress"),
    (re.compile(r"\blighting\s+control\s+panel\b|\blcp\b", re.I),
     "Lighting Control Panel", "Lighting Fixtures & Egress"),

    # ---------------- Telecommunications & Data ----------------
    (re.compile(r"\bwireless\s+access\s+point\b|\bwap\b", re.I),
     "Wireless Access Point (WAP)", "Telecommunications & Data"),
    (re.compile(r"\bdata\s+outlet\b.*?\bfloor\b|\bfloor-?mounted\s+data\b", re.I),
     "Data Outlet - Floor Mounted", "Telecommunications & Data"),
    (re.compile(r"\bdata\s+outlet\b.*?\bceiling\b|\bceiling\s+mounted\s+data\b", re.I),
     "Data Outlet - Ceiling Mounted", "Telecommunications & Data"),
    (re.compile(r"\bcombination\s+phone/data\s+outlet\b|\bvoice\s*/?\s*data\s+receptacle\b|\bdata/voip\s+outlet\b", re.I),
     "Combination Voice / Data Outlet", "Telecommunications & Data"),
    (re.compile(r"\bdata\s+outlet\b|\bdata\s+drop\b|\bdata\s+jack\b", re.I),
     "Data Outlet - Wall Mounted", "Telecommunications & Data"),
    (re.compile(r"\btelephone\s+outlet\b|\bphone\s+outlet\b", re.I),
     "Telephone Outlet", "Telecommunications & Data"),
    (re.compile(r"\btelevision\s+outlet\b|\btv\s+outlet\b|\bcatv\s+outlet\b", re.I),
     "Television / CATV Outlet", "Telecommunications & Data"),
    (re.compile(r"\bfiber\s+optic(s)?\s+outlet\b|\bfiber\s+drop\b", re.I),
     "Fiber Optic Outlet", "Telecommunications & Data"),

    # ---------------- Audio / Visual, Paging & Intercom ----------------
    (re.compile(r"\bceiling\s+(mount(ed)?\s+)?speaker\b|\binterior\s+ceiling\s+mounted\s+speaker\b", re.I),
     "Speaker - Ceiling Mounted", "Audio / Visual, Paging & Intercom"),
    (re.compile(r"\bwall\s+(mount(ed)?\s+)?speaker\b|\balarm\s+warning\s+speaker\b", re.I),
     "Speaker - Wall Mounted", "Audio / Visual, Paging & Intercom"),
    (re.compile(r"\bintercom\s+(unit\s+)?\(?weatherproof\)?\b", re.I),
     "Intercom Station - Weatherproof", "Audio / Visual, Paging & Intercom"),
    (re.compile(r"\bmaster\s+intercom\b|\bintercom\s+master\b", re.I),
     "Intercom Master Station", "Audio / Visual, Paging & Intercom"),
    (re.compile(r"\bintercom(\s+push\s+button|\s+substation|\s+unit)?\b", re.I),
     "Intercom Substation / Push Button", "Audio / Visual, Paging & Intercom"),
    (re.compile(r"\baudio\s+visual\s+outlet\b|\bav\s+outlet\b", re.I),
     "Audio / Visual (AV) Outlet", "Audio / Visual, Paging & Intercom"),
    (re.compile(r"\bclock\s+location\b|\bclock\s+outlet\b", re.I),
     "Clock Outlet / Location", "Audio / Visual, Paging & Intercom"),
    (re.compile(r"\bdoorbell\b|\bdoor\s+chime\b|\bdoor\s+buzzer\b", re.I),
     "Door Chime / Buzzer", "Audio / Visual, Paging & Intercom"),

    # ---------------- HVAC & Mechanical Devices ----------------
    (re.compile(r"\bthermostat\b", re.I),
     "Thermostat", "HVAC & Mechanical Devices"),
    (re.compile(r"\bhumidistat\b", re.I),
     "Humidistat", "HVAC & Mechanical Devices"),
    (re.compile(r"\bcarbon\s+dioxide\s+sensor\b|\bco2\s+sensor\b", re.I),
     "Carbon Dioxide (CO2) Sensor", "HVAC & Mechanical Devices"),
    (re.compile(r"\bcarbon\s+monoxide\s+sensor\b|\bco\s+detector\b|\bco\s+sensor\b", re.I),
     "Carbon Monoxide (CO) Sensor", "HVAC & Mechanical Devices"),
    (re.compile(r"\bdifferential\s+pressure\s+sensor\b|\bpressure\s+monitor\b", re.I),
     "Differential Pressure Sensor", "HVAC & Mechanical Devices"),
    (re.compile(r"\bvariable\s+air\s+volume\b|\bvav\s+box\b|\bvav\s+terminal\b", re.I),
     "Variable Air Volume (VAV) Terminal Unit", "HVAC & Mechanical Devices"),
    (re.compile(r"\bvolume\s+control\s+damper\b|\bmanual\s+volume\s+damper\b", re.I),
     "Volume Control Damper", "HVAC & Mechanical Devices"),
    (re.compile(r"\bmotorized\s+damper\b|\bcontrol\s+damper\b", re.I),
     "Motorized Control Damper", "HVAC & Mechanical Devices"),
    (re.compile(r"\bfire/smoke\s+damper\b|\bcombination\s+fire/smoke\s+damper\b", re.I),
     "Combination Fire / Smoke Damper", "HVAC & Mechanical Devices"),
    (re.compile(r"\bfire\s+damper\b", re.I),
     "Fire Damper", "HVAC & Mechanical Devices"),
    (re.compile(r"\bsmoke\s+damper\b", re.I),
     "Smoke Damper", "HVAC & Mechanical Devices"),
    (re.compile(r"\belectric\s+unit\s+heater\b|\bunit\s+heater\b|\bcabinet\s+unit\s+heater\b", re.I),
     "Electric Unit Heater", "HVAC & Mechanical Devices"),
    (re.compile(r"\bceiling\s+exhaust\s+fan\b|\bexhaust\s+fan\b", re.I),
     "Exhaust Fan", "HVAC & Mechanical Devices"),

    # ---------------- Electrical Distribution & Raceways ----------------
    (re.compile(r"\bjunction\s+box\b|\bj-?box\b", re.I),
     "Junction Box", "Electrical Distribution & Raceways"),
    (re.compile(r"\bpull\s+box\b", re.I),
     "Pull Box", "Electrical Distribution & Raceways"),
    (re.compile(r"\bpanelboard\b|\bdistribution\s+panel\b", re.I),
     "Panelboard", "Electrical Distribution & Raceways"),
    (re.compile(r"\bswitchboard\b|\bswitchgear\b", re.I),
     "Switchboard / Switchgear", "Electrical Distribution & Raceways"),
    (re.compile(r"\bautomatic\s+transfer\s+switch\b|\bats\b", re.I),
     "Automatic Transfer Switch (ATS)", "Electrical Distribution & Raceways"),
    (re.compile(r"\b(distribution\s+)?transformer\b|\bxfmr\b", re.I),
     "Transformer", "Electrical Distribution & Raceways"),
    (re.compile(r"\bvariable\s+frequency\s+drive\b|\bvfd\b", re.I),
     "Variable Frequency Drive (VFD)", "Electrical Distribution & Raceways"),
    (re.compile(r"\bmotor\s+starter\b|\bmotor\s+controller\b", re.I),
     "Motor Starter / Controller", "Electrical Distribution & Raceways"),

    # ---------------- Plumbing & Piping Specialties ----------------
    (re.compile(r"\bfloor\s+drain\b", re.I),
     "Floor Drain", "Plumbing & Piping Specialties"),
    (re.compile(r"\broof\s+drain\b", re.I),
     "Roof Drain", "Plumbing & Piping Specialties"),
    (re.compile(r"\bhose\s+bibb?\b", re.I),
     "Hose Bibb", "Plumbing & Piping Specialties"),
    (re.compile(r"\bwall\s+hydrant\b", re.I),
     "Wall Hydrant", "Plumbing & Piping Specialties"),
    (re.compile(r"\bbackflow\s+preventer\b|\bbfp\b", re.I),
     "Backflow Preventer", "Plumbing & Piping Specialties"),
    (re.compile(r"\bcleanout\s+(in|at)\s+floor\b|\bfloor\s+cleanout\b", re.I),
     "Cleanout - Floor", "Plumbing & Piping Specialties"),
    (re.compile(r"\bcleanout\s+(in|at)\s+wall\b|\bwall\s+cleanout\b", re.I),
     "Cleanout - Wall", "Plumbing & Piping Specialties"),
    (re.compile(r"\b(water\s+heater|instantaneous\s+water\s+heater)\b", re.I),
     "Water Heater", "Plumbing & Piping Specialties"),
    (re.compile(r"\b(fire\s+)?sprinkler\s+head\b", re.I),
     "Fire Sprinkler Head", "Plumbing & Piping Specialties"),
]


def classify_line_device(line: str) -> Optional[Tuple[str, str]]:
    """Match a text line from a legend to a normalized specific device type and category.

    Returns (canonical_name, category) or None if not a recognized device.
    """
    clean_line = line.strip()
    if len(clean_line) < 3 or len(clean_line) > 140:
        return None
    if BOILERPLATE_LINE_RE.search(clean_line):
        return None

    for pattern, canonical_name, category in SPECIFIC_RULES:
        if pattern.search(clean_line):
            return canonical_name, category
    return None


# ---------------------------------------------------------------------------
# PDF Scanning & Legend Extraction
# ---------------------------------------------------------------------------

def find_candidate_pdfs(root_dir: str) -> List[str]:
    """Find all candidate blueprint and reference PDFs, skipping non-construction folders."""
    pdfs = []
    for base, _dirs, files in os.walk(root_dir):
        if any(skip in base.lower() for skip in SKIP_DIR_KEYWORDS):
            continue
        for f in files:
            if f.lower().endswith(".pdf"):
                pdfs.append(os.path.join(base, f))
    return sorted(pdfs)


def identify_legend_pages(doc: fitz.Document) -> List[Tuple[int, str]]:
    """Identify page indices containing device / symbol legends.

    Returns a list of (page_index, matched_legend_title).
    """
    legend_pages = []
    seen_indices = set()

    # 1. Check Table of Contents / Bookmarks
    try:
        toc = doc.get_toc()
        for item in toc:
            title = (item[1] or "").strip()
            if any(k in title.lower() for k in ["legend", "symbol", "abbreviation", "wiring device"]):
                page_num = item[2]
                if 1 <= page_num <= len(doc):
                    idx = page_num - 1
                    if idx not in seen_indices:
                        seen_indices.add(idx)
                        legend_pages.append((idx, f"Bookmark: {title}"))
    except Exception:
        pass

    # 2. Check early pages (most sets place legends on first 1-15 pages)
    # and if set has <= 30 pages, check all pages.
    scan_limit = len(doc) if len(doc) <= 30 else min(15, len(doc))
    for idx in range(scan_limit):
        if idx in seen_indices:
            continue
        try:
            page_text = doc[idx].get_text()
            m = LEGEND_TITLE_RE.search(page_text)
            if m:
                seen_indices.add(idx)
                legend_pages.append((idx, m.group(0)))
        except Exception:
            pass

    return sorted(legend_pages, key=lambda x: x[0])


def extract_devices_from_page(
    doc: fitz.Document,
    page_idx: int,
    legend_title: str,
    pdf_name: str,
) -> List[Dict[str, Any]]:
    """Extract individual device definitions from a legend page."""
    extracted = []
    page = doc[page_idx]
    text = page.get_text()

    lines = [l.strip() for l in text.splitlines() if l.strip()]
    for line in lines:
        match = classify_line_device(line)
        if match:
            canonical_name, category = match
            extracted.append({
                "canonical_name": canonical_name,
                "category": category,
                "raw_text": line,
                "pdf": pdf_name,
                "page": page_idx + 1,
                "legend_title": legend_title,
            })

    return extracted


# ---------------------------------------------------------------------------
# Aggregator & Frequency Filter
# ---------------------------------------------------------------------------

def run_extraction(
    pdf_dir: str,
    min_sources: int = 2,
    verbose: bool = False,
) -> Dict[str, Any]:
    """Run full extraction across all candidate PDFs, categorize, and filter."""
    pdf_paths = find_candidate_pdfs(pdf_dir)
    print(f"Discovered {len(pdf_paths)} candidate PDFs to inspect.")

    all_raw_extractions = []
    legend_sheets_catalog = []

    for i, path in enumerate(pdf_paths):
        fname = os.path.basename(path)
        try:
            doc = fitz.open(path)
            legend_pages = identify_legend_pages(doc)
            for page_idx, title in legend_pages:
                legend_sheets_catalog.append({
                    "pdf": fname,
                    "path": path,
                    "page": page_idx + 1,
                    "title": title,
                })
                page_devices = extract_devices_from_page(doc, page_idx, title, fname)
                all_raw_extractions.extend(page_devices)
                if verbose and page_devices:
                    print(f"  [{fname} p.{page_idx+1}] {title} -> {len(page_devices)} devices")
            doc.close()
        except Exception as e:
            if verbose:
                print(f"  Error reading {fname}: {e}")

        if (i + 1) % 50 == 0 or (i + 1) == len(pdf_paths):
            print(f"Processed {i + 1}/{len(pdf_paths)} PDFs... ({len(legend_sheets_catalog)} legend sheets found)")

    # Group by canonical device name
    devices_by_name = defaultdict(list)
    for dev in all_raw_extractions:
        devices_by_name[dev["canonical_name"]].append(dev)

    included_devices = []
    excluded_single_source = []

    for name, occurrences in devices_by_name.items():
        # Distinct PDFs where this device appeared
        unique_pdfs = sorted(list(set(d["pdf"] for d in occurrences)))
        source_count = len(unique_pdfs)
        category = occurrences[0]["category"]

        # Collect sample raw texts and sheets
        sample_raw = sorted(list(set(d["raw_text"] for d in occurrences)))[:5]
        sources_summary = [
            {"pdf": d["pdf"], "page": d["page"], "legend": d["legend_title"]}
            for d in occurrences
        ]

        record = {
            "device_type": name,
            "category": category,
            "source_count": source_count,
            "total_occurrences": len(occurrences),
            "sources": unique_pdfs,
            "sample_legend_texts": sample_raw,
            "occurrences": sources_summary,
        }

        if source_count >= min_sources:
            included_devices.append(record)
        else:
            excluded_single_source.append(record)

    # Sort included devices by category then by source_count descending
    included_devices.sort(key=lambda x: (x["category"], -x["source_count"], x["device_type"]))
    excluded_single_source.sort(key=lambda x: (x["category"], x["device_type"]))

    # Group included devices by category
    categorized_included = defaultdict(list)
    for d in included_devices:
        categorized_included[d["category"]].append(d)

    summary = {
        "total_pdfs_scanned": len(pdf_paths),
        "total_legend_sheets_found": len(legend_sheets_catalog),
        "total_device_mentions_extracted": len(all_raw_extractions),
        "min_sources_threshold": min_sources,
        "included_device_types_count": len(included_devices),
        "excluded_single_source_count": len(excluded_single_source),
        "categories_count": len(categorized_included),
        "categories": {cat: len(devs) for cat, devs in sorted(categorized_included.items())},
    }

    return {
        "summary": summary,
        "categorized_devices": dict(categorized_included),
        "included_devices": included_devices,
        "excluded_single_source_devices": excluded_single_source,
        "legend_sheets": legend_sheets_catalog,
    }


def write_markdown_report(data: Dict[str, Any], md_path: str):
    """Generate a clean GitHub-style Markdown report of the extracted and filtered devices."""
    summary = data["summary"]
    categorized = data["categorized_devices"]

    lines = []
    lines.append("# Extracted Construction Device Types & Categories\n")
    lines.append(f"**Total PDFs Scanned:** {summary['total_pdfs_scanned']}  \n")
    lines.append(f"**Legend Sheets Analyzed:** {summary['total_legend_sheets_found']}  \n")
    lines.append(f"**Total Mentions Extracted:** {summary['total_device_mentions_extracted']}  \n")
    lines.append(f"**Cross-PDF Filter Threshold:** Minimum {summary['min_sources_threshold']} distinct PDF legends  \n")
    lines.append(f"**Included Device Types:** {summary['included_device_types_count']}  \n")
    lines.append(f"**Excluded Single-Source Items:** {summary['excluded_single_source_count']}  \n\n")

    lines.append("## Category Summary\n")
    lines.append("| Category | Device Types Count |")
    lines.append("| :--- | :---: |")
    for cat, count in sorted(summary["categories"].items(), key=lambda x: -x[1]):
        lines.append(f"| {cat} | {count} |")
    lines.append("\n---\n")

    lines.append("## Curated Device Types by Category\n")
    for cat, devices in sorted(categorized.items()):
        lines.append(f"### {cat} ({len(devices)} types)\n")
        lines.append("| Specific Device Type | Source Count | Sample Raw Legend Texts | Sample PDF Sources |")
        lines.append("| :--- | :---: | :--- | :--- |")
        for d in devices:
            raw_sample = "; ".join(f"`{t}`" for t in d["sample_legend_texts"][:2])
            sources_sample = ", ".join(d["sources"][:3])
            if len(d["sources"]) > 3:
                sources_sample += f" (+{len(d['sources'])-3} more)"
            lines.append(f"| **{d['device_type']}** | {d['source_count']} | {raw_sample} | {sources_sample} |")
        lines.append("")

    lines.append("\n---\n")
    lines.append("## Excluded Single-Source Items (Audit Log)\n")
    lines.append(
        "> [!NOTE]\n"
        "> These items appeared in only a single PDF legend and were excluded per the requirement: "
        "> *'Some things that are only in a specific one should not be in but ones that are in two or three or a bunch should be added to the list.'*\n"
    )
    lines.append("| Excluded Device Type | Category | Source PDF | Raw Text |")
    lines.append("| :--- | :--- | :--- | :--- |")
    for d in data["excluded_single_source_devices"]:
        src = d["sources"][0] if d["sources"] else "Unknown"
        raw = d["sample_legend_texts"][0] if d["sample_legend_texts"] else ""
        lines.append(f"| {d['device_type']} | {d['category']} | {src} | `{raw}` |")

    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"Wrote Markdown report to {md_path}")


# ---------------------------------------------------------------------------
# CLI Entrypoint
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Extract specific device types from blueprint legends across local PDFs."
    )
    parser.add_argument(
        "--pdf-dir",
        default="/home/max/.t3/projects/construction-calculator/blueprints_and_libraries",
        help="Root directory containing blueprint PDFs",
    )
    parser.add_argument(
        "--min-sources",
        type=int,
        default=2,
        help="Minimum distinct PDF sources required to include a device (default: 2)",
    )
    parser.add_argument(
        "--output",
        default="scripts/extracted_legend_devices.json",
        help="Path for JSON output",
    )
    parser.add_argument(
        "--markdown",
        default="scripts/extracted_legend_devices.md",
        help="Path for Markdown output",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Print verbose extraction details",
    )
    args = parser.parse_args()

    results = run_extraction(
        pdf_dir=args.pdf_dir,
        min_sources=args.min_sources,
        verbose=args.verbose,
    )

    # Save JSON
    out_dir = os.path.dirname(args.output)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"Wrote structured JSON results to {args.output}")

    # Save Markdown
    md_dir = os.path.dirname(args.markdown)
    if md_dir:
        os.makedirs(md_dir, exist_ok=True)
    write_markdown_report(results, args.markdown)

    # Print summary to stdout
    summary = results["summary"]
    print("\n" + "=" * 60)
    print("EXTRACTION & CATEGORIZATION SUMMARY")
    print("=" * 60)
    print(f"Total PDFs Scanned:                {summary['total_pdfs_scanned']}")
    print(f"Total Legend Sheets Analyzed:      {summary['total_legend_sheets_found']}")
    print(f"Total Raw Mentions Extracted:      {summary['total_device_mentions_extracted']}")
    print(f"Min Sources Inclusion Threshold:   {summary['min_sources_threshold']}")
    print(f"Included Specific Device Types:    {summary['included_device_types_count']}")
    print(f"Excluded Single-Source Items:      {summary['excluded_single_source_count']}")
    print("-" * 60)
    print("Categories:")
    for cat, count in summary["categories"].items():
        print(f"  * {cat:36s}: {count:3d} device types")
    print("=" * 60)


if __name__ == "__main__":
    main()
