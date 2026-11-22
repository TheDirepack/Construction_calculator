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
import com.thedirepack.constructioncalc.core.electrical.LightingOccupancy
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
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.codeLength
import com.thedirepack.constructioncalc.core.electrical.code.codeNumber
import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import kotlin.math.roundToInt
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Bullets
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Choice
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Paragraph
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Table
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.ToolLink
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Calculator
import com.thedirepack.constructioncalc.core.electrical.BoxFill
import com.thedirepack.constructioncalc.core.electrical.BurialWiringMethod
import com.thedirepack.constructioncalc.core.electrical.GecBonding
import com.thedirepack.constructioncalc.core.electrical.RacewayInstall

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
            val v = Code.book.values
            listOf(
                Bullets(
                    listOf(
                        "Circuits rated ${v.terminationLowColumnMaxAmps} A or less, or ${ConductorSize.entries.first().label} through ${v.terminationLowColumnMaxSize.label}: use the 60 °C column, unless the equipment is listed and marked for 75 °C.",
                        "Circuits over ${v.terminationLowColumnMaxAmps} A, or conductors larger than ${v.terminationLowColumnMaxSize.label}: use the 75 °C column, unless the equipment is listed and marked higher.",
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
            val bands = AmbientCorrection.bands
            val rows = bands.mapIndexed { idx, band ->
                val c = band.maxC.toInt()
                val f = Measure.TEMPERATURE.fromBase(band.maxC, UnitSystem.IMPERIAL).toInt()
                val prev = bands.getOrNull(idx - 1)?.maxC?.toInt()
                val cRange = if (prev == null) "$c or less" else "${prev + 1}–$c"
                val fRange = if (prev == null) "$f or less" else "${Measure.TEMPERATURE.fromBase(prev.toDouble(), UnitSystem.IMPERIAL).toInt() + 1}–$f"
                listOf(cRange, fRange) + TemperatureRating.entries.map { n(band.of(it), 2) }
            }
            val basis = Code.book.values.ambientBasisC
            listOf(
                Table(listOf("°C", "°F", "60 °C", "75 °C", "90 °C"), rows, "Factors for a ${Measure.TEMPERATURE.format(basis, UnitSystem.METRIC, 0)} (${Measure.TEMPERATURE.format(basis, UnitSystem.IMPERIAL, 0)}) table basis. A dash: too hot for that insulation."),
                Paragraph("Raceways or cables in sunlight on a rooftop, less than 3/4 in. (19 mm) above it: add ${AmbientCorrection.rooftopAdderText(UnitSystem.METRIC).drop(1)} (${AmbientCorrection.rooftopAdderText(UnitSystem.IMPERIAL).drop(1)}) to the outdoor temperature (${CodeRule.ROOFTOP_ADDER.cite}). Type XHHW-2 is exempt."),
            )
        },
        RefEntry(
            "nec-adjustment", "More than three current-carrying conductors", RefSection.CONDUCTORS,
            "Adjustment factors when a raceway or cable holds more than three current-carrying conductors.",
            RefSource.CodeRef(listOf(CodeRule.CONDUCTOR_COUNT_ADJUSTMENT, CodeRule.NEUTRAL_COUNTING, CodeRule.GROUNDING_COUNTING)),
            tools = listOf(ToolIds.PANELS, ToolIds.CONDUIT_FILL),
            keywords = listOf("bundling", "derate", "derating", "adjustment", "current carrying", "ccc", "neutral"),
        ) { ctx ->
            val rows = ConductorCountAdjustment.bands.map { b ->
                listOf(if (b.to == null) "${b.from} and above" else "${b.from}–${b.to}", codePercent(b.factor, spaced = false))
            }
            listOf(
                Table(listOf("Current-carrying conductors", "Percent of ampacity"), rows, "Apply together with any ambient correction."),
                Bullets(
                    listOf(
                        "A neutral carrying only the unbalanced current of its circuit doesn't count. It does count on a 3-wire circuit from a 4-wire wye (two phases and the neutral), and where most of the load is nonlinear (${CodeRule.NEUTRAL_COUNTING.cite}).",
                        "Equipment grounding and bonding conductors don't count (${CodeRule.GROUNDING_COUNTING.cite}).",
                        "Raceways ${Code.book.values.let { codeLength(it.nippleMaxLengthIn, it.nippleMaxLengthMm, ctx.system) }} or shorter (nipples) need no adjustment (${CodeRule.SHORT_RACEWAY_ADJUSTMENT.cite}).",
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
            "nec-load-demand", "Load calculation demand factors", RefSection.LOADS,
            "Demand factors for feeder and service load calculations: lighting, receptacles, dryers and ranges.",
            RefSource.CodeRef(listOf(CodeRule.LIGHTING_DEMAND, CodeRule.RECEPTACLE_DEMAND, CodeRule.DRYER_DEMAND, CodeRule.RANGE_DEMAND)),
            tools = listOf(ToolIds.PANELS),
            keywords = listOf("article 220", "demand factor", "load calculation", "service size", "feeder", "dryer", "range", "220.45", "220.47", "220.54", "220.55"),
        ) {
            val lc = Code.book.loadCalculation
            fun va(v: Double) = "%,d VA".format(v.roundToInt())
            fun tiers(list: List<com.thedirepack.constructioncalc.core.electrical.code.DemandTier>): List<List<String>> {
                var from = 0.0
                return list.map { t ->
                    val label = when (val to = t.upToVa) {
                        null -> "Remainder over ${va(from)}"
                        else -> if (from == 0.0) "First ${va(to)}" else "${va(from)} to ${va(to)}"
                    }
                    from = t.upToVa ?: from
                    listOf(label, codePercent(t.factor))
                }
            }
            val lighting = LightingOccupancy.entries.flatMap { occ ->
                val rows = lc.lightingDemand[occ] ?: return@flatMap listOf(listOf(occ.label, "Total", codePercent(1.0)))
                tiers(rows).map { (portion, pct) -> listOf(occ.label, portion, pct) }
            }
            val dryers = lc.dryerDemand.map { r ->
                val count = when {
                    r.to == null -> "${r.from} and over"
                    r.to == r.from -> "${r.from}"
                    else -> "${r.from}–${r.to}"
                }
                val pct = if (r.minusPercentPerDryer > 0.0) {
                    "${codePercentValue(r.percent)} less ${codePercentValue(r.minusPercentPerDryer)} per dryer over ${r.from - 1}"
                } else {
                    codePercentValue(r.percent)
                }
                listOf(count, pct)
            }
            val ranges = lc.rangeDemand.map { r ->
                val count = when {
                    r.to == null -> "${r.from} and over"
                    r.to == r.from -> "${r.from}"
                    else -> "${r.from}–${r.to}"
                }
                val c = if (r.columnCKwPerRange > 0.0) "${n(r.columnCKw, 1)} + ${n(r.columnCKwPerRange, 2)} per range" else n(r.columnCKw, 1)
                listOf(count, codePercentValue(r.columnAPercent), codePercentValue(r.columnBPercent), c)
            }
            listOf(
                Paragraph("General lighting (${CodeRule.LIGHTING_DEMAND.cite})"),
                Table(listOf("Occupancy", "Portion of load", "Demand factor"), lighting),
                Paragraph("Non-dwelling receptacles (${CodeRule.RECEPTACLE_DEMAND.cite})"),
                Table(listOf("Portion of load", "Demand factor"), tiers(lc.receptacleDemand)),
                Paragraph("Household clothes dryers (${CodeRule.DRYER_DEMAND.cite}), ${va(lc.dryerMinVa)} minimum each"),
                Table(listOf("Dryers", "Demand factor"), dryers),
                Paragraph("Household ranges and cooking appliances (${CodeRule.RANGE_DEMAND.cite})"),
                Table(
                    listOf("Appliances", "Col. A (under ${n(lc.rangeColumnAMaxKw, 2)} kW)", "Col. B (${n(lc.rangeColumnAMaxKw, 2)}–${n(lc.rangeColumnBMaxKw, 2)} kW)", "Col. C max kW (to ${n(lc.rangeColumnCMaxKw, 1)} kW)"),
                    ranges,
                    "Over ${n(lc.rangeColumnCMaxKw, 1)} kW up to ${n(lc.rangeNote1MaxKw, 1)} kW: increase Column C by ${codePercent(lc.rangeNote1IncreasePerKw)} for each kW, or major fraction, over ${n(lc.rangeColumnCMaxKw, 1)} kW.",
                ),
                Bullets(
                    listOf(
                        "Small-appliance and laundry circuits: ${va(lc.smallApplianceCircuitVa)} each (${CodeRule.SMALL_APPLIANCE_LAUNDRY.cite}).",
                        "${lc.dwellingApplianceMinCount} or more fastened-in-place appliances in a dwelling: ${codePercent(lc.dwellingApplianceFactor)} of their total (${CodeRule.DWELLING_APPLIANCE_DEMAND.cite}).",
                        "Add ${codePercent(lc.largestMotorAdder)} of the largest motor's full-load current to the feeder (${CodeRule.FEEDER_MOTOR_LOAD.cite}).",
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
        ) { ctx ->
            val v = Code.book.values
            listOf(
                Table(
                    listOf("Conductors", "Maximum fill"),
                    listOf(
                        listOf("1", "${ConduitFill.limitPercent(1)}%"),
                        listOf("2", "${ConduitFill.limitPercent(2)}%"),
                        listOf("Over 2", "${ConduitFill.limitPercent(3)}%"),
                        listOf("Nipple, ${codeLength(v.nippleMaxLengthIn, v.nippleMaxLengthMm, ctx.system)} or shorter", "${ConduitFill.limitPercent(3, nipple = true)}%"),
                    ),
                ),
                Bullets(
                    listOf(
                        "Equipment grounding conductors count toward fill (${CodeRule.FILL_GROUNDING_CONDUCTORS.cite}).",
                        "All conductors the same size: a count ending in .${(v.note7RoundUpFraction * 10).roundToInt()} or more rounds up (${CodeRule.FILL_ROUNDING.cite}).",
                        "Three conductors can jam in a bend when the conduit's inside diameter is ${codeNumber(ConduitFill.JAM_LOW)} to ${codeNumber(ConduitFill.JAM_HIGH)} times a conductor's diameter (${CodeRule.JAM_RATIO.cite}).",
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
            // Table 4's columns after 100 %: over 2 conductors, 1, 2 and nipple.
            val v = Code.book.values
            val fills = listOf(v.fillOverTwoConductorsPercent, v.fillOneConductorPercent, v.fillTwoConductorsPercent, v.fillNipplePercent)
            listOf(
                Choice("Conduit", types.map { it.displayName }) { pick ->
                    val type = types[pick]
                    val rows = ConduitTable.sizes(type).map { c ->
                        listOf(c.tradeSize.display, c.tradeSize.metricDesignator.toString(), len(c.internalDiameterIn, ctx)) +
                            (listOf(c.area100SqIn) + fills.map { c.allowedAreaSqIn(it) }).map { a ->
                                if (ctx.metric) area(a, ctx) else n(a, 3)
                            }
                    }
                    listOf(Table(listOf("Trade size", "Metric", "ID ${lenUnit(ctx)}", "100% ${areaUnit(ctx)}") + fills.map { "$it%" }, rows, "${type.fullName}, ${type.article}."))
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
            "Minimum radius of conduit bends, and the ${RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS.toInt()}° limit between pull points.",
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
        ) { ctx ->
            val v = Code.book.values
            listOf(
                Table(
                    listOf("Volts to ground") + WorkingSpaceCondition.entries.map { it.label },
                    WorkingSpace.rows.map { r -> listOf(r.voltsToGround) + r.depthIn.zip(r.depthMm).map { (inch, mm) -> "${feetInches(inch)} ($mm mm)" } },
                    "Depth, measured from exposed live parts or the enclosure front.",
                ),
                Bullets(WorkingSpaceCondition.entries.map { "${it.label}: ${it.description}." }),
                Paragraph("Width: at least ${codeLength(v.workingSpaceMinWidthIn, v.workingSpaceMinWidthMm, ctx.system)} or the equipment's width, whichever is more, with room for doors to open 90°. Height: at least ${codeLength(WorkingSpace.MIN_HEIGHT_IN, v.workingSpaceMinHeightMm, ctx.system)} or the equipment's height."),
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
            val mv = Code.book.values
            listOf(
                Table(
                    listOf("Motor") + MotorProtectiveDevice.entries.map { it.label },
                    MotorType.entries.map { m -> listOf(m.label) + MotorProtectiveDevice.entries.map { codePercentValue(MotorProtection.percent(m, it), spaced = false) } },
                    "Percent of the table full-load current.",
                ),
                Bullets(
                    listOf(
                        "If the result isn't a standard size, the next larger standard size is allowed (${CodeRule.MOTOR_NEXT_HIGHER_RATING.cite}).",
                        "Conductors for one motor: at least ${codePercent(mv.motorConductorFactor, spaced = false)} of full-load current (${CodeRule.MOTOR_CONDUCTORS.cite}).",
                        "Overloads: no more than ${codePercent(mv.motorOverloadFactor, spaced = false)} of nameplate current for a service factor of ${codeNumber(mv.motorOverloadServiceFactor)} or more or a ${codeNumber(mv.motorOverloadMaxRiseC)} °C rise, ${codePercent(mv.motorOverloadFactorOther, spaced = false)} otherwise (${CodeRule.MOTOR_OVERLOAD.cite}).",
                        "Disconnect: rated at least ${codePercent(mv.motorDisconnectFactor, spaced = false)} of full-load current (${CodeRule.MOTOR_DISCONNECT_RATING.cite}).",
                    ),
                ),
                ToolLink("Size a motor circuit with a drive", ToolIds.VFD_SIZING),
            )
        },
        RefEntry(
            "nec-motor-circuit", "Single-motor branch circuit", RefSection.MOTORS,
            "Conductors, short-circuit protection, overloads, disconnect and ground for one across-the-line motor.",
            RefSource.CodeRef(listOf(CodeRule.MOTOR_TABLE_FLC, CodeRule.MOTOR_CONDUCTORS, CodeRule.MOTOR_BRANCH_PROTECTION, CodeRule.MOTOR_OVERLOAD, CodeRule.MOTOR_DISCONNECT_RATING, CodeRule.EGC_SIZE)),
            keywords = listOf("motor", "across the line", "dol", "starter", "branch circuit", "overload", "heater", "430"),
        ) {
            val mv = Code.book.values
            listOf(
                Calculator(QuickCalc.MOTOR_CIRCUIT),
                Bullets(
                    listOf(
                        "Full-load current from the tables, not the nameplate (${CodeRule.MOTOR_TABLE_FLC.cite}).",
                        "Conductor ampacity at least ${codePercent(mv.motorConductorFactor, spaced = false)} of it (${CodeRule.MOTOR_CONDUCTORS.cite}), in the column the terminals allow.",
                        "Short-circuit and ground-fault device up to the ${CodeRule.MOTOR_BRANCH_PROTECTION.cite} percentage, next size up allowed (${CodeRule.MOTOR_NEXT_HIGHER_RATING.cite}).",
                        "Overloads from the nameplate current (${CodeRule.MOTOR_OVERLOAD.cite}); disconnect at least ${codePercent(mv.motorDisconnectFactor, spaced = false)} of the table current (${CodeRule.MOTOR_DISCONNECT_RATING.cite}).",
                        "Equipment ground from the short-circuit device rating (${CodeRule.EGC_SIZE.cite}).",
                    ),
                ),
            )
        },
        RefEntry(
            "nec-box-fill", "Box fill", RefSection.RACEWAY,
            "Cubic inches each conductor, clamp, device and ground takes up, and standard metal box volumes.",
            RefSource.CodeRef(listOf(CodeRule.BOX_FILL, CodeRule.BOX_VOLUME_ALLOWANCE, CodeRule.METAL_BOX_VOLUMES)),
            tools = listOf(ToolIds.CONDUIT_FILL),
            keywords = listOf("box", "box fill", "cubic inch", "device box", "4 square", "1900", "314.16", "yoke", "clamp"),
        ) { ctx ->
            val v = Code.book.values
            listOf(
                Calculator(QuickCalc.BOX_FILL),
                Table(
                    listOf("Conductor", "Volume each (${volUnit(ctx)})"),
                    BoxFill.supportedSizes.map { listOf(it.label, vol(BoxFill.volumeAllowanceCuIn(it), ctx)) },
                ),
                Bullets(
                    listOf(
                        "Each conductor that starts, ends or is spliced in the box: one allowance for its size. An unbroken loop of ${codeLength(v.boxFillLoopLengthIn, v.boxFillLoopLengthMm, ctx.system)} or more counts twice.",
                        "Internal cable clamps, one or more: one allowance for the largest conductor.",
                        "Each luminaire stud or hickey: one allowance for the largest conductor.",
                        "Each device yoke or strap: ${v.boxFillDeviceYokeAllowances} allowances for the largest conductor connected to it.",
                        "Equipment grounding conductors: one allowance for the largest, covering up to ${v.boxFillEgcCountedAsOne}; ${codeNumber(v.boxFillExtraEgcFraction)} allowance for each one after that.",
                        "Conductors that don't leave the box don't count, and the code's exceptions leave out some fixture wires under a luminaire canopy.",
                    ),
                ),
                Table(
                    listOf("Metal box", "Volume (${volUnit(ctx)})"),
                    BoxFill.standardMetalBoxes.map { listOf(it.name, vol(it.volumeCuIn, ctx)) },
                    "Trade sizes in inches. Nonmetallic and other boxes: use the volume marked on the box.",
                ),
            )
        },
        RefEntry(
            "nec-pull-box", "Pull and junction box size", RefSection.RACEWAY,
            "Minimum box dimensions for conductors 4 AWG and larger: straight, angle and U pulls.",
            RefSource.CodeRef(listOf(CodeRule.PULL_BOX_STRAIGHT, CodeRule.PULL_BOX_ANGLE)),
            tools = listOf(ToolIds.PULL_PLANNER, ToolIds.CONDUIT_FILL),
            keywords = listOf("pull box", "junction box", "j box", "314.28", "straight pull", "angle pull", "u pull", "8 times", "6 times"),
        ) {
            val v = Code.book.values
            listOf(
                Calculator(QuickCalc.PULL_BOX),
                Bullets(
                    listOf(
                        "Straight pull: length at least ${v.pullBoxStraightMultiplier} × the trade size of the largest raceway (${CodeRule.PULL_BOX_STRAIGHT.cite}).",
                        "Angle or U pull: from each raceway entry to the opposite wall, at least ${v.pullBoxAngleMultiplier} × the largest raceway in that row plus the trade sizes of the other raceways in the same row on that wall (${CodeRule.PULL_BOX_ANGLE.cite}).",
                        "Entries enclosing the same conductor: at least ${v.pullBoxAngleMultiplier} × the larger raceway apart.",
                        "Conductors 6 AWG and smaller follow box fill instead.",
                    ),
                ),
            )
        },
        RefEntry(
            "nec-gec", "Grounding electrode conductor and bonding jumpers", RefSection.PROTECTION,
            "Grounding electrode conductor and main or supply-side bonding jumper by service conductor size.",
            RefSource.CodeRef(listOf(CodeRule.GEC_SIZE, CodeRule.SUPPLY_SIDE_BONDING, CodeRule.GEC_GROUND_ROD_MAX, CodeRule.GEC_CONCRETE_ENCASED_MAX)),
            tools = listOf(ToolIds.PANELS),
            keywords = listOf("gec", "grounding electrode", "ground rod", "ufer", "bonding jumper", "main bonding", "250.66", "250.102"),
        ) {
            val v = Code.book.values
            listOf(
                Calculator(QuickCalc.GEC),
                Table(
                    listOf("Copper service", "Aluminum service", "GEC copper", "GEC aluminum"),
                    GecBonding.table.map { listOf(it.copperRangeLabel, it.aluminumRangeLabel, it.gecCopper.label, it.gecAluminum.label) },
                    "Size of the largest ungrounded service conductor, or the equivalent area of parallel sets.",
                ),
                Bullets(
                    listOf(
                        "Only connected to rod, pipe or plate electrodes: never more than ${v.gecRodMaxCopper.label} copper or ${v.gecRodMaxAluminum.label} aluminum (${CodeRule.GEC_GROUND_ROD_MAX.cite}).",
                        "Only connected to a concrete-encased electrode: never more than ${v.gecConcreteEncasedMaxCopper.label} copper (${CodeRule.GEC_CONCRETE_ENCASED_MAX.cite}).",
                        "Main, system and supply-side bonding jumpers use the same rows up to the last; beyond it, at least ${codePercent(v.bondingJumperLargeAreaFraction, spaced = false)} of the largest ungrounded conductor's area (${CodeRule.SUPPLY_SIDE_BONDING.cite}).",
                    ),
                ),
            )
        },
        RefEntry(
            "nec-burial-depth", "Burial depth", RefSection.RACEWAY,
            "Minimum cover over underground cables and raceways, 1000 V and below.",
            RefSource.CodeRef(CodeRule.BURIAL_DEPTH),
            keywords = listOf("burial", "bury", "underground", "cover", "trench", "depth", "uf", "300.5"),
        ) { ctx ->
            val rows = RacewayInstall.burialDepths
            listOf(
                Table(
                    listOf("Location") + BurialWiringMethod.entries.map { "${it.shortLabel} (${lenUnit(ctx)})" },
                    rows.map { r -> listOf(r.location) + BurialWiringMethod.entries.map { m -> cover(r.coverInches.getOrNull(m.columnIndex), ctx) } },
                    "Cover is measured from the top of the cable or raceway to finished grade or the top of the concrete.",
                ),
                Bullets(BurialWiringMethod.entries.map { "${it.shortLabel}: ${it.label}" } + rows.mapNotNull { r -> r.note?.let { "${r.location}: $it" } }),
            )
        },
        RefEntry(
            "nec-raceway-support", "Raceway support spacing", RefSection.RACEWAY,
            "How close to boxes and how far apart conduit and tubing must be secured.",
            RefSource.CodeRef(CodeRule.RACEWAY_SUPPORT),
            tools = listOf(ToolIds.CONDUIT_BENDING),
            keywords = listOf("support", "strap", "secure", "spacing", "emt", "pvc", "rigid", "within 3 ft", "10 ft"),
        ) { ctx ->
            val types = ConduitType.entries.filter { RacewayInstall.supportRule(it) != null }
            listOf(
                Calculator(QuickCalc.RACEWAY_SUPPORT),
                Table(
                    listOf("Raceway", "Section", "Within box (${feetUnit(ctx)})", "Between supports (${feetUnit(ctx)})"),
                    types.map { t ->
                        val r = RacewayInstall.supportRule(t)!!
                        listOf(t.displayName, r.section, feet(r.withinBoxFt, ctx), if (r.byTradeSizeFt.isEmpty()) feet(r.defaultIntervalFt, ctx) else "By size")
                    },
                ),
                Choice("Spacing by trade size", types.filter { RacewayInstall.supportRule(it)!!.byTradeSizeFt.isNotEmpty() }.map { it.displayName }) { i ->
                    val t = types.filter { RacewayInstall.supportRule(it)!!.byTradeSizeFt.isNotEmpty() }[i]
                    val r = RacewayInstall.supportRule(t)!!
                    listOfNotNull(
                        Table(listOf("Trade size", "Between supports (${feetUnit(ctx)})"), r.byTradeSizeFt.entries.sortedBy { it.key.ordinal }.map { (size, ft) -> listOf(size.label, feet(ft, ctx)) }),
                        r.note?.let { Paragraph(it) },
                    )
                },
            )
        },
        RefEntry(
            "nec-pvc-expansion", "PVC thermal expansion", RefSection.RACEWAY,
            "How much rigid PVC conduit grows and shrinks with temperature, and when it needs an expansion fitting.",
            RefSource.CodeRef(CodeRule.PVC_EXPANSION),
            keywords = listOf("pvc", "expansion", "expansion fitting", "thermal", "352.44", "temperature change"),
        ) { ctx ->
            val v = Code.book.values
            listOf(
                Calculator(QuickCalc.PVC_EXPANSION),
                Paragraph(
                    if (ctx.metric) "Rigid PVC changes length by ${n(v.pvcExpansionInPer100FtPer100F * 25.4 / 30.48 * 1.8 / 100.0, 4)} mm per meter for every °C of temperature change. Use an expansion fitting when the expected change is ${codeLength(v.pvcExpansionFittingThresholdIn, v.pvcExpansionFittingThresholdMm, ctx.system)} or more."
                    else "Rigid PVC changes length by ${n(v.pvcExpansionInPer100FtPer100F, 2)} in. per 100 ft for every 100 °F of temperature change. Use an expansion fitting when the expected change is ${codeLength(v.pvcExpansionFittingThresholdIn, v.pvcExpansionFittingThresholdMm, ctx.system)} or more.",
                ),
                Table(
                    if (ctx.metric) listOf("Temperature change (°C)", "Change (mm per 30.48 m)") else listOf("Temperature change (°F)", "Change (in. per 100 ft)"),
                    RacewayInstall.pvcExpansionTable.map { r ->
                        if (ctx.metric) listOf(Decimals.trim(r.tempChangeF * 5.0 / 9.0, 1), Decimals.trim(r.changeInPer100Ft * 25.4, 1))
                        else listOf(r.tempChangeF.toString(), n(r.changeInPer100Ft, 2))
                    },
                ),
            )
        },
    )

    private fun volUnit(ctx: RefContext) = if (ctx.metric) "cm³" else "in³"
    private fun vol(cuIn: Double?, ctx: RefContext) = cuIn?.let { if (ctx.metric) Decimals.trim(it * 16.387064, 1) else Decimals.trim(it, 2) } ?: BLANK
    private fun cover(inches: Int?, ctx: RefContext) = inches?.let { if (ctx.metric) (it * 25).toString() else it.toString() } ?: BLANK
    private fun feetUnit(ctx: RefContext) = if (ctx.metric) "m" else "ft"
    private fun feet(ft: Double, ctx: RefContext) = if (ctx.metric) Decimals.trim(ft * 0.3048, 2) else Decimals.trim(ft, 1)

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
