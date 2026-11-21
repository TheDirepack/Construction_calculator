package com.thedirepack.constructioncalc.feature.reference.content

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.AmbientCorrection
import com.thedirepack.constructioncalc.core.electrical.Ampacity
import com.thedirepack.constructioncalc.core.electrical.BareConductorArea
import com.thedirepack.constructioncalc.core.electrical.BendRadiusColumn
import com.thedirepack.constructioncalc.core.electrical.ConductorCountAdjustment
import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitFill
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.EquipmentGroundingConductor
import com.thedirepack.constructioncalc.core.electrical.InsulatedConductorArea
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.MotorFullLoadCurrent
import com.thedirepack.constructioncalc.core.electrical.MotorProtection
import com.thedirepack.constructioncalc.core.electrical.MotorProtectiveDevice
import com.thedirepack.constructioncalc.core.electrical.MotorType
import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.electrical.SmallConductorLimit
import com.thedirepack.constructioncalc.core.electrical.StandardOcpd
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.WorkingSpace
import com.thedirepack.constructioncalc.core.electrical.WorkingSpaceCondition
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Bullets
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Choice
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Paragraph
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Table
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.ToolLink

/**
 * NEC tables, generated from the same `core:electrical` data the calculators use, so the Reference
 * can never disagree with a result. When a table changes for a new edition, it changes here too.
 * Notes are in the app's own words; the code's text is never copied.
 */
internal object CodeTables {
    private const val BLANK = "—"
    private fun n(v: Double?, decimals: Int = 4) = v?.let { Decimals.trim(it, decimals) } ?: BLANK
    private fun i(v: Int?) = v?.toString() ?: BLANK

    // Unit-aware cells: inches ↔ mm, in² ↔ mm², Ω per 1000 ft ↔ Ω per km (exact factors).
    private fun len(inches: Double?, ctx: RefContext, decimals: Int = 3) =
        inches?.let { if (ctx.metric) Decimals.trim(it * 25.4, 1) else Decimals.trim(it, decimals) } ?: BLANK
    private fun area(sqIn: Double?, ctx: RefContext) =
        sqIn?.let { if (ctx.metric) Decimals.trim(it * 645.16, 1) else Decimals.trim(it, 4) } ?: BLANK
    private fun ohms(perKft: Double?, ctx: RefContext) =
        perKft?.let { if (ctx.metric) Decimals.trim(it / 0.3048, 4) else Decimals.trim(it, 4) } ?: BLANK
    private fun lenUnit(ctx: RefContext) = if (ctx.metric) "mm" else "in"
    private fun areaUnit(ctx: RefContext) = if (ctx.metric) "mm²" else "in²"
    private fun ohmUnit(ctx: RefContext) = if (ctx.metric) "Ω/km" else "Ω/kft"

