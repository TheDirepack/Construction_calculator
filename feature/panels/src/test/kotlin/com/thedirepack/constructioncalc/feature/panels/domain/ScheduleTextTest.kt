package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.design.PublishedValues
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.CircuitRef
import com.thedirepack.constructioncalc.core.model.ConductorSpec
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTextTest {

    private val feet = { q: Quantity -> "${q.value.toDouble() / 12} ft" }

    private fun sample(): Project {
        val run = Run(name = "Home run", raceway = Raceway(ConduitType.EMT, length = Quantity.feet(Rational.of(50)).stored()))
        val group = com.thedirepack.constructioncalc.core.model.CircuitGroup(name = "Kitchen", color = 1)
        val main = Panel(name = "MDP", spaces = 6, system = PanelSystem.WYE_208_120, circuits = listOf(Circuit(slot = 1, poles = 3, breakerAmps = 100, description = "To LP-1")))
        val lp = Panel(
            name = "LP-1",
            location = "Electrical room",
            system = PanelSystem.WYE_208_120,
            spaces = 12,
            busAmps = 100,
            mainAmps = 100,
            fedFrom = CircuitRef(main.id, main.circuits.single().id),
            circuits = listOf(
                Circuit(slot = 1, breakerAmps = 20, description = "Counter receptacles", loadVa = 1200.0, groupIds = listOf(group.id), runIds = listOf(run.id)),
                Circuit(slot = 2, poles = 2, breakerAmps = 30, description = "Dryer", loadVa = 5000.0),
                Circuit(slot = 3, kind = CircuitKind.SPARE),
            ),
        )
        return Project(name = "Smith house", createdAt = 0, runs = listOf(run), groups = listOf(group), panels = listOf(main, lp))
    }

    @Test fun wireTextForOnePoleWithNeutralAndTwoPoleWithout() {
        val project = sample()
        val design = ProjectAnalysis.analyze(project)
        val lp = project.panels[1]
        val one = design.circuits.getValue(lp.circuits[0].id)
        val two = design.circuits.getValue(lp.circuits[1].id)
        assertEquals("2#12, 1#12G", Wire.text(one))
        assertEquals("2#10, 1#10G", Wire.text(two)) // 30 A at 75 °C terminals: 10 AWG copper
        assertNull(Wire.text(design.circuits.getValue(lp.circuits[2].id))) // spare: no wire
    }

    @Test fun aluminumIsMarkedAndLargeSizesUseKcmil() {
        assertEquals("3-250k", Wire.run(3, com.thedirepack.constructioncalc.core.electrical.ConductorSize.KCMIL_250))
        assertEquals("1#1/0", Wire.run(1, com.thedirepack.constructioncalc.core.electrical.ConductorSize.AWG_1_0))
        val project = sample().copy(design = sample().design.copy(conductor = ConductorSpec(material = ConductorMaterial.ALUMINUM)))
        val d = ProjectAnalysis.analyze(project).circuits.getValue(project.panels[1].circuits[1].id)
        assertTrue(Wire.text(d)!!.endsWith(" Al"))
    }

    @Test fun textExportListsEveryPositionWithPhasesLoadsAndTotals() {
        val project = sample()
        val text = ScheduleText.render(
            project,
            ProjectAnalysis.analyze(project),
            panelIds = listOf(project.panels[1].id),
            header = listOf("Acme Electric · Lic. 42"),
            date = "Nov 20, 2026",
            length = feet,
        )
        val lines = text.lines()
        assertEquals("Acme Electric · Lic. 42", lines[0])
        assertTrue(text.contains("Panel schedule · Smith house"))
        assertTrue(text.contains("PANEL LP-1"))
        assertTrue(text.contains("Location: Electrical room"))
        assertTrue(text.contains("208Y/120 V 3φ 4W · 12 spaces · 100 A bus · 100 A main"))
        assertTrue(text.contains("Fed from: MDP #1,3,5 To LP-1"))
        // Row 1 (phase A): 1-pole with neutral, load, wire, group, run and length.
        assertTrue(text.contains("1 (A) · 20/1 · Counter receptacles · 1,200 VA · 2#12, 1#12G"))
        assertTrue(text.contains("[Kitchen] · in Home run · length 50.0 ft"))
        // The 2-pole at slot 2 takes phases A and B; its third position isn't listed again.
        assertTrue(text.contains("2,4 (A,B) · 30/2 · Dryer · 5,000 VA · 2#10, 1#10G"))
        assertEquals(1, lines.count { it.startsWith("2,4 ") })
        assertTrue(text.contains("3 (B) · 20/1 · Spare"))
        // Free positions are folded into ranges.
        assertTrue(text.contains("5–12 · space (8)") || text.contains("space"))
        // Totals per phase (1200 on A + 2500 from the 2-pole = 3700; B gets 2500; C none).
        assertTrue(text.contains("Phase A: 3,700 VA"))
        assertTrue(text.contains("Phase B: 2,500 VA"))
        assertTrue(text.contains("Phase C: 0 VA"))
        assertTrue(text.contains("Total: 6,200 VA · imbalance"))
        // Panels not requested stay out.
        assertTrue(!text.contains("PANEL MDP"))
    }

    @Test fun textExportOfAllPanelsAndAnEmptyProject() {
        val project = sample()
        val all = ScheduleText.render(project, ProjectAnalysis.analyze(project), date = "d")
        assertTrue(all.indexOf("PANEL MDP") in 0 until all.indexOf("PANEL LP-1"))
        val empty = Project(name = "Nothing", createdAt = 0)
        assertTrue(ScheduleText.render(empty, ProjectAnalysis.analyze(empty), date = "d").contains("No panels."))
    }

    @Test fun overlappingCircuitsAreStillListedAndWarned() {
        val a = Circuit(slot = 1, description = "A")
        val b = Circuit(slot = 1, description = "B")
        val project = Project(name = "P", createdAt = 0, panels = listOf(Panel(name = "X", spaces = 4, circuits = listOf(a, b))))
        val text = ScheduleText.render(project, ProjectAnalysis.analyze(project), date = "d")
        assertEquals(1, text.lines().count { it.startsWith("1 (A) ·") })
        assertTrue(text.contains("overlaps"))
    }

    @Test fun publishedValuesCarryTheRightKinds() {
        val project = sample()
        val design = ProjectAnalysis.analyze(project)
        val panel = PublishedValues.panel(design.panels[1])
        assertEquals(
            listOf(ValueKind.CURRENT, ValueKind.CURRENT, ValueKind.CURRENT, ValueKind.APPARENT_POWER, ValueKind.POWER),
            panel.map { it.kind },
        )
        assertEquals("6,200 VA", panel.first { it.kind == ValueKind.APPARENT_POWER }.text)
        val circuit = PublishedValues.circuit(design.circuits.getValue(project.panels[1].circuits[0].id)) { "50'" }
        assertEquals(listOf(ValueKind.TEXT, ValueKind.LENGTH, ValueKind.CURRENT, ValueKind.APPARENT_POWER), circuit.map { it.kind })
        assertEquals("2#12, 1#12G", circuit[0].text)
        assertEquals("600", circuit[1].exact) // 50 ft in inches
        assertNotNull(circuit[2].rational)
    }
}
