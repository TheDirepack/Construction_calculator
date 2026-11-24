package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun codecRoundTripsFavoriteFlagAndLegacyJsonDefaultsFalse() {
        val fav = NamedCable(id = "f1", name = "Favorite Feeder", diameterIn = 0.85, favorite = true)
        val regular = NamedCable(id = "r1", name = "Regular Branch", diameterIn = 0.45, favorite = false)
        val json = NamedCableCodec.encode(listOf(fav, regular))
        assertTrue(json.contains("\"favorite\":true"))
        assertFalse(json.contains("\"favorite\":false"))
        assertEquals(listOf(fav, regular), NamedCableCodec.decode(json))

        val explicitFalseJson = """[{"id":"r2","name":"Explicit False","diameterIn":0.5,"favorite":false}]"""
        val decodedExplicitFalse = NamedCableCodec.decode(explicitFalseJson)
        assertEquals(1, decodedExplicitFalse.size)
        assertFalse(decodedExplicitFalse.single().favorite)

        val legacyJson = """[{"id":"old","name":"Legacy MC","diameterIn":0.52,"areaSqIn":0.212,"note":"pre-favorites"}]"""
        val decodedLegacy = NamedCableCodec.decode(legacyJson)
        assertEquals(1, decodedLegacy.size)
        assertEquals("Legacy MC", decodedLegacy.single().name)
        assertEquals("pre-favorites", decodedLegacy.single().note)
        assertFalse(decodedLegacy.single().favorite)
    }

    @Test
    fun sortedForPickerPlacesFavoritesFirstThenAlphabeticalByName() {
        val zebraFav = NamedCable(id = "1", name = "Zebra 500", diameterIn = 0.9, favorite = true)
        val alphaReg = NamedCable(id = "2", name = "alpha 12", diameterIn = 0.4, favorite = false)
        val betaFav = NamedCable(id = "3", name = "beta 250", diameterIn = 0.7, favorite = true)
        val charlieReg = NamedCable(id = "4", name = "Charlie 10", diameterIn = 0.5, favorite = false)

        val sorted = NamedCable.sortedForPicker(listOf(zebraFav, alphaReg, betaFav, charlieReg))
        assertEquals(listOf(betaFav, zebraFav, alphaReg, charlieReg), sorted)
    }
}
