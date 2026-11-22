package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.DesignDefaults
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialCircuitAndCsvTest {

    @Test fun partialDetailCircuitUsesInlineConduitTypeAndLengthWithoutARun() {
        val len = Quantity.feet(Rational.of(150)).stored()
        val emtCircuit = Circuit(
            slot = 1,
            breakerAmps = 20,
            loadVa = 1440.0,
            length = len,
            conduitTag = "HR-1",
            conduitType = ConduitType.EMT,
            conduitSize = TradeSize.S_3_4,
        )
        val pvcCircuit = emtCircuit.copy(
            id = "pvc-ckt",
            slot = 3,
            conduitTag = "HR-2",
            conduitType = ConduitType.PVC_40,
        )
        val project = Project(
            name = "Partial",
            createdAt = 0,
            panels = listOf(Panel(name = "LP-1", circuits = listOf(emtCircuit, pvcCircuit))),
            design = DesignDefaults(upsizeForVoltageDrop = false),
        )
        val design = ProjectAnalysis.analyze(project)
        val emtDesign = design.circuits.getValue(emtCircuit.id)
        val pvcDesign = design.circuits.getValue(pvcCircuit.id)

        assertEquals(ConductorSize.AWG_12, emtDesign.size)
        assertEquals(ConductorSize.AWG_12, emtDesign.groundSize)
        assertNotNull(emtDesign.voltageDropPercent)
        assertNotNull(pvcDesign.voltageDropPercent)
        // Steel (EMT) Table 9 effective Z is slightly higher than PVC for 12 AWG Cu at 0.85 PF
        assertTrue(emtDesign.voltageDropPercent!! > pvcDesign.voltageDropPercent!!)
        assertTrue(emtDesign.notes.any { it.isWarning && "Voltage drop" in it.text })

        // Verify JSON serialization round-trip preserves inline conduit fields
        val encoded = ProjectCodec.encode(project)
        val decoded = ProjectCodec.decode(encoded)
        val roundTripped = decoded.panels.single().circuits.first()
        assertEquals("HR-1", roundTripped.conduitTag)
        assertEquals(ConduitType.EMT, roundTripped.conduitType)
        assertEquals(TradeSize.S_3_4, roundTripped.conduitSize)
    }

    @Test fun inlineConduitOverfillProducesWarning() {
        // 3-pole 100 A circuit (3 × #3 AWG + 1 × #8 EGC) does not fit in 1/2" EMT
        val c = Circuit(
            slot = 1,
            poles = 3,
            breakerAmps = 100,
            loadVa = 20_000.0,
            neutral = false,
            conduitTag = "F-1",
            conduitType = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
        )
        val project = Project(name = "P", createdAt = 0, panels = listOf(Panel(name = "DP-1", circuits = listOf(c))))
        val d = ProjectAnalysis.analyze(project).circuits.getValue(c.id)
        assertTrue(d.notes.any { it.isWarning && "over" in it.text && "fill" in it.text })
    }

    @Test fun csvEscapesRfc4180AndExportsPanelScheduleAndTakeoff() {
        assertEquals("plain", ScheduleCsv.escape("plain"))
        assertEquals("\"a, b\"", ScheduleCsv.escape("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", ScheduleCsv.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", ScheduleCsv.escape("line1\nline2"))

        val run = Run(name = "Home, Run 1", raceway = Raceway(ConduitType.EMT, TradeSize.S_3_4, length = Quantity.feet(Rational.of(100)).stored()))
        val c1 = Circuit(slot = 1, breakerAmps = 20, description = "Kitchen \"North\", East", loadVa = 1440.0, runIds = listOf(run.id))
        val c2 = Circuit(slot = 2, breakerAmps = 20, description = "Spare room", loadVa = 1200.0, conduitTag = "C-2", conduitType = ConduitType.EMT, conduitSize = TradeSize.S_3_4)
        val project = Project(
            name = "Job 1",
            createdAt = 0,
            runs = listOf(run),
            panels = listOf(Panel(name = "LP-1", circuits = listOf(c1, c2))),
        )
        val design = ProjectAnalysis.analyze(project)
        val scheduleCsv = ScheduleCsv.panelSchedule(project, design)
        assertTrue(scheduleCsv.contains("\r\n"))
        assertTrue(scheduleCsv.startsWith("Panel,System,Slots,"))
        assertTrue(scheduleCsv.contains("\"Kitchen \"\"North\"\", East\""))
        assertTrue(scheduleCsv.contains("\"Home, Run 1\""))
        assertTrue(scheduleCsv.contains("C-2 3/4\" EMT") || scheduleCsv.contains("\"C-2 3/4\"\" EMT\""))

        val takeoffCsv = ScheduleCsv.materialTakeoff(project, design)
        assertTrue(takeoffCsv.startsWith("Category,Item,Quantity,Unit,Runs\r\n"))
        assertTrue(takeoffCsv.contains("Wire,"))
        assertTrue(takeoffCsv.contains("Conduit,"))
    }
}
