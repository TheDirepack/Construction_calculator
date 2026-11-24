package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.ConductorSpec
import com.thedirepack.constructioncalc.core.model.DesignDefaults
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.GoverningConstraint
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertTrue(inputs.checkAmpacity)
        assertEquals("20", inputs.ocpdAmps)
        assertFalse(inputs.continuousLoad)
        assertEquals(TemperatureRating.C90, inputs.insulation)
        assertEquals(TemperatureRating.C75, inputs.termination)
        assertEquals("30", inputs.ambientC)
        assertEquals("2", inputs.currentCarrying)
        // And the tool agrees with the engine on the drop and governing constraint.
        val parsed = inputs.parse()
        val out = parsed.request!!
        val amp = parsed.ampacity!!
        assertEquals(2.09, VoltageDrop.calculate(out)!!.percent, 0.01)
        val ampRes = VoltageDrop.checkAmpacity(out, amp, VoltageDrop.smallestSize(out))
        assertEquals(ConductorSize.AWG_8, ampRes.governingSize)
        assertEquals(GoverningConstraint.VOLTAGE_DROP, ampRes.governing)
    }

    @Test fun prefillsSingleRunAmbientContinuousAndCustomConductor() {
        val hotRun = Run(
            name = "Hot Attic",
            raceway = Raceway(ConduitType.PVC_40, length = Quantity.feet(Rational.of(40)).stored(), ambientC = 40.0),
        )
        val spec = ConductorSpec(
            material = ConductorMaterial.ALUMINUM,
            insulation = InsulationType.THW,
            insulationRating = TemperatureRating.C75,
            terminationRating = TemperatureRating.C60,
        )
        val c = Circuit(
            slot = 1,
            poles = 2,
            breakerAmps = null,
            loadVa = 4800.0,
            continuous = true,
            neutral = false,
            runIds = listOf(hotRun.id),
            conductor = spec,
        )
        val project = Project(
            name = "P",
            createdAt = 0,
            runs = listOf(hotRun),
            panels = listOf(Panel(name = "LP", system = PanelSystem.SINGLE_120_240, circuits = listOf(c))),
            design = DesignDefaults(ambientC = 30.0),
        )
        val inputs = voltageDropInputsForCircuit(project, c.id)!!
        assertTrue(inputs.checkAmpacity)
        assertEquals("", inputs.ocpdAmps)
        assertTrue(inputs.continuousLoad)
        assertEquals(TemperatureRating.C75, inputs.insulation)
        assertEquals(TemperatureRating.C60, inputs.termination)
        assertEquals("40", inputs.ambientC)
        assertEquals("2", inputs.currentCarrying)
        assertEquals(ConductorMaterial.ALUMINUM.name, inputs.material)
        val parsed = inputs.parse()
        assertNotNull(parsed.request)
        val amp = parsed.ampacity!!
        assertNull(amp.ocpdAmps)
        assertEquals(40.0, amp.ambientC, 0.0)
        assertTrue(amp.continuousLoad)
    }

    @Test fun threePhaseCircuitAndCustomVoltage() {
        val c = Circuit(slot = 1, poles = 3, breakerAmps = 30, loadVa = 10_000.0)
        val panel = Panel(name = "MCC", system = PanelSystem.DELTA_240, circuits = listOf(c))
        val inputs = voltageDropInputsForCircuit(Project(name = "P", createdAt = 0, panels = listOf(panel)), c.id)!!
        assertEquals(AcPhases.THREE, inputs.system)
        assertEquals(240, inputs.voltagePreset)
        assertTrue(inputs.checkAmpacity)
        assertEquals("30", inputs.ocpdAmps)
        assertEquals("3", inputs.currentCarrying)
        val wye = Panel(name = "HV", system = PanelSystem.WYE_480_277, circuits = listOf(c))
        assertEquals(480, voltageDropInputsForCircuit(Project(name = "P", createdAt = 0, panels = listOf(wye)), c.id)!!.voltagePreset)
    }

    @Test fun goneCircuit() {
        assertNull(voltageDropInputsForCircuit(Project(name = "P", createdAt = 0), "nope"))
    }
}