    private val ampacityMaterials = listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM)

    val entries: List<RefEntry> get() = listOf(
        RefEntry(
            "nec-ampacity", "Conductor ampacity", RefSection.CONDUCTORS,
            "Allowable ampacity by size and insulation temperature, before correction and adjustment.",
            RefSource.CodeRef(CodeRule.AMPACITY_TABLE),
            tools = listOf(ToolIds.PANELS, ToolIds.CONDUIT_FILL, ToolIds.VFD_SIZING, ToolIds.VOLTAGE_DROP),
            keywords = listOf("ampacity", "amps", "wire size", "310.16", "60", "75", "90", "thhn"),
        ) {
            listOf(
                Choice("Material", listOf("Copper", "Aluminum or copper-clad aluminum")) { pick ->
                    val material = ampacityMaterials[pick]
                    val rows = ConductorSize.entries.mapNotNull { size ->
                        val cells = TemperatureRating.entries.map { Ampacity.of(size, material, it) }
                        if (cells.all { it == null }) null else listOf(size.label) + cells.map(::i)
                    }
                    listOf(Table(listOf("Size", "60 °C", "75 °C", "90 °C"), rows, "Amperes. Up to three current-carrying conductors, 30 °C (86 °F) ambient."))
                },
                Bullets(
                    listOf(
                        "60 °C: TW, UF. 75 °C: THW, THWN, XHHW, USE. 90 °C: THHN, THWN-2, XHHW-2, RHW-2 (check the cable's marking).",
                        "Correct for ambient temperature and adjust for more than three current-carrying conductors (${CodeRule.AMPACITY.cite}). The result can't exceed what the terminals allow (${CodeRule.TERMINATION_TEMPERATURE.cite}).",
                        "Small conductors have their own protection limits (${CodeRule.SMALL_CONDUCTORS.cite}).",
                    ),
                ),
                ToolLink("Size a circuit in Panels", ToolIds.PANELS),
            )
        },
        RefEntry(
            "nec-terminations", "Termination temperature", RefSection.CONDUCTORS,
            "Which ampacity column a circuit may use, based on its terminals.",
            RefSource.CodeRef(CodeRule.TERMINATION_TEMPERATURE),
            tools = listOf(ToolIds.PANELS, ToolIds.CONDUIT_FILL, ToolIds.VFD_SIZING),
            keywords = listOf("terminal", "lug", "60 °c", "75 °c", "column"),
        ) {
            listOf(
                Bullets(
                    listOf(
                        "Circuits rated 100 A or less, or 14 AWG through 1 AWG: use the 60 °C column, unless the equipment is listed and marked for 75 °C.",
                        "Circuits over 100 A, or conductors larger than 1 AWG: use the 75 °C column, unless the equipment is listed and marked higher.",
                        "Most panelboards, breakers and devices today are marked 60/75 °C, so 75 °C is common. Check the label.",
                        "Higher-rated insulation (90 °C) still helps: correction and adjustment start from its column, as long as the result doesn't exceed the terminal column.",
                    ),
                ),
            )
        },
        RefEntry(
            "nec-ambient", "Ambient temperature correction", RefSection.CONDUCTORS,
            "Multiply the table ampacity by the factor for the hottest temperature along the run.",
            RefSource.CodeRef(listOf(CodeRule.AMBIENT_CORRECTION, CodeRule.ROOFTOP_ADDER)),
            tools = listOf(ToolIds.PANELS, ToolIds.CONDUIT_FILL),
            keywords = listOf("ambient", "temperature", "correction", "derate", "heat", "rooftop", "attic"),
        ) {
            val upper = (10..85 step 5).toList()
            val rows = upper.mapIndexed { idx, c ->
                val f = c * 9 / 5 + 32
                val cRange = if (idx == 0) "$c or less" else "${upper[idx - 1] + 1}–$c"
                val fRange = if (idx == 0) "$f or less" else "${upper[idx - 1] * 9 / 5 + 33}–$f"
                listOf(cRange, fRange) + TemperatureRating.entries.map { n(AmbientCorrection.factor(c.toDouble(), it), 2) }
            }
            listOf(
                Table(listOf("°C", "°F", "60 °C", "75 °C", "90 °C"), rows, "Factors for a 30 °C (86 °F) table basis. A dash: too hot for that insulation."),
                Paragraph("Raceways or cables in sunlight on a rooftop, less than 3/4 in. (19 mm) above it: add ${AmbientCorrection.ROOFTOP_ADDER_C.toInt()} °C (60 °F) to the outdoor temperature (${CodeRule.ROOFTOP_ADDER.cite}). Type XHHW-2 is exempt."),
            )
        },
        RefEntry(
            "nec-adjustment", "More than three current-carrying conductors", RefSection.CONDUCTORS,
            "Adjustment factors when a raceway or cable holds more than three current-carrying conductors.",
            RefSource.CodeRef(listOf(CodeRule.CONDUCTOR_COUNT_ADJUSTMENT, CodeRule.NEUTRAL_COUNTING, CodeRule.GROUNDING_COUNTING)),
            tools = listOf(ToolIds.PANELS, ToolIds.CONDUIT_FILL),
            keywords = listOf("bundling", "derate", "derating", "adjustment", "current carrying", "ccc", "neutral"),
        ) {
            val bands = listOf(4 to 6, 7 to 9, 10 to 20, 21 to 30, 31 to 40)
            val rows = bands.map { (lo, hi) -> listOf("$lo–$hi", "${(ConductorCountAdjustment.factor(hi) * 100).toInt()}%") } +
                listOf(listOf("41 and above", "${(ConductorCountAdjustment.factor(41) * 100).toInt()}%"))
            listOf(
                Table(listOf("Current-carrying conductors", "Percent of ampacity"), rows, "Apply together with any ambient correction."),
                Bullets(
                    listOf(
                        "A neutral carrying only the unbalanced current of its circuit doesn't count. It does count on a 3-wire circuit from a 4-wire wye (two phases and the neutral), and where most of the load is nonlinear (${CodeRule.NEUTRAL_COUNTING.cite}).",
                        "Equipment grounding and bonding conductors don't count (${CodeRule.GROUNDING_COUNTING.cite}).",
                        "Raceways 24 in. or shorter (nipples) need no adjustment (${CodeRule.SHORT_RACEWAY_ADJUSTMENT.cite}).",
                    ),
                ),
                ToolLink("Check a raceway in Conduit Fill", ToolIds.CONDUIT_FILL),
            )
        },
        RefEntry(
            "nec-conductor-properties", "Conductor properties", RefSection.CONDUCTORS,
            "Area, bare diameter and DC resistance at 75 °C for every size.",
            RefSource.CodeRef(CodeRule.CONDUCTOR_PROPERTIES),
            tools = listOf(ToolIds.VOLTAGE_DROP, ToolIds.CONDUIT_FILL),
            keywords = listOf("circular mils", "cmil", "resistance", "ohms", "diameter", "bare", "mm2", "kcmil", "table 8"),
        ) { ctx ->
            listOf(
                Choice("Stranding", listOf("Stranded", "Solid (18–8 AWG)")) { pick ->
                    val stranding = if (pick == 0) Stranding.STRANDED else Stranding.SOLID
                    val sizes = ConductorSize.entries.filter { stranding == Stranding.STRANDED || ConductorResistance.hasSolidOption(it) }
                    val rows = sizes.map { size ->
                        val bare = BareConductorArea.dimensions(size, solid = stranding == Stranding.SOLID)
                        listOf(
                            size.label,
                            size.circularMils.toString(),
                            n(size.squareMillimeters, 2),
                            len(bare?.diameterIn, ctx),
                            ohms(ConductorResistance.ohmsPerKft(size, ConductorMaterial.COPPER, stranding), ctx),
                            ohms(ConductorResistance.ohmsPerKft(size, ConductorMaterial.COATED_COPPER, stranding), ctx),
                            ohms(ConductorResistance.ohmsPerKft(size, ConductorMaterial.ALUMINUM, stranding), ctx),
                        )
                    }
                    listOf(Table(listOf("Size", "cmil", "mm²", "Dia. ${lenUnit(ctx)}", "Cu ${ohmUnit(ctx)}", "Cu coated", "Al ${ohmUnit(ctx)}"), rows, "DC resistance at 75 °C. mm² is calculated from circular mils."))
                },
                Paragraph("At another temperature: R2 = R1 × (1 + α × (T2 − 75)), α = 0.00323 copper, 0.00330 aluminum."),
                ToolLink("Correct for temperature in Electrical Formulas", ToolIds.FORMULAS, "resistance-temperature"),
            )
        },
        RefEntry(
            "nec-ac-impedance", "AC resistance and reactance", RefSection.CONDUCTORS,
            "Ohms per 1000 ft for three-phase, 60 Hz circuits at 75 °C, by conduit material.",
            RefSource.CodeRef(CodeRule.AC_IMPEDANCE),
            tools = listOf(ToolIds.VOLTAGE_DROP),
            keywords = listOf("impedance", "reactance", "xl", "ac resistance", "table 9", "voltage drop"),
        ) { ctx ->
            listOf(
                Choice("Material", listOf("Copper", "Aluminum")) { pick ->
                    val material = ampacityMaterials[pick]
                    val rows = ConductorImpedance.sizes(material).map { size ->
                        val r = ConductorImpedance.row(size, material)!!
                        listOf(
                            size.label, ohms(r.xNonMagnetic, ctx), ohms(r.xSteel, ctx), ohms(r.rPvc, ctx), ohms(r.rAluminum, ctx), ohms(r.rSteel, ctx),
                            ohms(r.effectiveZ(Table9Conduit.PVC, 0.85), ctx), ohms(r.effectiveZ(Table9Conduit.STEEL, 0.85), ctx),
                        )
                    }
                    val per = if (ctx.metric) "Ω per km" else "Ω per 1000 ft"
                    listOf(Table(listOf("Size", "XL PVC/Al", "XL steel", "R PVC", "R Al", "R steel", "Z PVC @0.85", "Z steel @0.85"), rows, "$per, to neutral. Effective Z at 0.85 power factor = R × PF + XL × sin(acos PF)."))
                },
                ToolLink("Calculate voltage drop", ToolIds.VOLTAGE_DROP),
            )
        },
        RefEntry(
            "nec-conductor-dimensions", "Insulated conductor dimensions", RefSection.CONDUCTORS,
            "Approximate area and diameter of insulated building wire, for conduit fill.",
            RefSource.CodeRef(CodeRule.CONDUCTOR_DIMENSIONS),
            tools = listOf(ToolIds.CONDUIT_FILL, ToolIds.PULL_PLANNER, ToolIds.REEL_LENGTH),
            keywords = listOf("thhn", "xhhw", "area", "diameter", "od", "table 5", "insulation"),
        ) { ctx ->
            val types = InsulationType.entries
            listOf(
                Choice("Insulation", types.map { it.label }) { pick ->
                    val type = types[pick]
                    val rows = InsulatedConductorArea.sizes(type).map { size ->
                        val d = InsulatedConductorArea.dimensions(type, size)!!
                        listOf(size.label, area(d.areaSqIn, ctx), len(d.diameterIn, ctx))
                    }
                    listOf(Table(listOf("Size", "Area ${areaUnit(ctx)}", "Diameter ${lenUnit(ctx)}"), rows, "Types: ${type.types}."))
                },
            )
        },
        RefEntry(
            "nec-standard-ocpd", "Standard breaker and fuse sizes", RefSection.PROTECTION,
            "Standard ampere ratings of fuses and inverse-time circuit breakers.",
            RefSource.CodeRef(listOf(CodeRule.STANDARD_OCPD, CodeRule.NEXT_SIZE_UP)),
            tools = listOf(ToolIds.PANELS, ToolIds.VFD_SIZING),
            keywords = listOf("breaker", "fuse", "standard size", "ocpd", "rating", "next size up"),
        ) {
            listOf(
                Paragraph(StandardOcpd.ratings.joinToString(", ") + " A."),
                Paragraph("Fuses only, also: " + StandardOcpd.additionalFuseRatings.joinToString(", ") + " A."),
                Paragraph("Next size up (${CodeRule.NEXT_SIZE_UP.cite}): at 800 A or less, when a conductor's ampacity falls between standard ratings, the next higher rating is allowed, unless the circuit supplies multiple receptacles for cord-and-plug loads and the device would be over the conductor's ampacity. Over 800 A the conductor must be at least the device rating."),
            )
        },
        RefEntry(
            "nec-small-conductors", "Small conductor protection", RefSection.PROTECTION,
            "Maximum breaker or fuse for 18–10 AWG, after correction and adjustment.",
            RefSource.CodeRef(CodeRule.SMALL_CONDUCTORS),
            tools = listOf(ToolIds.PANELS),
            keywords = listOf("14 awg 15 a", "12 awg 20 a", "10 awg 30 a", "small conductor", "240.4(d)"),
        ) {
            val sizes = listOf(ConductorSize.AWG_18, ConductorSize.AWG_16, ConductorSize.AWG_14, ConductorSize.AWG_12, ConductorSize.AWG_10)
            val rows = sizes.map { s ->
                listOf(s.label, i(SmallConductorLimit.maxOcpd(s, ConductorMaterial.COPPER)), i(SmallConductorLimit.maxOcpd(s, ConductorMaterial.ALUMINUM)), i(SmallConductorLimit.maxOcpd(s, ConductorMaterial.COPPER_CLAD_ALUMINUM)))
            }
            val conditions = sizes.flatMap { s -> ConductorMaterial.entries.mapNotNull { SmallConductorLimit.conditions(s, it) } }.distinct()
            listOf(
                Table(listOf("Size", "Copper", "Aluminum", "Cu-clad Al"), rows, "Amperes. A dash: not a size for that material here."),
                Bullets(conditions + "Motor, welder, HVAC and other equipment circuits can be protected differently; see their own articles."),
            )
        },
        RefEntry(
            "nec-egc", "Equipment grounding conductor size", RefSection.PROTECTION,
            "Minimum equipment grounding conductor by the rating of the overcurrent device ahead of it.",
            RefSource.CodeRef(listOf(CodeRule.EGC_SIZE, CodeRule.EGC_INCREASED, CodeRule.EGC_MULTIPLE_CIRCUITS)),
            tools = listOf(ToolIds.PANELS, ToolIds.CONDUIT_FILL),
            keywords = listOf("ground", "egc", "grounding conductor", "bond", "250.122"),
        ) {
            listOf(
                Table(
                    listOf("Device rating, not over (A)", "Copper", "Aluminum or Cu-clad Al"),
                    EquipmentGroundingConductor.table.map { (amps, cu, al) -> listOf(amps.toString(), cu.label, al.label) },
                ),
                Bullets(
                    listOf(
                        "Upsized phase conductors (for voltage drop, say): increase the grounding conductor in proportion to their circular mils (${CodeRule.EGC_INCREASED.cite}).",
                        "Several circuits in one raceway can share one grounding conductor sized for the largest device (${CodeRule.EGC_MULTIPLE_CIRCUITS.cite}).",
                        "Never larger than the circuit's ungrounded conductors need to be.",
                    ),
                ),
            )
        },
        RefEntry(
            "nec-fill-percent", "Conduit fill limits", RefSection.RACEWAY,
            "How much of a conduit's area conductors may fill.",
            RefSource.CodeRef(listOf(CodeRule.FILL_PERCENT, CodeRule.FILL_NIPPLES, CodeRule.FILL_ROUNDING, CodeRule.FILL_GROUNDING_CONDUCTORS)),
            tools = listOf(ToolIds.CONDUIT_FILL, ToolIds.PULL_PLANNER),
            keywords = listOf("fill", "40%", "53%", "31%", "60%", "nipple", "percent"),
        ) {
            listOf(
                Table(
                    listOf("Conductors", "Maximum fill"),
                    listOf(
                        listOf("1", "${ConduitFill.limitPercent(1)}%"),
                        listOf("2", "${ConduitFill.limitPercent(2)}%"),
                        listOf("Over 2", "${ConduitFill.limitPercent(3)}%"),
                        listOf("Nipple, 24 in. or shorter", "${ConduitFill.limitPercent(3, nipple = true)}%"),
                    ),
                ),
                Bullets(
                    listOf(
                        "Equipment grounding conductors count toward fill (${CodeRule.FILL_GROUNDING_CONDUCTORS.cite}).",
                        "All conductors the same size: a count ending in .8 or more rounds up (${CodeRule.FILL_ROUNDING.cite}).",
                        "Three conductors can jam in a bend when the conduit's inside diameter is 2.8 to 3.2 times a conductor's diameter (${CodeRule.JAM_RATIO.cite}).",
                    ),
                ),
                ToolLink("Check fill", ToolIds.CONDUIT_FILL),
            )
        },
        RefEntry(
            "nec-conduit-dimensions", "Conduit dimensions", RefSection.RACEWAY,
            "Inside diameter and usable area of each conduit type and size.",
            RefSource.CodeRef(CodeRule.CONDUIT_DIMENSIONS),
            tools = listOf(ToolIds.CONDUIT_FILL, ToolIds.PULL_PLANNER),
            keywords = listOf("emt", "rmc", "imc", "pvc", "inside diameter", "id", "area", "table 4", "trade size"),
        ) { ctx ->
            val types = ConduitType.entries
            listOf(
                Choice("Conduit", types.map { it.displayName }) { pick ->
                    val type = types[pick]
                    val rows = ConduitTable.sizes(type).map { c ->
                        listOf(c.tradeSize.display, c.tradeSize.metricDesignator.toString(), len(c.internalDiameterIn, ctx)) +
                            listOf(c.area100SqIn, c.allowedAreaSqIn(40), c.allowedAreaSqIn(53), c.allowedAreaSqIn(31), c.allowedAreaSqIn(60)).map { a ->
                                if (ctx.metric) area(a, ctx) else n(a, 3)
                            }
                    }
                    listOf(Table(listOf("Trade size", "Metric", "ID ${lenUnit(ctx)}", "100% ${areaUnit(ctx)}", "40%", "53%", "31%", "60%"), rows, "${type.fullName}, ${type.article}."))
                },
            )
        },
        RefEntry(
            "nec-conductors-in-conduit", "How many wires fit", RefSection.RACEWAY,
            "Most conductors of one size and type in each conduit size.",
            RefSource.CodeRef(listOf(CodeRule.CONDUCTORS_IN_CONDUIT, CodeRule.FILL_PERCENT, CodeRule.CONDUIT_DIMENSIONS, CodeRule.CONDUCTOR_DIMENSIONS)),
            tools = listOf(ToolIds.CONDUIT_FILL),
            keywords = listOf("annex c", "how many", "wires", "fit", "max conductors", "emt", "thhn"),
        ) {
            val types = ConduitType.entries
            val insulations = InsulationType.entries
            listOf(
                Choice("Conduit", types.map { it.displayName }) { typePick ->
                    val conduits = ConduitTable.sizes(types[typePick])
                    listOf(
                        Choice("Insulation", insulations.map { it.label }) { insPick ->
                            val ins = insulations[insPick]
                            val rows = InsulatedConductorArea.sizes(ins).map { size ->
                                val area = InsulatedConductorArea.area(ins, size)!!
                                listOf(size.label) + conduits.map { c -> ConduitFill.maxCount(c, area).takeIf { it > 0 }?.toString() ?: BLANK }
                            }
                            listOf(Table(listOf("Size") + conduits.map { it.tradeSize.display }, rows, "Calculated from the fill limits and dimension tables the way the annex does it. The annex itself is informative."))
                        },
                    )
                },
                ToolLink("Mixed sizes: open Conduit Fill", ToolIds.CONDUIT_FILL),
            )
        },
        RefEntry(
            "nec-bend-radius", "Bend radius and bends between pull points", RefSection.RACEWAY,
            "Minimum radius of conduit bends, and the 360° limit between pull points.",
            RefSource.CodeRef(CodeRule.BEND_RADIUS),
            tools = listOf(ToolIds.CONDUIT_BENDING, ToolIds.PULL_PLANNER),
            keywords = listOf("radius", "bend", "360", "pull point", "pull box", "table 2"),
        ) { ctx ->
            val sizes = TradeSize.entries.filter { RacewayBends.minimumRadiusInches(it, BendRadiusColumn.ONE_SHOT) != null }
            listOf(
                Table(
                    listOf("Trade size", "One shot and full shoe (${lenUnit(ctx)})", "Other bends (${lenUnit(ctx)})"),
                    sizes.map { s -> listOf(if (ctx.metric) "${s.display} (${s.metricDesignator})" else s.display, len(RacewayBends.minimumRadiusInches(s, BendRadiusColumn.ONE_SHOT), ctx, 2), len(RacewayBends.minimumRadiusInches(s, BendRadiusColumn.OTHER), ctx, 2)) },
                    "Radius to the centerline of the conduit.",
                ),
                Paragraph("No more than ${RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS.toInt()}° of bends between pull points, offsets and kicks included:"),
                Bullets(ConduitType.entries.map { "${it.displayName}: ${RacewayBends.section(it)}" }.distinct()),
                ToolLink("Plan a pull", ToolIds.PULL_PLANNER),
            )
        },
        RefEntry(
            "nec-working-space", "Working space", RefSection.FIELD,
            "Clear space in front of panels and other equipment that may be worked on energized.",
            RefSource.CodeRef(CodeRule.WORKING_SPACE),
            tools = listOf(ToolIds.PANELS),
            keywords = listOf("clearance", "working space", "36 in", "3 ft", "panel", "110.26"),
        ) {
            listOf(
                Table(
                    listOf("Volts to ground") + WorkingSpaceCondition.entries.map { it.label },
                    WorkingSpace.rows.map { r -> listOf(r.voltsToGround) + r.depthIn.zip(r.depthMm).map { (inch, mm) -> "${feetInches(inch)} ($mm mm)" } },
                    "Depth, measured from exposed live parts or the enclosure front.",
                ),
                Bullets(WorkingSpaceCondition.entries.map { "${it.label}: ${it.description}." }),
                Paragraph("Width: at least ${WorkingSpace.MIN_WIDTH_IN} in. or the equipment's width, whichever is more, with room for doors to open 90°. Height: at least 6-1/2 ft or the equipment's height."),
            )
        },
        RefEntry(
            "nec-motor-flc-three-phase", "Three-phase motor full-load current", RefSection.MOTORS,
            "Table full-load current of three-phase induction motors, for sizing conductors and protection.",
            RefSource.CodeRef(listOf(CodeRule.MOTOR_FLC_THREE_PHASE, CodeRule.MOTOR_TABLE_FLC)),
            tools = listOf(ToolIds.VFD_SIZING),
            keywords = listOf("motor", "fla", "flc", "hp", "horsepower", "3 phase", "430.250"),
        ) { listOf(motorTable(AcPhases.THREE), motorNote(), ToolLink("Size a motor circuit with a drive", ToolIds.VFD_SIZING)) },
        RefEntry(
            "nec-motor-flc-single-phase", "Single-phase motor full-load current", RefSection.MOTORS,
            "Table full-load current of single-phase motors.",
            RefSource.CodeRef(listOf(CodeRule.MOTOR_FLC_SINGLE_PHASE, CodeRule.MOTOR_TABLE_FLC)),
            keywords = listOf("motor", "fla", "flc", "hp", "horsepower", "single phase", "430.248"),
        ) { listOf(motorTable(AcPhases.SINGLE), motorNote()) },
        RefEntry(
            "nec-motor-protection", "Motor branch-circuit protection", RefSection.MOTORS,
            "Largest fuse or breaker for a motor branch circuit, as a percent of full-load current.",
            RefSource.CodeRef(listOf(CodeRule.MOTOR_BRANCH_PROTECTION, CodeRule.MOTOR_NEXT_HIGHER_RATING, CodeRule.MOTOR_CONDUCTORS, CodeRule.MOTOR_OVERLOAD, CodeRule.MOTOR_DISCONNECT_RATING)),
            tools = listOf(ToolIds.VFD_SIZING),
            keywords = listOf("motor", "breaker", "fuse", "250%", "175%", "short circuit", "overload", "disconnect"),
        ) {
            listOf(
                Table(
                    listOf("Motor") + MotorProtectiveDevice.entries.map { it.label },
                    MotorType.entries.map { m -> listOf(m.label) + MotorProtectiveDevice.entries.map { "${MotorProtection.percent(m, it)}%" } },
                    "Percent of the table full-load current.",
                ),
                Bullets(
                    listOf(
                        "If the result isn't a standard size, the next larger standard size is allowed (${CodeRule.MOTOR_NEXT_HIGHER_RATING.cite}).",
                        "Conductors for one motor: at least 125% of full-load current (${CodeRule.MOTOR_CONDUCTORS.cite}).",
                        "Overloads: no more than 125% of nameplate current for a service factor of 1.15 or more or a 40 °C rise, 115% otherwise (${CodeRule.MOTOR_OVERLOAD.cite}).",
                        "Disconnect: rated at least 115% of full-load current (${CodeRule.MOTOR_DISCONNECT_RATING.cite}).",
                    ),
                ),
                ToolLink("Size a motor circuit with a drive", ToolIds.VFD_SIZING),
            )
        },
    )

    private fun motorTable(phase: AcPhases): Table {
        val volts = MotorFullLoadCurrent.voltages(phase)
        val hps = volts.flatMap { MotorFullLoadCurrent.horsepowers(phase, it) }.distinct().sorted()
        return Table(
            listOf("HP") + volts.map { "$it V" },
            hps.map { hp -> listOf(MotorFullLoadCurrent.label(hp)) + volts.map { v -> n(MotorFullLoadCurrent.amps(phase, hp, v), 1) } },
            "Amperes. Use the column for the system: 115 V for 110–120 V, 230 V for 220–240 V, 460 V for 440–480 V, 575 V for 550–600 V.",
        )
    }

    private fun motorNote() = Paragraph(
        "Size conductors, short-circuit protection and disconnects from these table values, not the nameplate (${CodeRule.MOTOR_TABLE_FLC.cite}). Size overloads from the nameplate.",
    )

    private fun feetInches(inches: Int): String {
        val ft = inches / 12
        val rem = inches % 12
        return if (rem == 0) "$ft ft" else "$ft ft $rem in"
    }
}
