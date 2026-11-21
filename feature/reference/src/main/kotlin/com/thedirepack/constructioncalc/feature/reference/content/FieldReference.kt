package com.thedirepack.constructioncalc.feature.reference.content

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Bullets
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Choice
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Paragraph
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Table
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.ToolLink
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tan

/** Trade references that aren't NEC tables: bending, plugs, colors, systems, abbreviations, fractions. */
internal object FieldReference {
    private fun rad(deg: Double) = deg * PI / 180

    val entries: List<RefEntry> get() = listOf(
        RefEntry(
            "bend-offsets", "Offset multipliers and shrink", RefSection.BENDING,
            "Distance between offset bends and how much the conduit shortens, by bend angle.",
            RefSource.Math,
            tools = listOf(ToolIds.CONDUIT_BENDING),
            keywords = listOf("offset", "multiplier", "shrink", "bend", "22.5", "30", "45", "10", "60", "kick"),
        ) {
            val angles = listOf(10.0 to ("6" to "1/16"), 22.5 to ("2.6" to "3/16"), 30.0 to ("2" to "1/4"), 45.0 to ("1.4" to "3/8"), 60.0 to ("1.2" to "1/2"))
            listOf(
                Table(
                    listOf("Angle", "Multiplier", "Field", "Shrink per inch", "Field"),
                    angles.map { (deg, field) ->
                        listOf("${Decimals.trim(deg, 1)}°", Decimals.fixed(1 / sin(rad(deg)), 3), field.first, Decimals.fixed(tan(rad(deg / 2)), 3), "${field.second} in")
                    },
                ),
                Bullets(
                    listOf(
                        "Distance between bends = offset depth × multiplier (the cosecant of the angle).",
                        "Shrink = depth × shrink per inch (the tangent of half the angle). Add it to the measurement to the obstruction.",
                        "The field values are the rounded numbers printed on many benders; the exact values are what the Conduit Bending tool uses.",
                    ),
                ),
                ToolLink("Lay out an offset", ToolIds.CONDUIT_BENDING),
            )
        },
        RefEntry(
            "bend-saddles", "Saddles", RefSection.BENDING,
            "Three-point and four-point saddle layout over an obstruction.",
            RefSource.Math,
            tools = listOf(ToolIds.CONDUIT_BENDING),
            keywords = listOf("saddle", "3 point", "4 point", "obstruction", "pipe crossing"),
        ) {
            listOf(
                Table(
                    listOf("Center / outer bends", "Outer marks from center, per inch of depth", "Move center mark ahead, per inch"),
                    listOf(
                        listOf("45° / 22.5°", "${Decimals.fixed(1 / sin(rad(22.5)), 2)} in (field 2-1/2)", "${Decimals.fixed(tan(rad(11.25)), 3)} in (field 3/16)"),
                        listOf("60° / 30°", "${Decimals.fixed(1 / sin(rad(30.0)), 2)} in (field 2)", "${Decimals.fixed(tan(rad(15.0)), 3)} in (field 1/4)"),
                    ),
                ),
                Bullets(
                    listOf(
                        "Three-point saddle: center bend on the obstruction's center, the two outer bends at half the angle, bent the other way.",
                        "Four-point saddle: two offsets back to back. Lay out the first offset, add the obstruction's width, then the second offset.",
                        "Every saddle adds its total degrees toward the 360° limit between pull points.",
                    ),
                ),
                ToolLink("Lay out a saddle", ToolIds.CONDUIT_BENDING),
            )
        },
        RefEntry(
            "knockouts", "Knockout and hole sizes", RefSection.RACEWAY,
            "Hole diameter for each conduit trade size.",
            RefSource.Practice("Typical punch and hole-saw sizes; manufacturers vary slightly"),
            tools = listOf(ToolIds.CONDUIT_BENDING, ToolIds.CONDUIT_FILL),
            keywords = listOf("knockout", "ko", "hole saw", "punch", "step bit", "drill"),
        ) {
            listOf(
                Table(
                    listOf("Trade size", "Hole"),
                    listOf(
                        "1/2" to "7/8", "3/4" to "1-1/8", "1" to "1-3/8", "1-1/4" to "1-3/4", "1-1/2" to "2",
                        "2" to "2-1/2", "2-1/2" to "3", "3" to "3-5/8", "3-1/2" to "4-1/8", "4" to "4-5/8",
                    ).map { (t, h) -> listOf("$t\"", "$h\"") },
                    "Nominal hole for the fitting's thread. Check the fitting or punch maker's chart for exact sizes.",
                ),
            )
        },
        RefEntry(
            "nema-configurations", "NEMA plugs and receptacles", RefSection.DEVICES,
            "Common straight-blade and locking configurations: voltage, amps and wiring.",
            RefSource.Practice("NEMA WD 6 configurations; ratings as commonly listed"),
            keywords = listOf("nema", "receptacle", "plug", "twist lock", "locking", "5-15", "5-20", "6-50", "14-50", "l14-30", "l6-30", "l21", "tt-30", "rv", "dryer", "range"),
        ) {
            val straight = listOf(
                listOf("1-15", "125", "15", "2-pole, 2-wire", "Old ungrounded receptacles"),
                listOf("5-15", "125", "15", "2-pole, 3-wire", "Standard receptacle"),
                listOf("5-20", "125", "20", "2-pole, 3-wire", "T-slot, 20 A circuits"),
                listOf("5-30", "125", "30", "2-pole, 3-wire", ""),
                listOf("5-50", "125", "50", "2-pole, 3-wire", ""),
                listOf("6-15", "250", "15", "2-pole, 3-wire", "240 V tools, window AC"),
                listOf("6-20", "250", "20", "2-pole, 3-wire", ""),
                listOf("6-30", "250", "30", "2-pole, 3-wire", "Welders, compressors"),
                listOf("6-50", "250", "50", "2-pole, 3-wire", "Welders"),
                listOf("7-15 to 7-50", "277", "15–50", "2-pole, 3-wire", "277 V lighting"),
                listOf("10-30", "125/250", "30", "3-pole, 3-wire", "Old dryers, no ground"),
                listOf("10-50", "125/250", "50", "3-pole, 3-wire", "Old ranges, no ground"),
                listOf("14-30", "125/250", "30", "3-pole, 4-wire", "Dryers"),
                listOf("14-50", "125/250", "50", "3-pole, 4-wire", "Ranges, EV chargers, RVs"),
                listOf("14-60", "125/250", "60", "3-pole, 4-wire", ""),
                listOf("15-20 to 15-60", "250 3φ", "20–60", "3-pole, 4-wire", "Three-phase"),
                listOf("18-20 to 18-60", "120/208 3φ Y", "20–60", "4-pole, 4-wire", "No ground"),
                listOf("TT-30", "125", "30", "2-pole, 3-wire", "Travel trailers"),
            )
            val locking = listOf(
                listOf("L5-15 / L5-20 / L5-30", "125", "15 / 20 / 30", "2-pole, 3-wire", ""),
                listOf("L6-15 / L6-20 / L6-30", "250", "15 / 20 / 30", "2-pole, 3-wire", ""),
                listOf("L7-20 / L7-30", "277", "20 / 30", "2-pole, 3-wire", ""),
                listOf("L14-20 / L14-30", "125/250", "20 / 30", "3-pole, 4-wire", "Generators"),
                listOf("L15-20 / L15-30", "250 3φ", "20 / 30", "3-pole, 4-wire", ""),
                listOf("L16-20 / L16-30", "480 3φ", "20 / 30", "3-pole, 4-wire", ""),
                listOf("L21-20 / L21-30", "120/208 3φ Y", "20 / 30", "4-pole, 5-wire", ""),
                listOf("L22-20 / L22-30", "277/480 3φ Y", "20 / 30", "4-pole, 5-wire", ""),
                listOf("L23-20 / L23-30", "347/600 3φ Y", "20 / 30", "4-pole, 5-wire", ""),
            )
            val columns = listOf("NEMA", "Volts", "Amps", "Wiring", "Typical use")
            listOf(
                Choice("Type", listOf("Straight blade", "Locking")) { pick -> listOf(Table(columns, if (pick == 0) straight else locking, "\"Wire\" counts the grounding conductor; \"pole\" doesn't.")) },
                Paragraph("A configuration's rating doesn't by itself make it suitable for a location or circuit: check the equipment's listing and the branch-circuit rules."),
            )
        },
        RefEntry(
            "wire-colors", "Conductor colors", RefSection.DEVICES,
            "Which colors are required and the common phase colors by system.",
            RefSource.CodeRef(listOf(CodeRule.GROUNDED_IDENTIFICATION, CodeRule.EGC_IDENTIFICATION, CodeRule.UNGROUNDED_IDENTIFICATION, CodeRule.FEEDER_IDENTIFICATION)),
            tools = listOf(ToolIds.PANELS),
            keywords = listOf("color", "colour", "phase tape", "brown orange yellow", "black red blue", "neutral", "white", "gray", "green", "high leg"),
        ) {
            listOf(
                Bullets(
                    listOf(
                        "Required: neutrals (grounded conductors) white or gray, or three white or gray stripes (${CodeRule.GROUNDED_IDENTIFICATION.cite}).",
                        "Required: equipment grounding conductors bare, green, or green with yellow stripes (${CodeRule.EGC_IDENTIFICATION.cite}).",
                        "Phase colors aren't set by the NEC, but where there's more than one voltage system, the scheme must be posted at each panel (${CodeRule.UNGROUNDED_IDENTIFICATION.cite}, ${CodeRule.FEEDER_IDENTIFICATION.cite}). Many jurisdictions and specs require their own.",
                    ),
                ),
                Table(
                    listOf("System", "Phases", "Neutral"),
                    listOf(
                        listOf("120/240 V single-phase", "Black, red", "White"),
                        listOf("120/208 V three-phase wye", "Black, red, blue", "White"),
                        listOf("277/480 V three-phase wye", "Brown, orange, yellow", "Gray"),
                        listOf("120/240 V high-leg delta", "Black, orange (high leg), blue", "White"),
                        listOf("480 V delta, ungrounded", "Brown, orange, yellow", "—"),
                    ),
                    "Common US practice, not a code requirement.",
                ),
                Paragraph("High-leg delta: the high leg (about 208 V to ground) must be orange or otherwise marked, and it's the B phase in panelboards. Never use it for 120 V loads."),
            )
        },
        RefEntry(
            "voltage-systems", "Common voltage systems", RefSection.FIELD,
            "Line-to-line and line-to-ground voltages of the usual services.",
            RefSource.Math,
            tools = listOf(ToolIds.PANELS, ToolIds.VOLTAGE_DROP),
            keywords = listOf("voltage", "208", "240", "277", "480", "600", "347", "wye", "delta", "service"),
        ) {
            listOf(
                Table(
                    listOf("System", "Line to line", "Line to neutral", "Wires"),
                    listOf(
                        listOf("Single-phase, 3-wire", "240 V", "120 V", "2 hots, neutral"),
                        listOf("Three-phase wye", "208 V", "120 V", "3 hots, neutral"),
                        listOf("Three-phase wye", "480 V", "277 V", "3 hots, neutral"),
                        listOf("Three-phase wye (Canada)", "600 V", "347 V", "3 hots, neutral"),
                        listOf("High-leg delta", "240 V", "120 V (A, C); 208 V high leg", "3 hots, neutral"),
                        listOf("Delta, ungrounded or corner-grounded", "240 or 480 V", "—", "3 hots"),
                    ),
                ),
                Paragraph("Wye: line-to-line = √3 × line-to-neutral. 120 × 1.732 = 208; 277 × 1.732 = 480."),
                ToolLink("Wye and delta formulas", ToolIds.FORMULAS, "wye-delta"),
            )
        },
        RefEntry(
            "abbreviations", "Abbreviations", RefSection.FIELD,
            "Abbreviations on drawings, nameplates and in the code.",
            RefSource.Practice("Common usage"),
            keywords = listOf("abbreviation", "acronym", "meaning", "what does"),
        ) {
            listOf(Table(listOf("Abbreviation", "Meaning"), ABBREVIATIONS.map { (a, m) -> listOf(a, m) }))
        },
        RefEntry(
            "fractions", "Fractions, decimals and millimeters", RefSection.FIELD,
            "Inch fractions with their decimal and metric equivalents.",
            RefSource.Math,
            tools = listOf(ToolIds.FOOT_INCH),
            keywords = listOf("fraction", "decimal", "mm", "millimeter", "metric", "64ths", "32nds", "16ths", "conversion"),
        ) {
            listOf(
                Choice("Steps", listOf("Sixteenths", "Thirty-seconds", "Sixty-fourths")) { pick ->
                    val denominator = listOf(16, 32, 64)[pick]
                    val rows = (1..denominator).map { k ->
                        val inches = k.toDouble() / denominator
                        listOf(fraction(k, denominator), Decimals.fixed(inches, 4), Decimals.fixed(inches * 25.4, 2))
                    }
                    listOf(Table(listOf("Inches", "Decimal", "mm"), rows))
                },
                ToolLink("Calculate with fractions", ToolIds.FOOT_INCH),
            )
        },
    )

