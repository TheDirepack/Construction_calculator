package com.thedirepack.constructioncalc.core.electrical.formula

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaQuantity.*
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Every formula in the Electrical Formulas tool and on the Reference formula sheet (one list, so the
 * sheet and the calculator never disagree). Relations are written in base units: V, A, W, VA, VAR,
 * Ω, H, F, Hz, hours, kWh.
 *
 * To add a formula: give it a new stable [Formula.id], list its variables and relations (a solver
 * for each variable that can be isolated), and add a test in FormulaCatalogTest that solves it
 * every way round.
 */
object FormulaCatalog {
    private val SQRT3 = sqrt(3.0)
    private const val WATTS_PER_HP = 746.0

    // ---- Units and variable shorthands -------------------------------------------------------
    private val volts = listOf(FormulaUnit("V"), FormulaUnit("kV", 1e3))
    private val amps = listOf(FormulaUnit("A"), FormulaUnit("mA", 1e-3), FormulaUnit("kA", 1e3))
    private val watts = listOf(FormulaUnit("W"), FormulaUnit("kW", 1e3), FormulaUnit("MW", 1e6))
    private val voltAmps = listOf(FormulaUnit("VA"), FormulaUnit("kVA", 1e3), FormulaUnit("MVA", 1e6))
    private val vars = listOf(FormulaUnit("VAR"), FormulaUnit("kVAR", 1e3))
    private val ohms = listOf(FormulaUnit("Ω"), FormulaUnit("mΩ", 1e-3), FormulaUnit("kΩ", 1e3), FormulaUnit("MΩ", 1e6))

    private fun v(id: String = "V", symbol: String = id, name: String = "Voltage", hint: String? = null) =
        Variable(id, symbol, name, VOLTAGE, volts, hint = hint)

    private fun i(id: String = "I", symbol: String = id, name: String = "Current", zeroOk: Boolean = false) =
        Variable(id, symbol, name, CURRENT, amps, minExclusive = !zeroOk)

    private fun p(id: String = "P", name: String = "Real power", unit: Int = 0) = Variable(id, id, name, POWER, watts, defaultUnit = unit)
    private fun s(unit: Int = 0) = Variable("S", "S", "Apparent power", APPARENT_POWER, voltAmps, defaultUnit = unit)
    private fun q(unit: Int = 0) = Variable("Q", "Q", "Reactive power", REACTIVE_POWER, vars, defaultUnit = unit, minExclusive = false)
    private fun r(id: String = "R", symbol: String = id, name: String = "Resistance", zeroOk: Boolean = false) =
        Variable(id, symbol, name, RESISTANCE, ohms, minExclusive = !zeroOk)

    private fun pf(id: String = "PF", name: String = "Power factor") =
        Variable(id, id, name, RATIO, listOf(FormulaUnit("")), max = 1.0, hint = "0 to 1, e.g. 0.85")

    private fun percent(id: String, symbol: String, name: String, hint: String? = null) =
        Variable(id, symbol, name, PERCENT, listOf(FormulaUnit("%")), max = 100.0, hint = hint)

    private fun hp() = Variable("HP", "HP", "Motor output", HORSEPOWER, listOf(FormulaUnit("hp")))

    // ---- Shared relations --------------------------------------------------------------------
    private fun realPower() = relation("P = S × PF", "P", "S", "PF") {
        solve("P") { it["S"] * it["PF"] }
        solve("S") { it["P"] / it["PF"] }
        solve("PF") { it["P"] / it["S"] }
    }

    private fun powerTriangle() = relation("S² = P² + Q²", "S", "P", "Q") {
        solve("S") { sqrt(it["P"] * it["P"] + it["Q"] * it["Q"]) }
        solve("P") { (it["S"] * it["S"] - it["Q"] * it["Q"]).takeIf { d -> d >= 0 }?.let(::sqrt) }
        solve("Q") { (it["S"] * it["S"] - it["P"] * it["P"]).takeIf { d -> d >= 0 }?.let(::sqrt) }
    }

