package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind

/** Values a project's electrical design offers other tools' input fields (Panels publishes them; the value picker lists them). */
object PublishedValues {

    /** A panel's phase currents and connected load. Keys are stable per panel. */
    fun panel(d: PanelDesign): List<ResultValue> {
        val name = d.panel.name
        val id = d.panel.id
        return buildList {
            d.phaseAmps.forEach { (phase, amps) ->
                add(ResultValue.number("panel_${id}_amps_${phase.name.lowercase()}", "$name phase ${phase.name} current", ValueKind.CURRENT, amps, Wire.amps(amps)))
            }
            add(ResultValue.number("panel_${id}_va", "$name connected load", ValueKind.APPARENT_POWER, d.totalVa, Wire.va(d.totalVa)))
            // Connected VA taken as watts (power factor 1): a conservative stand-in for tools that want POWER.
            add(ResultValue.number("panel_${id}_w", "$name connected load (VA taken as W)", ValueKind.POWER, d.totalVa, "${Wire.number(d.totalVa, 0)} W"))
        }
    }

    /** A circuit's wire size (text), length and load current. */
    fun circuit(d: CircuitDesign, lengthText: (Quantity) -> String): List<ResultValue> {
        val label = "${d.panel.name} #${d.circuit.slots.joinToString(",")}"
        val id = d.circuit.id
        return buildList {
            Wire.text(d)?.let { add(ResultValue.text("circuit_${id}_wire", "$label wire", it)) }
            d.lengthInches?.let {
                val q = Quantity.inches(Rational.of(it))
                add(ResultValue.of("circuit_${id}_length", "$label length", q, lengthText(q)))
            }
            d.amps?.let { add(ResultValue.number("circuit_${id}_amps", "$label load current", ValueKind.CURRENT, it, Wire.amps(it))) }
            d.loadVa?.let { add(ResultValue.number("circuit_${id}_va", "$label load", ValueKind.APPARENT_POWER, it, Wire.va(it))) }
        }
    }

    /**
     * A conduit run's length, if set; for a run with a path, also the cable a pull needs per
     * conductor (routed length plus slack, for Reel Length) and the pull's max tension.
     */
    fun run(d: RunDesign, units: UnitSystem = UnitSystem.IMPERIAL, lengthText: (Quantity) -> String): List<ResultValue> {
        val q = d.run.raceway?.length?.toQuantity() ?: return emptyList()
        return buildList {
            add(ResultValue.of("run_${d.run.id}_length", "${d.run.name} length", q, lengthText(q)))
            if (d.run.path.isNotEmpty()) {
                val pull = PullAnalysis.analyze(d.run, d, units)
                pull.cableLengthIn?.let {
                    val needed = Quantity.inches(Rational.of(it))
                    add(ResultValue.of("run_${d.run.id}_cable_needed", "${d.run.name} cable needed per conductor", needed, lengthText(needed)))
                }
                pull.maxTension?.let { add(ResultValue.number("run_${d.run.id}_max_tension", "${d.run.name} max pulling tension", ValueKind.FORCE, it, PullAnalysis.lb(it, units))) }
            }
        }
    }

    /** Everything [design] offers: panel loads, circuit wire, length, current and load, run lengths. */
    fun all(design: ProjectDesign, units: UnitSystem = UnitSystem.IMPERIAL, lengthText: (Quantity) -> String): List<ResultValue> =
        design.panels.flatMap(::panel) +
            design.circuits.values.flatMap { circuit(it, lengthText) } +
            design.runs.values.flatMap { run(it, units, lengthText) }
}