    private fun fraction(k: Int, d: Int): String {
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(k, d)
        return if (k == d) "1" else "${k / g}/${d / g}"
    }

    private val ABBREVIATIONS = listOf(
        "AFCI" to "Arc-fault circuit interrupter",
        "AHJ" to "Authority having jurisdiction (the inspector or code official)",
        "AIC" to "Ampere interrupting capacity (also AIR, interrupting rating)",
        "ATS" to "Automatic transfer switch",
        "AWG" to "American Wire Gauge",
        "CCA" to "Copper-clad aluminum",
        "CCC" to "Current-carrying conductor",
        "EGC" to "Equipment grounding conductor",
        "EMT" to "Electrical metallic tubing",
        "ENT" to "Electrical nonmetallic tubing",
        "FLA" to "Full-load amps (motor nameplate)",
        "FLC" to "Full-load current (NEC motor tables)",
        "FMC" to "Flexible metal conduit",
        "GEC" to "Grounding electrode conductor",
        "GFCI" to "Ground-fault circuit interrupter (people protection, about 5 mA)",
        "GFPE" to "Ground-fault protection of equipment",
        "IMC" to "Intermediate metal conduit",
        "kcmil" to "Thousand circular mils (formerly MCM)",
        "LFMC" to "Liquidtight flexible metal conduit",
        "LFNC" to "Liquidtight flexible nonmetallic conduit",
        "LRA" to "Locked-rotor amps",
        "MCA" to "Minimum circuit ampacity (equipment nameplate)",
        "MCB" to "Main circuit breaker",
        "MCC" to "Motor control center",
        "MDP" to "Main distribution panel",
        "MLO" to "Main lugs only",
        "MOCP" to "Maximum overcurrent protection (equipment nameplate)",
        "NM" to "Nonmetallic-sheathed cable (Romex)",
        "OCPD" to "Overcurrent protective device (breaker or fuse)",
        "PF" to "Power factor",
        "PVC" to "Rigid polyvinyl chloride conduit",
        "RMC" to "Rigid metal conduit",
        "SCCR" to "Short-circuit current rating",
        "SER / SEU" to "Service-entrance cable, round / flat",
        "SPD" to "Surge-protective device",
        "THHN" to "Thermoplastic, high heat-resistant, nylon-jacketed",
        "THWN" to "Thermoplastic, heat- and water-resistant, nylon-jacketed",
        "UF" to "Underground feeder cable",
        "USE" to "Underground service-entrance cable",
        "VFD" to "Variable-frequency drive",
        "XHHW" to "Cross-linked polyethylene, high heat- and water-resistant",
    )
}