    /** `S = k × V × I`, k = 1 for single-phase, √3 for three-phase (V line-to-line). */
    private fun apparentPower(threePhase: Boolean): Relation {
        val k = if (threePhase) SQRT3 else 1.0
        return relation(if (threePhase) "S = √3 × V × I" else "S = V × I", "S", "V", "I") {
            solve("S") { k * it["V"] * it["I"] }
            solve("V") { it["S"] / (k * it["I"]) }
            solve("I") { it["S"] / (k * it["V"]) }
        }
    }

    // ---- The formulas ------------------------------------------------------------------------
    private val ohmsLaw = Formula(
        id = "ohms-law",
        title = "Ohm's law and power",
        group = FormulaGroup.BASICS,
        summary = "Voltage, current, resistance and power: enter any two.",
        variables = listOf(v(), i(), r(), p()),
        relations = listOf(
            relation("V = I × R", "V", "I", "R") {
                solve("V") { it["I"] * it["R"] }
                solve("I") { it["V"] / it["R"] }
                solve("R") { it["V"] / it["I"] }
            },
            relation("P = V × I", "P", "V", "I") {
                solve("P") { it["V"] * it["I"] }
                solve("V") { it["P"] / it["I"] }
                solve("I") { it["P"] / it["V"] }
            },
            relation("P = I² × R", "P", "I", "R") {
                solve("P") { it["I"] * it["I"] * it["R"] }
                solve("I") { sqrt(it["P"] / it["R"]) }
                solve("R") { it["P"] / (it["I"] * it["I"]) }
            },
            relation("P = V² ÷ R", "P", "V", "R") {
                solve("P") { it["V"] * it["V"] / it["R"] }
                solve("V") { sqrt(it["P"] * it["R"]) }
                solve("R") { it["V"] * it["V"] / it["P"] }
            },
        ),
        notes = listOf("DC, or AC with a purely resistive load (heaters, incandescent lamps). Other AC loads need a power factor: use single- or three-phase power."),
        keywords = listOf("ohm", "watts", "amps", "volts", "resistance", "power wheel", "dc"),
    )

    private fun acPower(threePhase: Boolean) = Formula(
        id = if (threePhase) "power-three-phase" else "power-single-phase",
        title = if (threePhase) "Three-phase power" else "Single-phase power",
        group = FormulaGroup.AC_POWER,
        summary = if (threePhase) "Amps, kW, kVA, kVAR and power factor for a balanced three-phase load." else "Amps, kW, kVA, kVAR and power factor for a single-phase load.",
        variables = listOf(
            v(name = if (threePhase) "Line-to-line voltage" else "Voltage"),
            i(name = if (threePhase) "Line current" else "Current"),
            pf(), p(unit = 1), s(unit = 1), q(unit = 1),
        ),
        relations = listOf(apparentPower(threePhase), realPower(), powerTriangle()),
        notes = listOf(
            if (threePhase) "Balanced load; V is line-to-line (208, 480…)." else "V is the voltage across the load (120, 240…).",
            "A power factor of 1 makes kW equal kVA (resistive loads).",
        ),
        keywords = listOf("kva", "kw", "kvar", "amps", "power factor", "watts", if (threePhase) "3 phase" else "1 phase", "load"),
    )

