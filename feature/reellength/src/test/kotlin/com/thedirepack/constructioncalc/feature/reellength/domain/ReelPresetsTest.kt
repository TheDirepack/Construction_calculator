package com.thedirepack.constructioncalc.feature.reellength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelPresetsTest {

    @Test
    fun builtInPresetsHaveEightNominalEntriesAndPassCapacityAndRemainingModes() {
        assertEquals(8, BuiltInReelPresets.all.size)
        assertEquals(8, BuiltInReelPresets.all.map { it.id }.toSet().size)

        BuiltInReelPresets.all.forEach { preset ->
            assertTrue("${preset.id} should be builtIn", preset.builtIn)
            assertTrue(
                "${preset.id} should note nominal dimensions",
                preset.note.contains("nominal", ignoreCase = true) &&
                    preset.note.contains("stamped", ignoreCase = true),
            )

            val capacityRequest = preset.toRequest(cableDiameterIn = 0.5, capacity = true)
            assertEquals(
                "Capacity problems for ${preset.name}",
                emptyList<String>(),
                ReelLength.problems(capacityRequest),
            )
            val capacityResult = ReelLength.calculate(capacityRequest)
            assertNotNull(capacityResult)
            assertTrue(capacityResult!!.lengthFeet > 0.0)

            val remainingRequest = preset.toRequest(cableDiameterIn = 0.5, capacity = false)
            assertEquals(
                "Remaining problems for ${preset.name}",
                emptyList<String>(),
                ReelLength.problems(remainingRequest),
            )
            val remainingResult = ReelLength.calculate(remainingRequest)
            assertNotNull(remainingResult)
            assertTrue(remainingResult!!.lengthFeet > 0.0)
        }
    }

    @Test
    fun reelPresetRejectsInvalidGeometryInInit() {
        val cases = listOf<() -> ReelPreset>(
            { ReelPreset(id = "", name = "Bad", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0) },
            { ReelPreset(id = "r1", name = "   ", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0) },
            { ReelPreset(id = "r1", name = "Bad", flangeIn = 0.0, drumIn = 10.0, traverseIn = 12.0) },
            { ReelPreset(id = "r1", name = "Bad", flangeIn = Double.NaN, drumIn = 10.0, traverseIn = 12.0) },
            { ReelPreset(id = "r1", name = "Bad", flangeIn = 24.0, drumIn = 0.0, traverseIn = 12.0) },
            { ReelPreset(id = "r1", name = "Bad", flangeIn = 24.0, drumIn = Double.POSITIVE_INFINITY, traverseIn = 12.0) },
            { ReelPreset(id = "r1", name = "Bad", flangeIn = 24.0, drumIn = 10.0, traverseIn = -1.0) },
            { ReelPreset(id = "r1", name = "Bad", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0, clearanceIn = -0.5) },
            { ReelPreset(id = "r1", name = "Bad", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0, clearanceIn = Double.NaN) },
            { ReelPreset(id = "r1", name = "Equal", flangeIn = 12.0, drumIn = 12.0, traverseIn = 6.0, clearanceIn = 0.0) },
            { ReelPreset(id = "r1", name = "Smaller", flangeIn = 10.0, drumIn = 12.0, traverseIn = 6.0, clearanceIn = 0.0) },
            { ReelPreset(id = "r1", name = "ZeroDepth", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0, clearanceIn = 7.0) },
            { ReelPreset(id = "r1", name = "NegDepth", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0, clearanceIn = 8.0) },
        )
        cases.forEach { build ->
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun reelCableFavoriteRejectsInvalidGeometryAndCableFieldsInInit() {
        val cases = listOf<() -> ReelCableFavorite>(
            {
                ReelCableFavorite(
                    id = "", name = "Bad", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
                    cableName = "MC", cableDiameterIn = 0.5,
                )
            },
            {
                ReelCableFavorite(
                    id = "s1", name = " ", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
                    cableName = "MC", cableDiameterIn = 0.5,
                )
            },
            {
                ReelCableFavorite(
                    id = "s1", name = "Setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
                    cableName = "", cableDiameterIn = 0.5,
                )
            },
            {
                ReelCableFavorite(
                    id = "s1", name = "Setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
                    cableName = "MC", cableDiameterIn = 0.0,
                )
            },
            {
                ReelCableFavorite(
                    id = "s1", name = "Setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
                    cableName = "MC", cableDiameterIn = Double.NaN,
                )
            },
            {
                ReelCableFavorite(
                    id = "s1", name = "Setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
                    clearanceIn = 7.0, cableName = "MC", cableDiameterIn = 0.5,
                )
            },
        )
        cases.forEach { build ->
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun payloadJsonRoundTripsAndHandlesEmptyOrMalformedInput() {
        val customReel = ReelPreset(
            id = "custom-1",
            name = "Jobsite Steel 28x14",
            flangeIn = 28.0,
            drumIn = 12.0,
            traverseIn = 14.0,
            clearanceIn = 2.0,
            builtIn = false,
            note = "Warehouse bay 3",
        )
        val setupStarred = ReelCableFavorite(
            id = "fav-1",
            name = "30x16 + 500 XHHW-2",
            flangeIn = 30.0,
            drumIn = 14.0,
            traverseIn = 16.0,
            clearanceIn = 2.0,
            cableName = "500 kcmil XHHW-2",
            cableDiameterIn = 0.949,
            favorite = true,
        )
        val setupPlain = ReelCableFavorite(
            id = "fav-2",
            name = "18x10 + 12/3 MC",
            flangeIn = 18.0,
            drumIn = 8.0,
            traverseIn = 10.0,
            clearanceIn = 1.5,
            cableName = "12/3 MC",
            cableDiameterIn = 0.52,
            favorite = false,
        )
        val payload = ReelPresetsPayload(
            customReels = listOf(customReel),
            favoriteReelIds = setOf("builtin-30x16", "custom-1"),
            reelCableFavorites = listOf(setupStarred, setupPlain),
        )

        val encoded = ReelPresetsPayload.encode(payload)
        val decoded = ReelPresetsPayload.decode(encoded)
        assertEquals(payload, decoded)

        assertEquals(ReelPresetsPayload(), ReelPresetsPayload.decode(""))
        assertEquals(ReelPresetsPayload(), ReelPresetsPayload.decode("not json"))

        // Omitted favorite field defaults to false
        val legacySetupJson = """
            {
              "reelCableFavorites": [
                {
                  "id": "legacy-1",
                  "name": "24x12 + #10",
                  "flangeIn": 24.0,
                  "drumIn": 10.0,
                  "traverseIn": 12.0,
                  "cableName": "#10 THHN",
                  "cableDiameterIn": 0.164
                }
              ]
            }
        """.trimIndent()
        val decodedLegacy = ReelPresetsPayload.decode(legacySetupJson)
        assertEquals(1, decodedLegacy.reelCableFavorites.size)
        assertFalse(decodedLegacy.reelCableFavorites.single().favorite)
        assertEquals(2.0, decodedLegacy.reelCableFavorites.single().clearanceIn, 1e-12)
    }

    @Test
    fun favoriteOrderingPlacesFavoritesFirstThenAlphabetical() {
        val customZebraFav = ReelPreset(
            id = "c-z",
            name = "Zebra Reel",
            flangeIn = 26.0,
            drumIn = 12.0,
            traverseIn = 14.0,
        )
        val customAlpha = ReelPreset(
            id = "c-a",
            name = "Alpha Reel",
            flangeIn = 20.0,
            drumIn = 8.0,
            traverseIn = 10.0,
        )
        val payload = ReelPresetsPayload(
            customReels = listOf(customZebraFav, customAlpha),
            favoriteReelIds = setOf("c-z", "builtin-30x16"),
        )

        val favs = payload.favoriteReels()
        assertEquals(listOf("30\" × 16\" (feeder reel)", "Zebra Reel"), favs.map { it.name })

        val all = payload.allReels()
        assertEquals("30\" × 16\" (feeder reel)", all[0].name)
        assertEquals("Zebra Reel", all[1].name)
        assertTrue(all.drop(2).zipWithNext().all { (a, b) -> a.name.lowercase() <= b.name.lowercase() })

        val s1 = ReelCableFavorite(
            id = "1", name = "zeta setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
            cableName = "C1", cableDiameterIn = 0.5, favorite = true,
        )
        val s2 = ReelCableFavorite(
            id = "2", name = "Alpha setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
            cableName = "C2", cableDiameterIn = 0.5, favorite = false,
        )
        val s3 = ReelCableFavorite(
            id = "3", name = "Beta setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
            cableName = "C3", cableDiameterIn = 0.5, favorite = true,
        )
        val s4 = ReelCableFavorite(
            id = "4", name = "delta setup", flangeIn = 24.0, drumIn = 10.0, traverseIn = 12.0,
            cableName = "C4", cableDiameterIn = 0.5, favorite = false,
        )
        assertEquals(
            listOf(s3, s1, s2, s4),
            ReelPresetsPayload.sortedFavorites(listOf(s1, s2, s3, s4)),
        )
    }
}
