package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.design.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelScheduleTest {

    @Test fun singlePhaseRowsAlternateAandB() {
        val panel = Panel(name = "P", system = PanelSystem.SINGLE_120_240, spaces = 6)
        val s = ScheduleLayout.build(panel)
        assertEquals(listOf(Phase.A, Phase.B, Phase.A), s.rows.map { it.phase })
        assertEquals(listOf(1, 3, 5), s.rows.map { it.leftSlot })
        assertEquals(listOf<Int?>(2, 4, 6), s.rows.map { it.rightSlot })
        assertEquals(3, s.left.size)
        assertTrue(s.left.all { it.isEmpty && it.rowSpan == 1 })
    }

    @Test fun twoPoleBreakerSpansTwoRowsOnItsSide() {
        val c = Circuit(slot = 1, poles = 2, breakerAmps = 30)
        val panel = Panel(name = "P", system = PanelSystem.SINGLE_120_240, spaces = 6, circuits = listOf(c))
        val s = ScheduleLayout.build(panel)
        // Left side: the 2-pole covers rows 0-1 (positions 1 and 3); position 5 is free.
        assertEquals(listOf(0 to 2, 2 to 1), s.left.map { it.firstRow to it.rowSpan })
        assertEquals(c, s.left.first().circuit)
        assertNull(s.left.last().circuit)
        assertEquals(5, s.left.last().slot)
        // Right side is all empty singles.
        assertEquals(3, s.right.size)
        assertTrue(s.right.all { it.isEmpty && it.rowSpan == 1 })
        assertTrue(s.unplaced.isEmpty())
    }

    @Test fun threePhasePanelSpansThreeRowsAndKeepsPhases() {
        val three = Circuit(slot = 2, poles = 3, breakerAmps = 50)
        val single = Circuit(slot = 1, breakerAmps = 20)
        val panel = Panel(name = "P", system = PanelSystem.WYE_208_120, spaces = 12, circuits = listOf(single, three))
        val s = ScheduleLayout.build(panel)
        assertEquals(listOf(Phase.A, Phase.B, Phase.C, Phase.A, Phase.B, Phase.C), s.rows.map { it.phase })
        // Right side: 3-pole at 2, 4, 6 spans rows 0-2, then three free rows.
        val first = s.right.first()
        assertEquals(three, first.circuit)
        assertEquals(0, first.firstRow)
        assertEquals(3, first.rowSpan)
        assertEquals(listOf(8, 10, 12), s.right.drop(1).map { it.slot })
        assertEquals(single, s.left.first().circuit)
        assertEquals(1, s.left.first().rowSpan)
    }

    @Test fun oddSpaceCountLeavesTheLastRightCellOut() {
        val panel = Panel(name = "P", spaces = 5)
        val s = ScheduleLayout.build(panel)
        assertEquals(3, s.rows.size)
        assertNull(s.rows.last().rightSlot)
        assertEquals(3, s.left.size)
        assertEquals(2, s.right.size)
    }

    @Test fun circuitsPastTheEndOrLosingOverlapsAreUnplaced() {
        val a = Circuit(slot = 1)
        val overlap = Circuit(slot = 1) // same position: loses to `a`
        val past = Circuit(slot = 9) // 8-space panel
        val panel = Panel(name = "P", spaces = 8, circuits = listOf(a, overlap, past))
        val s = ScheduleLayout.build(panel)
        assertEquals(listOf(overlap.id, past.id), s.unplaced.map { it.id })
        assertEquals(a, s.left.first().circuit)
    }

    @Test fun breakerText() {
        assertEquals("20/1", ScheduleLayout.breakerText(Circuit(slot = 1)))
        assertEquals("30/2", ScheduleLayout.breakerText(Circuit(slot = 1, poles = 2, breakerAmps = 30)))
        assertEquals("space", ScheduleLayout.breakerText(Circuit(slot = 1, kind = CircuitKind.SPACE, breakerAmps = null)))
    }

    @Test fun loadMathMatchesTheEngineAndRoundTrips() {
        val wye = PanelSystem.WYE_208_120
        assertEquals(10.0, LoadMath.amps(wye, 1, 1200.0), 1e-9)
        assertEquals(1200.0, LoadMath.va(wye, 1, 10.0), 1e-9)
        assertEquals(20.0, LoadMath.amps(wye, 2, 4160.0), 1e-9)
        assertEquals(10.0, LoadMath.amps(wye, 3, 208 * Math.sqrt(3.0) * 10), 1e-9)
        // A 2- or 3-pole breaker in a single-phase panel sees 240 V.
        assertEquals(10.0, LoadMath.amps(PanelSystem.SINGLE_120_240, 2, 2400.0), 1e-9)
        assertEquals(10.0, LoadMath.amps(PanelSystem.SINGLE_120_240, 3, 2400.0), 1e-9)
        // No neutral: a 1-pole circuit is line-to-line.
        assertEquals(10.0, LoadMath.amps(PanelSystem.DELTA_240, 1, 2400.0), 1e-9)
        for (p in 1..3) assertEquals(7.5, LoadMath.amps(wye, p, LoadMath.va(wye, p, 7.5)), 1e-9)
    }

    @Test fun imbalance() {
        assertNull(LoadMath.imbalancePercent(listOf(0.0, 0.0, 0.0)))
        assertEquals(0.0, LoadMath.imbalancePercent(listOf(100.0, 100.0, 100.0))!!, 1e-9)
        // average 100, A deviates by 50.
        assertEquals(50.0, LoadMath.imbalancePercent(listOf(150.0, 100.0, 50.0))!!, 1e-9)
    }
}