    private fun motorCurrent(threePhase: Boolean) = Formula(
        id = if (threePhase) "motor-current-three-phase" else "motor-current-single-phase",
        title = if (threePhase) "Three-phase motor current from horsepower" else "Single-phase motor current from horsepower",
        group = FormulaGroup.MOTORS_TRANSFORMERS,
        summary = "Running current from output horsepower, efficiency and power factor.",
        variables = listOf(
            hp(),
            v(name = if (threePhase) "Line-to-line voltage" else "Voltage"),
            percent("EFF", "η", "Efficiency", hint = "From the nameplate, e.g. 90"),
            pf(),
            i(),
            p("POUT", "Output power", unit = 1),
            p("PIN", "Input power", unit = 1),
        ),
        relations = listOf(
            relation("Pout = HP × 746", "POUT", "HP") {
                solve("POUT") { it["HP"] * WATTS_PER_HP }
                solve("HP") { it["POUT"] / WATTS_PER_HP }
            },
            relation("Pin = Pout ÷ η", "PIN", "POUT", "EFF") {
                solve("PIN") { it["POUT"] / (it["EFF"] / 100) }
                solve("POUT") { it["PIN"] * it["EFF"] / 100 }
                solve("EFF") { it["POUT"] / it["PIN"] * 100 }
            },
            if (threePhase) {
                relation("Pin = √3 × V × I × PF", "PIN", "V", "I", "PF") {
                    solve("PIN") { SQRT3 * it["V"] * it["I"] * it["PF"] }
                    solve("V") { it["PIN"] / (SQRT3 * it["I"] * it["PF"]) }
                    solve("I") { it["PIN"] / (SQRT3 * it["V"] * it["PF"]) }
                    solve("PF") { it["PIN"] / (SQRT3 * it["V"] * it["I"]) }
                }
            } else {
                relation("Pin = V × I × PF", "PIN", "V", "I", "PF") {
                    solve("PIN") { it["V"] * it["I"] * it["PF"] }
                    solve("V") { it["PIN"] / (it["I"] * it["PF"]) }
                    solve("I") { it["PIN"] / (it["V"] * it["PF"]) }
                    solve("PF") { it["PIN"] / (it["V"] * it["I"]) }
                }
            },
        ),
        notes = listOf(
            "Estimates running current. Conductors and short-circuit protection are sized from the NEC table full-load current, not this (${CodeRule.MOTOR_TABLE_FLC.cite}).",
        ),
        toolId = if (threePhase) "vfd-sizing" else null,
        keywords = listOf("motor", "hp", "horsepower", "fla", "efficiency", "amps"),
    )

    private fun transformer(threePhase: Boolean) = Formula(
        id = if (threePhase) "transformer-three-phase" else "transformer-single-phase",
        title = if (threePhase) "Three-phase transformer current and fault current" else "Single-phase transformer current and fault current",
        group = FormulaGroup.MOTORS_TRANSFORMERS,
        summary = "Full-load current from kVA, and the most fault current the transformer can deliver.",
        variables = listOf(
            s(unit = 1),
            v(name = if (threePhase) "Line-to-line voltage" else "Voltage", hint = "Secondary voltage for secondary current"),
            i(name = "Full-load current"),
            percent("Z", "%Z", "Impedance", hint = "Nameplate percent impedance"),
            Variable("ISC", "Isc", "Available fault current", CURRENT, amps, defaultUnit = 2),
        ),
        relations = listOf(
            apparentPower(threePhase),
            relation("Isc = I × 100 ÷ %Z", "ISC", "I", "Z") {
                solve("ISC") { it["I"] * 100 / it["Z"] }
                solve("I") { it["ISC"] * it["Z"] / 100 }
                solve("Z") { it["I"] * 100 / it["ISC"] }
            },
        ),
        notes = listOf("Fault current assumes an unlimited (infinite) source and no conductor impedance, so it's the most the transformer can deliver at its terminals."),
        keywords = listOf("transformer", "kva", "fla", "short circuit", "fault current", "impedance", "aic"),
    )

    private val transformerRatio = Formula(
        id = "transformer-ratio",
        title = "Transformer ratio",
        group = FormulaGroup.MOTORS_TRANSFORMERS,
        summary = "Primary and secondary voltage and current of an ideal transformer.",
        variables = listOf(
            v("VP", "Vp", "Primary voltage"),
            v("VS", "Vs", "Secondary voltage"),
            i("IP", "Ip", "Primary current"),
            i("IS", "Is", "Secondary current"),
            Variable("A", "a", "Turns ratio", RATIO, listOf(FormulaUnit(""))),
        ),
        relations = listOf(
            relation("a = Vp ÷ Vs", "A", "VP", "VS") {
                solve("A") { it["VP"] / it["VS"] }
                solve("VP") { it["A"] * it["VS"] }
                solve("VS") { it["VP"] / it["A"] }
            },
            relation("Is = Ip × a", "IS", "IP", "A") {
                solve("IS") { it["IP"] * it["A"] }
                solve("IP") { it["IS"] / it["A"] }
                solve("A") { it["IS"] / it["IP"] }
            },
        ),
        notes = listOf("Ideal transformer: no losses or magnetizing current. Use line-to-line voltages and line currents on both sides."),
        keywords = listOf("transformer", "turns ratio", "primary", "secondary", "step down"),
    )

