package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.design.CircuitDesign
import com.thedirepack.constructioncalc.core.model.design.PanelDesign

/** Values the Panels tool offers to other tools' input fields (`ToolHost.publish`). */
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
        }
    }
}
