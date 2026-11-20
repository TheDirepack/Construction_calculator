package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.CircuitRef
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEditsTest {

    private fun project(vararg panels: Panel, runs: List<Run> = emptyList()) =
        Project(name = "Job", createdAt = 0, panels = panels.toList(), runs = runs)

    @Test fun newPanelDefaults() {
        val p = newPanel("  LP-1 ")
        assertEquals("LP-1", p.name)
        assertEquals(PanelSystem.WYE_208_120, p.system)
        assertEquals(42, p.spaces)
        assertEquals(225, p.busAmps)
        assertNull(p.mainAmps)
        assertEquals("Panel 3", project(Panel(name = "Panel 1"), Panel(name = "Panel 2")).suggestedPanelName())
    }

    @Test fun newCircuitIsTwentyAmpOnePoleAtTheFirstFreeSlot() {
        val panel = Panel(name = "P", spaces = 6, circuits = listOf(Circuit(slot = 1), Circuit(slot = 2), Circuit(slot = 3)))
        val c = newCircuit(panel)!!
        assertEquals(4, c.slot)
        assertEquals(1, c.poles)
        assertEquals(20, c.breakerAmps)
        assertEquals(6, newCircuit(panel, slot = 6)!!.slot)
        assertEquals(4, newCircuit(panel, slot = 1)!!.slot) // taken: falls back to first free
        val full = panel.copy(circuits = (1..6).map { Circuit(slot = it) })
        assertNull(newCircuit(full))
    }

    @Test fun freeSlotsAndPolesOffered() {
        val panel = Panel(name = "P", system = PanelSystem.SINGLE_120_240, spaces = 6, circuits = listOf(Circuit(slot = 1, poles = 2)))
        assertEquals(listOf(2, 4, 5, 6), freeSlots(panel, 1))
        assertEquals(listOf(2, 4), freeSlots(panel, 2)) // 5 would need 7
        assertEquals(listOf(1, 2), supportedPoles(panel)) // single phase: no 3-pole
        val threePhase = Panel(name = "P", system = PanelSystem.WYE_208_120, spaces = 6)
        assertEquals(listOf(1, 2, 3), supportedPoles(threePhase))
        // Ignoring the circuit being edited frees its own positions.
        assertTrue(1 in freeSlots(panel, 2, ignore = panel.circuits.single().id))
    }

    @Test fun invalidPoleCountsHaveNoFreePositions() {
        val panel = Panel(name = "P")
        for (poles in listOf(0, 4, Int.MAX_VALUE)) {
            assertTrue(freeSlots(panel, poles).isEmpty())
            assertNull(newCircuit(panel, poles))
        }
    }

    @Test fun movingACircuitWithinAPanel() {
        val c = Circuit(slot = 1, description = "Kitchen")
        val p = Panel(name = "P", spaces = 8, circuits = listOf(c, Circuit(slot = 3)))
        val moved = project(p).moveCircuit(c.id, p.id, 5, now = 9)
        assertEquals(listOf(3, 5), moved.panels.single().circuits.map { it.slot })
        assertEquals(9L, moved.updatedAt)
        // Occupied or off-panel targets are refused.
        val job = project(p)
        assertSame(job, job.moveCircuit(c.id, p.id, 3, now = 9))
        assertSame(job, job.moveCircuit(c.id, p.id, 9, now = 9))
    }

    @Test fun movingACircuitToAnotherPanelKeepsItsIdentityAndFeedRefs() {
        val feeder = Circuit(slot = 1, poles = 3, breakerAmps = 100)
        val a = Panel(name = "A", spaces = 12, circuits = listOf(feeder))
        val b = Panel(name = "B", spaces = 12)
        val sub = Panel(name = "Sub", fedFrom = CircuitRef(a.id, feeder.id))
        val moved = project(a, b, sub).moveCircuit(feeder.id, b.id, 2, now = 5)
        assertTrue(moved.panel(a.id)!!.circuits.isEmpty())
        val inB = moved.panel(b.id)!!.circuits.single()
        assertEquals(feeder.id, inB.id)
        assertEquals(2, inB.slot)
        assertEquals(CircuitRef(b.id, feeder.id), moved.panel(sub.id)!!.fedFrom)
    }

    @Test fun threePoleBreakerCantMoveIntoASinglePhasePanel() {
        val c = Circuit(slot = 1, poles = 3)
        val a = Panel(name = "A", circuits = listOf(c))
        val b = Panel(name = "B", system = PanelSystem.SINGLE_120_240)
        val job = project(a, b)
        assertSame(job, job.moveCircuit(c.id, b.id, 1, now = 1))
    }

    @Test fun upsertReplacesAndKeepsSlotOrder() {
        val a = Circuit(slot = 3)
        val p = Panel(name = "P", circuits = listOf(a))
        val job = project(p)
        val added = job.upsertCircuit(p.id, Circuit(slot = 1), now = 2)
        assertEquals(listOf(1, 3), added.panels.single().circuits.map { it.slot })
        val edited = added.upsertCircuit(p.id, a.copy(description = "New"), now = 3)
        assertEquals(2, edited.panels.single().circuits.size)
        assertEquals("New", edited.panels.single().circuits.last().description)
        assertSame(job, job.upsertCircuit("missing", a, now = 4))
    }

    @Test fun deletingACircuitClearsWhatItFed() {
        val feeder = Circuit(slot = 1)
        val a = Panel(name = "A", circuits = listOf(feeder))
        val b = Panel(name = "B", fedFrom = CircuitRef(a.id, feeder.id))
        val result = project(a, b).deleteCircuit(feeder.id, now = 1)
        assertTrue(result.panel(a.id)!!.circuits.isEmpty())
        assertNull(result.panel(b.id)!!.fedFrom)
    }

    @Test fun switchingToSinglePhaseTurnsThreePolesIntoTwo() {
        val c = Circuit(slot = 1, poles = 3)
        val p = Panel(name = "P", circuits = listOf(c))
        val r = project(p).changePanelSystem(p.id, PanelSystem.SINGLE_120_240, now = 1).panel(p.id)!!
        assertEquals(PanelSystem.SINGLE_120_240, r.system)
        assertEquals(2, r.circuits.single().poles)
    }

    @Test fun duplicatingAPanelGivesNewIds() {
        val c = Circuit(slot = 1, description = "Lights", runIds = listOf("home-run"))
        val a = Panel(name = "LP-1", circuits = listOf(c))
        val b = Panel(name = "LP-2")
        val r = project(a, b).duplicatePanel(a.id, now = 1)
        // The copy doesn't share the original's conduits (that would double their conductor count).
        assertEquals(emptyList<String>(), r.panels[1].circuits.single().runIds)
        assertEquals(listOf("home-run"), r.panels[0].circuits.single().runIds)
        assertEquals(listOf("LP-1", "LP-1 (copy)", "LP-2"), r.panels.map { it.name })
        val copy = r.panels[1]
        assertNotEquals(a.id, copy.id)
        assertNotEquals(c.id, copy.circuits.single().id)
        assertEquals("Lights", copy.circuits.single().description)
    }

    @Test fun assigningARunAcrossPanels() {
        val run = Run(name = "Home run", raceway = Raceway())
        val other = Run(name = "Other", raceway = Raceway())
        val a1 = Circuit(slot = 1, runIds = listOf(other.id))
        val a2 = Circuit(slot = 3)
        val b1 = Circuit(slot = 1)
        val b2 = Circuit(slot = 3, runIds = listOf(run.id)) // currently in the run, will be taken out
        val a = Panel(name = "A", circuits = listOf(a1, a2))
        val b = Panel(name = "B", circuits = listOf(b1, b2))
        val job = project(a, b, runs = listOf(run, other))

        val r = job.assignRun(run.id, setOf(a1.id, b1.id), now = 7)
        assertEquals(listOf(other.id, run.id), r.findCircuit(a1.id)!!.circuit.runIds) // keeps its other run
        assertEquals(emptyList<String>(), r.findCircuit(a2.id)!!.circuit.runIds)
        assertEquals(listOf(run.id), r.findCircuit(b1.id)!!.circuit.runIds)
        assertEquals(emptyList<String>(), r.findCircuit(b2.id)!!.circuit.runIds)
        assertEquals(setOf(a1.id, b1.id), r.circuitsInRun(run.id).map { it.circuit.id }.toSet())
        assertEquals(listOf("A", "B"), r.circuitsInRun(run.id).map { it.panel.name })

        // Assigning twice changes nothing more.
        assertEquals(r.panels, r.assignRun(run.id, setOf(a1.id, b1.id), now = 8).panels)
        // Unknown run: no change.
        assertSame(job, job.assignRun("nope", setOf(a1.id), now = 7))
    }

    @Test fun deletingARunTakesItOutOfEveryCircuit() {
        val run = Run(name = "R", raceway = Raceway())
        val c = Circuit(slot = 1, runIds = listOf(run.id))
        val r = project(Panel(name = "A", circuits = listOf(c)), runs = listOf(run)).deleteRunAndUnassign(run.id, now = 1)
        assertTrue(r.runs.isEmpty())
        assertTrue(r.panels.single().circuits.single().runIds.isEmpty())
    }

    @Test fun designRunsAreConduitRunsAndRunsWithCircuits() {
        val conduit = Run(name = "Conduit", raceway = Raceway())
        val plain = Run(name = "Wall")
        val usedPlain = Run(name = "Used")
        val job = project(Panel(name = "A", circuits = listOf(Circuit(slot = 1, runIds = listOf(usedPlain.id)))), runs = listOf(conduit, plain, usedPlain))
        assertEquals(listOf("Conduit", "Used"), job.designRuns().map { it.name })
    }

    @Test fun groupsAreRemovedFromCircuitsWhenDeleted() {
        val job0 = project(Panel(name = "A", circuits = listOf(Circuit(slot = 1), Circuit(slot = 3))))
        val g1 = job0.newGroup("Kitchen")
        val job1 = job0.addGroup(g1, 1)
        val g2 = job1.newGroup("RTU")
        assertNotEquals(g1.color, g2.color)
        val tagged = job1.addGroup(g2, 2).let { p ->
            val ids = p.panels.single().circuits.map { it.id }
            p.upsertCircuit(p.panels.single().id, p.findCircuit(ids[0])!!.circuit.copy(groupIds = listOf(g1.id, g2.id)), 3)
        }
        assertEquals(1, tagged.circuitsInGroup(g1.id).size)
        val removed = tagged.deleteGroup(g1.id, 4)
        assertEquals(listOf(g2.id), removed.groups.map { it.id })
        assertEquals(listOf(g2.id), removed.allCircuits().first { it.circuit.groupIds.isNotEmpty() }.circuit.groupIds)
        assertTrue(removed.circuitsInGroup(g1.id).isEmpty())
    }

    @Test fun fedFromCandidatesExcludeLoops() {
        val feeder = Circuit(slot = 1, poles = 3)
        val main = Panel(name = "Main", circuits = listOf(feeder))
        val sub = Panel(name = "Sub", fedFrom = CircuitRef(main.id, feeder.id), circuits = listOf(Circuit(slot = 1)))
        val subsub = Panel(name = "SubSub", fedFrom = CircuitRef(sub.id, sub.circuits.single().id), circuits = listOf(Circuit(slot = 1)))
        val job = project(main, sub, subsub)
        assertTrue(job.feedCandidates(main.id).isEmpty()) // everything else is downstream of Main
        assertEquals(listOf("Main"), job.feedCandidates(sub.id).map { it.panel.name }.distinct())
        assertFalse(job.feedCandidates(sub.id).any { it.panel.name == "SubSub" })
    }

    @Test fun circuitLabels() {
        val p = Panel(name = "LP-1")
        assertEquals("LP-1 #5 Kitchen", circuitLabel(p, Circuit(slot = 5, description = "Kitchen")))
        assertEquals("LP-1 #2,4 Heater", circuitLabel(p, Circuit(slot = 2, poles = 2, description = "Heater")))
        assertEquals("LP-1 #7 Spare", circuitLabel(p, Circuit(slot = 7, kind = CircuitKind.SPARE)))
    }
}