    private val wyeDelta = Formula(
        id = "wye-delta",
        title = "Wye and delta relationships",
        group = FormulaGroup.AC_POWER,
        summary = "Line-to-line and line-to-neutral voltage (wye), line and phase current (delta).",
        variables = listOf(
            v("VLL", "VLL", "Line-to-line voltage"),
            v("VLN", "VLN", "Line-to-neutral voltage"),
            i("IL", "IL", "Line current (delta)"),
            i("IPH", "Iph", "Phase (winding) current (delta)"),
        ),
        relations = listOf(
            relation("VLL = √3 × VLN", "VLL", "VLN") {
                solve("VLL") { SQRT3 * it["VLN"] }
                solve("VLN") { it["VLL"] / SQRT3 }
            },
            relation("IL = √3 × Iph", "IL", "IPH") {
                solve("IL") { SQRT3 * it["IPH"] }
                solve("IPH") { it["IL"] / SQRT3 }
            },
        ),
        notes = listOf("In a wye, line current equals phase current. In a delta, line voltage equals phase voltage."),
        keywords = listOf("wye", "delta", "star", "line to neutral", "208", "120", "480", "277", "phase voltage"),
    )

    private val neutralCurrent = Formula(
        id = "neutral-current",
        title = "Neutral current, unbalanced wye",
        group = FormulaGroup.AC_POWER,
        summary = "Neutral current of a three-phase, four-wire wye from the three phase currents.",
        variables = listOf(i("IA", "Ia", "Phase A current", zeroOk = true), i("IB", "Ib", "Phase B current", zeroOk = true), i("IC", "Ic", "Phase C current", zeroOk = true), i("IN", "In", "Neutral current", zeroOk = true)),
        relations = listOf(
            relation("In = √(Ia² + Ib² + Ic² − Ia·Ib − Ib·Ic − Ic·Ia)", "IN", "IA", "IB", "IC") {
                solve("IN") {
                    val a = it["IA"]; val b = it["IB"]; val c = it["IC"]
                    sqrt(maxOf(0.0, a * a + b * b + c * c - a * b - b * c - c * a))
                }
            },
        ),
        notes = listOf("Linear loads at the same power factor. Harmonic (nonlinear) loads can add neutral current this doesn't show."),
        keywords = listOf("neutral", "unbalanced", "imbalance", "wye", "panel balance"),
    )

    private val impedance = Formula(
        id = "impedance",
        title = "Reactance and impedance",
        group = FormulaGroup.CIRCUITS,
        summary = "Inductive and capacitive reactance, and the impedance and power factor of a series R-L-C circuit.",
        variables = listOf(
            Variable("F", "f", "Frequency", FREQUENCY, listOf(FormulaUnit("Hz")), default = 60.0),
            Variable("L", "L", "Inductance", INDUCTANCE, listOf(FormulaUnit("H"), FormulaUnit("mH", 1e-3), FormulaUnit("µH", 1e-6)), defaultUnit = 1),
            Variable("C", "C", "Capacitance", CAPACITANCE, listOf(FormulaUnit("F"), FormulaUnit("µF", 1e-6), FormulaUnit("nF", 1e-9)), defaultUnit = 1),
            r("XL", "XL", "Inductive reactance", zeroOk = true).copy(hint = "0 if there's no inductance"),
            r("XC", "XC", "Capacitive reactance", zeroOk = true).copy(hint = "0 if there's no capacitance"),
            r(zeroOk = true),
            r("Z", "Z", "Impedance"),
            pf(),
        ),
        relations = listOf(
            relation("XL = 2π × f × L", "XL", "F", "L") {
                solve("XL") { 2 * PI * it["F"] * it["L"] }
                solve("F") { it["XL"] / (2 * PI * it["L"]) }
                solve("L") { it["XL"] / (2 * PI * it["F"]) }
            },
            relation("XC = 1 ÷ (2π × f × C)", "XC", "F", "C") {
                solve("XC") { 1 / (2 * PI * it["F"] * it["C"]) }
                solve("F") { 1 / (2 * PI * it["C"] * it["XC"]) }
                solve("C") { 1 / (2 * PI * it["F"] * it["XC"]) }
            },
            relation("Z = √(R² + (XL − XC)²)", "Z", "R", "XL", "XC") {
                solve("Z") { val x = it["XL"] - it["XC"]; sqrt(it["R"] * it["R"] + x * x) }
                solve("R") { val x = it["XL"] - it["XC"]; (it["Z"] * it["Z"] - x * x).takeIf { d -> d >= 0 }?.let(::sqrt) }
            },
            relation("PF = R ÷ Z", "PF", "R", "Z") {
                solve("PF") { it["R"] / it["Z"] }
                solve("R") { it["PF"] * it["Z"] }
                solve("Z") { it["R"] / it["PF"] }
            },
        ),
        keywords = listOf("reactance", "impedance", "inductance", "capacitance", "xl", "xc", "rlc"),
    )

