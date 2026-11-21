package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CircuitPrefillTest {
    private val run = Run(name = "To RTU", raceway = Raceway(ConduitType.EMT, length = Quantity.feet(Rational.of(150)).stored()))

    @Test fun startsFromTheCircuitAsTheDesignSeesIt() {
        val c = Circuit(slot = 1, breakerAmps = 20, loadVa = 1440.0, runIds = listOf(run.id))
        val project = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val inputs = voltageDropInputsForCircuit(project, c.id)!!
        assertEquals(AcPhases.SINGLE, inputs.system)
        assertEquals(120, inputs.voltagePreset)
        assertEquals("12", inputs.amps)
        assertEquals(Quantity.feet(Rational.of(150)), inputs.length!!.toQuantity())
        assertEquals(Table9Conduit.STEEL, inputs.conduit)
        assertEquals("8 AWG", inputs.size) // the design upsized it for 3 % drop
        // And the tool agrees with the engine on the drop.
        val out = inputs.parse().request!!
        assertEquals(2.09, com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop.calculate(out)!!.percent, 0.01)
    }

    @Test fun threePhaseCircuitAndCustomVoltage() {
        val c = Circuit(slot = 1, poles = 3, breakerAmps = 30, loadVa = 10_000.0)
        val panel = Panel(name = "MCC", system = PanelSystem.DELTA_240, circuits = listOf(c))
        val inputs = voltageDropInputsForCircuit(Project(name = "P", createdAt = 0, panels = listOf(panel)), c.id)!!
        assertEquals(AcPhases.THREE, inputs.system)
        assertEquals(240, inputs.voltagePreset)
        val wye = Panel(name = "HV", system = PanelSystem.WYE_480_277, circuits = listOf(c))
        assertEquals(480, voltageDropInputsForCircuit(Project(name = "P", createdAt = 0, panels = listOf(wye)), c.id)!!.voltagePreset)
    }

    @Test fun goneCircuit() {
        assertNull(voltageDropInputsForCircuit(Project(name = "P", createdAt = 0), "nope"))
    }
}
