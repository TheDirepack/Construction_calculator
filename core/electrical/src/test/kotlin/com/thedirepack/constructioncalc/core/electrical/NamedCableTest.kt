package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class NamedCableTest {

    @Test
    fun defaultAreaIsCircularFromDiameter() {
        val cable = NamedCable(id = "c1", name = "3/C #12 MC", diameterIn = 0.5)
        assertEquals(PI * 0.25 * 0.25, cable.areaSqIn, 1e-9)
    }

    @Test
    fun codecRoundTripsFullAndMinimalCables() {
        val list = listOf(
            NamedCable(
                id = "simpull-500",
                name = "500 kcmil \"SIMpull\" XHHW-2",
                diameterIn = 0.949,
                areaSqIn = 0.7073,
                weightLbPerFt = 1.66,
                maxTensionLb = 4000.0,
                maxSwbpLbPerFt = 1000.0,
                note = "Southwire catalog\\line\ncheck",
            ),
            NamedCable(
                id = "mc-12-3",
                name = "12/3 MC Cable",
                diameterIn = 0.52,
            ),
        )
        val json = NamedCableCodec.encode(list)
        val decoded = NamedCableCodec.decode(json)
        assertEquals(list, decoded)
    }

    @Test
    fun customCableWorksInConduitFill() {
        val cable = NamedCable(id = "mc", name = "12/3 MC", diameterIn = 0.50, areaSqIn = 0.1963)
        val emt1 = ConduitTable.find(ConduitType.EMT, TradeSize.S_1)!!
        val analysis = ConduitFill.analyze(emt1, listOf(ConductorGroup(1, cable.toFillConductor())))
        assertEquals(1, analysis.conductorCount)
        assertEquals(0.1963, analysis.totalAreaSqIn, 1e-9)
        assertTrue(analysis.withinLimit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveDiameter() {
        NamedCable(id = "bad", name = "Bad", diameterIn = 0.0)
    }
}