    private val resistors = Formula(
        id = "series-parallel",
        title = "Two resistances in series and parallel",
        group = FormulaGroup.CIRCUITS,
        summary = "Combined resistance of two resistances, or the second one needed for a total.",
        variables = listOf(r("R1", "R1", "First resistance"), r("R2", "R2", "Second resistance"), r("RS", "Rs", "In series"), r("RP", "Rp", "In parallel")),
        relations = listOf(
            relation("Rs = R1 + R2", "RS", "R1", "R2") {
                solve("RS") { it["R1"] + it["R2"] }
                solve("R1") { it["RS"] - it["R2"] }
                solve("R2") { it["RS"] - it["R1"] }
            },
            relation("Rp = R1 × R2 ÷ (R1 + R2)", "RP", "R1", "R2") {
                solve("RP") { it["R1"] * it["R2"] / (it["R1"] + it["R2"]) }
                solve("R1") { (it["R2"] - it["RP"]).takeIf { d -> d > 0 }?.let { d -> it["RP"] * it["R2"] / d } }
                solve("R2") { (it["R1"] - it["RP"]).takeIf { d -> d > 0 }?.let { d -> it["RP"] * it["R1"] / d } }
            },
        ),
        notes = listOf("Works the same for reactances of one kind (all inductive or all capacitive)."),
        keywords = listOf("series", "parallel", "resistor", "combined", "equivalent"),
    )

    private val pfCorrection = Formula(
        id = "pf-correction",
        title = "Power factor correction",
        group = FormulaGroup.AC_POWER,
        summary = "Capacitor kVAR to raise a load's power factor.",
        variables = listOf(p(unit = 1), pf("PF1", "Present power factor"), pf("PF2", "Target power factor"), Variable("QC", "Qc", "Capacitor rating", REACTIVE_POWER, vars, defaultUnit = 1, minExclusive = false)),
        relations = listOf(
            relation("Qc = P × (tan(acos PF1) − tan(acos PF2))", "QC", "P", "PF1", "PF2") {
                fun t(pf: Double) = tan(acos(pf))
                solve("QC") { it["P"] * (t(it["PF1"]) - t(it["PF2"])) }
                solve("P") { (t(it["PF1"]) - t(it["PF2"])).takeIf { d -> d > 0 }?.let { d -> it["QC"] / d } }
                solve("PF1") { cos(atan(it["QC"] / it["P"] + t(it["PF2"]))) }
                solve("PF2") { (t(it["PF1"]) - it["QC"] / it["P"]).takeIf { d -> d >= 0 }?.let { d -> cos(atan(d)) } }
            },
        ),
        notes = listOf("A target above the present power factor gives a positive kVAR. Don't overcorrect past 1 (leading)."),
        keywords = listOf("power factor", "capacitor", "kvar", "correction"),
    )

    private val resistanceTemperature = Formula(
        id = "resistance-temperature",
        title = "Conductor resistance at temperature",
        group = FormulaGroup.CIRCUITS,
        summary = "Corrects a conductor's resistance from one temperature to another.",
        variables = listOf(
            r("R1", "R1", "Resistance at T1"),
            Variable("T1", "T1", "Known temperature", TEMPERATURE, listOf(FormulaUnit("°C")), min = -273.15, default = 75.0, hint = "NEC Chapter 9 Table 8 values are at 75 °C"),
            Variable("T2", "T2", "New temperature", TEMPERATURE, listOf(FormulaUnit("°C")), min = -273.15),
            Variable("ALPHA", "α", "Temperature coefficient", COEFFICIENT, listOf(FormulaUnit("/°C")), default = 0.00323, hint = "Copper 0.00323, aluminum 0.00330 (at 75 °C)"),
            r("R2", "R2", "Resistance at T2"),
        ),
        relations = listOf(
            relation("R2 = R1 × (1 + α × (T2 − T1))", "R2", "R1", "T1", "T2", "ALPHA") {
                solve("R2") { it["R1"] * (1 + it["ALPHA"] * (it["T2"] - it["T1"])) }
                solve("R1") { it["R2"] / (1 + it["ALPHA"] * (it["T2"] - it["T1"])) }
                solve("T2") { it["T1"] + (it["R2"] / it["R1"] - 1) / it["ALPHA"] }
                solve("T1") { it["T2"] - (it["R2"] / it["R1"] - 1) / it["ALPHA"] }
                solve("ALPHA") { (it["R2"] / it["R1"] - 1) / (it["T2"] - it["T1"]) }
            },
        ),
        notes = listOf("The coefficients are the ones in the notes to ${CodeRule.CONDUCTOR_PROPERTIES.cite}."),
        keywords = listOf("resistance", "temperature", "alpha", "correction", "table 8"),
    )

    private fun voltageDrop(threePhase: Boolean) = Formula(
        id = if (threePhase) "voltage-drop-three-phase" else "voltage-drop-single-phase",
        title = if (threePhase) "Voltage drop, three-phase (K factor)" else "Voltage drop, single-phase (K factor)",
        group = FormulaGroup.BASICS,
        summary = "Quick voltage drop, or the circular mils or length for a drop you choose.",
        variables = listOf(
            Variable("K", "K", "K factor", COEFFICIENT, listOf(FormulaUnit("Ω·cmil/ft")), default = 12.9, hint = "Copper 12.9, aluminum 21.2 (at 75 °C)"),
            i(),
            Variable("L", "L", "One-way length", LENGTH, listOf(FormulaUnit("ft"))),
            Variable("CM", "CM", "Conductor area", CONDUCTOR_AREA, listOf(FormulaUnit("cmil"), FormulaUnit("kcmil", 1e3)), hint = "12 AWG = 6,530; 10 AWG = 10,380"),
            v("VD", "VD", "Voltage drop"),
            v(name = "Supply voltage"),
            percent("PCT", "VD%", "Voltage drop percent"),
        ),
        relations = listOf(
            if (threePhase) {
                relation("VD = √3 × K × I × L ÷ CM", "VD", "K", "I", "L", "CM") {
                    solve("VD") { SQRT3 * it["K"] * it["I"] * it["L"] / it["CM"] }
                    solve("CM") { SQRT3 * it["K"] * it["I"] * it["L"] / it["VD"] }
                    solve("L") { it["VD"] * it["CM"] / (SQRT3 * it["K"] * it["I"]) }
                    solve("I") { it["VD"] * it["CM"] / (SQRT3 * it["K"] * it["L"]) }
                    solve("K") { it["VD"] * it["CM"] / (SQRT3 * it["I"] * it["L"]) }
                }
            } else {
                relation("VD = 2 × K × I × L ÷ CM", "VD", "K", "I", "L", "CM") {
                    solve("VD") { 2 * it["K"] * it["I"] * it["L"] / it["CM"] }
                    solve("CM") { 2 * it["K"] * it["I"] * it["L"] / it["VD"] }
                    solve("L") { it["VD"] * it["CM"] / (2 * it["K"] * it["I"]) }
                    solve("I") { it["VD"] * it["CM"] / (2 * it["K"] * it["L"]) }
                    solve("K") { it["VD"] * it["CM"] / (2 * it["I"] * it["L"]) }
                }
            },
            relation("VD% = VD ÷ V × 100", "PCT", "VD", "V") {
                solve("PCT") { it["VD"] / it["V"] * 100 }
                solve("VD") { it["PCT"] * it["V"] / 100 }
                solve("V") { it["VD"] / it["PCT"] * 100 }
            },
        ),
        notes = listOf("A DC estimate. The Voltage Drop tool uses the ${CodeRule.CONDUCTOR_PROPERTIES.cite} and ${CodeRule.AC_IMPEDANCE.cite} values for an exact wire, conduit and power factor."),
        toolId = "voltage-drop",
        keywords = listOf("voltage drop", "vd", "k factor", "circular mils", "length", "wire size"),
    )

    private val energy = Formula(
        id = "energy-cost",
        title = "Energy and cost",
        group = FormulaGroup.ENERGY,
        summary = "Energy used by a load over time, and what it costs.",
        variables = listOf(
            p(unit = 1),
            Variable("T", "t", "Time", TIME, listOf(FormulaUnit("h"))),
            Variable("E", "E", "Energy", ENERGY, listOf(FormulaUnit("kWh"), FormulaUnit("Wh", 1e-3), FormulaUnit("MWh", 1e3))),
            Variable("RATE", "Rate", "Price", MONEY, listOf(FormulaUnit("$/kWh")), minExclusive = false),
            Variable("COST", "Cost", "Cost", MONEY, listOf(FormulaUnit("$")), minExclusive = false),
        ),
        relations = listOf(
            relation("E = P × t", "E", "P", "T") {
                solve("E") { it["P"] * it["T"] / 1000 }
                solve("P") { it["E"] * 1000 / it["T"] }
                solve("T") { it["E"] * 1000 / it["P"] }
            },
            relation("Cost = E × rate", "COST", "E", "RATE") {
                solve("COST") { it["E"] * it["RATE"] }
                solve("RATE") { it["COST"] / it["E"] }
                solve("E") { it["RATE"].takeIf { r -> r > 0 }?.let { r -> it["COST"] / r } }
            },
        ),
        keywords = listOf("energy", "kwh", "cost", "bill", "hours", "running cost"),
    )

    private val efficiency = Formula(
        id = "efficiency",
        title = "Efficiency and losses",
        group = FormulaGroup.ENERGY,
        summary = "Input power, output power, efficiency and the power lost as heat.",
        variables = listOf(p("PIN", "Input power"), p("POUT", "Output power"), percent("EFF", "η", "Efficiency"), p("LOSS", "Losses").copy(minExclusive = false)),
        relations = listOf(
            relation("Pout = Pin × η", "POUT", "PIN", "EFF") {
                solve("POUT") { it["PIN"] * it["EFF"] / 100 }
                solve("PIN") { it["POUT"] / (it["EFF"] / 100) }
                solve("EFF") { it["POUT"] / it["PIN"] * 100 }
            },
            relation("Losses = Pin − Pout", "LOSS", "PIN", "POUT") {
                solve("LOSS") { it["PIN"] - it["POUT"] }
                solve("PIN") { it["POUT"] + it["LOSS"] }
                solve("POUT") { it["PIN"] - it["LOSS"] }
            },
        ),
        notes = listOf("Losses become heat: 1 W ≈ 3.412 BTU/h."),
        keywords = listOf("efficiency", "losses", "heat", "input", "output"),
    )

    val all: List<Formula> = listOf(
        ohmsLaw,
        voltageDrop(threePhase = false),
        voltageDrop(threePhase = true),
        acPower(threePhase = false),
        acPower(threePhase = true),
        wyeDelta,
        neutralCurrent,
        pfCorrection,
        motorCurrent(threePhase = true),
        motorCurrent(threePhase = false),
        transformer(threePhase = true),
        transformer(threePhase = false),
        transformerRatio,
        impedance,
        resistors,
        resistanceTemperature,
        energy,
        efficiency,
    )

    fun find(id: String): Formula? = all.firstOrNull { it.id == id }

    val default: Formula get() = ohmsLaw

    init {
        val dupes = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(dupes.isEmpty()) { "Duplicate formula ids: $dupes" }
    }
}
