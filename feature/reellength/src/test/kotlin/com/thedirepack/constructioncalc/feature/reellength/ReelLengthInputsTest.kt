package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import kotlin.math.PI

class ReelLengthInputsTest {
    private fun inches(n: Long) = Quantity.inches(Rational.of(n)).stored()

    private val filled = ReelLengthInputs(
        flange = inches(30), drum = inches(12), traverse = inches(18), distance = inches(3), cableDiameter = inches(1),
    )

    @Test fun savedInputsRoundTripAndDefaultsDecode() {
        val custom = filled.copy(mode = ReelMode.CAPACITY, packingFactor = "0.9", clearance = inches(3))
        assertEquals(custom, ToolInputs.decode<ReelLengthInputs>(ToolInputs.encode(custom)))
        assertEquals(ReelLengthInputs(), ToolInputs.decode<ReelLengthInputs>(JsonObject(emptyMap())))
        assertEquals(Rational.of(2), ReelLengthInputs().clearance!!.toQuantity().value)
    }

    @Test fun incompleteFormIsMissingNotError() {
        val parsed = ReelLengthInputs().parse()
        assertNull(parsed.request)
        assertEquals(listOf("Flange diameter", "Drum diameter", "Traverse", "Distance from flange edge", "Cable diameter"), parsed.missing)
        assertTrue(parsed.problems.isEmpty())
        assertTrue(reelLengthOutput(ReelLengthInputs(), AppSettings()).notices.isEmpty())
    }

    @Test fun capacityModeUsesTheClearanceNotX() {
        val capacity = filled.copy(mode = ReelMode.CAPACITY, distance = null)
        assertEquals(2.0, capacity.parse().request!!.topClearance, 0.0)
        val length = reelLengthOutput(capacity, AppSettings()).results.first { it.key == "length" }.rational!!.toDouble()
        assertEquals(PI * 199.5 * 12, length, 1e-3)
    }

    @Test fun drumCircumferenceAndDiameterYieldTheSameEstimateAndSurviveSave() {
        val circumference = filled.copy(
            drum = inches(9), // ignored while the circumference method is selected
            drumMeasurement = DrumMeasurement.CIRCUMFERENCE,
            drumCircumference = Quantity.inches(Rational.of(12 * PI)).stored(),
        )
        val restored = ToolInputs.decode<ReelLengthInputs>(ToolInputs.encode(circumference))!!
        assertEquals(circumference, restored)
        assertEquals(12.0, restored.parse().request!!.drumDiameter, 1e-9)
        assertEquals(reelLengthOutput(filled, AppSettings()).results, reelLengthOutput(restored, AppSettings()).results)
        assertEquals(listOf("Drum circumference"), circumference.copy(drumCircumference = null).parse().missing)
    }

    @Test fun flangeToCoreDepthUsesTheRadialDistanceTwice() {
        val depth = filled.copy(
            drum = null,
            drumMeasurement = DrumMeasurement.FLANGE_TO_CORE,
            flangeToCore = inches(9), // 30 in flange − 2 × 9 in depth = 12 in core
        )
        val restored = ToolInputs.decode<ReelLengthInputs>(ToolInputs.encode(depth))!!
        assertEquals(depth, restored)
        assertEquals(12.0, restored.parse().request!!.drumDiameter, 1e-9)
        assertEquals(reelLengthOutput(filled, AppSettings()).results, reelLengthOutput(restored, AppSettings()).results)
        for (invalidDepth in listOf(inches(-1), inches(0), inches(15))) {
            assertNull(depth.copy(flangeToCore = invalidDepth).parse().request)
            assertTrue(depth.copy(flangeToCore = invalidDepth).parse().problems.isNotEmpty())
        }
    }

    @Test fun remainingResults() {
        val out = reelLengthOutput(filled, AppSettings())
        val byKey = out.results.associateBy { it.key }
        assertEquals(listOf("length", "length_feet", "wound_depth", "layers", "turns_per_layer"), out.results.map { it.key })
        assertEquals(ValueKind.LENGTH, byKey.getValue("length").kind)
        assertEquals(PI * 162 * 12, byKey.getValue("length").rational!!.toDouble(), 1e-3)
        assertEquals("509 ft", byKey.getValue("length_feet").text)
        assertEquals(Rational.of(6), byKey.getValue("wound_depth").rational)
        assertEquals(Rational.of(6), byKey.getValue("layers").rational)
        assertEquals(Rational.of(18), byKey.getValue("turns_per_layer").rational)
        assertTrue(out.notices.none { it.level == NoticeLevel.ERROR })
    }

    @Test fun feetFormatting() {
        assertEquals("1,235 ft", formatFeet(1234.6))
        assertEquals("45.5 ft", formatFeet(45.46))
    }

    @Test fun metricShowsMetersAndMillimeters() {
        val metric = AppSettings(unitSystem = UnitSystem.METRIC)
        val out = reelLengthOutput(filled, metric)
        val byKey = out.results.associateBy { it.key }
        assertEquals("155 m", byKey.getValue("length_feet").text)
        assertEquals("Cable remaining (meters)", byKey.getValue("length_feet").label)
        // exact stays in inches
        assertEquals(PI * 162 * 12, byKey.getValue("length").rational!!.toDouble(), 1e-3)
        assertEquals("152 mm", byKey.getValue("wound_depth").text)
        assertTrue(out.summary.contains("mm flange"))
        assertEquals("30.5 m", formatRunLength(100.0, metric))
        assertEquals("12.2 m", formatRunLength(40.0, metric))
    }

    @Test fun badInputsAreErrors() {
        val tooDeep = reelLengthOutput(filled.copy(distance = inches(10)), AppSettings())
        assertTrue(tooDeep.results.isEmpty())
        assertEquals(NoticeLevel.ERROR, tooDeep.notices.single().level)
        assertEquals(listOf("Packing factor isn't a number."), filled.copy(packingFactor = "1.2.3").parse().problems)
        assertTrue(filled.copy(cableDiameter = inches(0)).parse().problems.isNotEmpty())
    }

    @Test fun outOfRangeEstimateShowsAnErrorInsteadOfFormattingInfinity() {
        val largeFlange = Quantity.inches(Rational.of(1e200)).stored()
        val output = reelLengthOutput(filled.copy(flange = largeFlange), AppSettings())
        assertTrue(output.results.isEmpty())
        assertEquals(NoticeLevel.ERROR, output.notices.single().level)
    }

    @Test fun cableNeededSaysWhetherTheReelIsEnough() {
        val remaining = reelLengthOutput(filled, AppSettings()).results.first { it.key == "length" }.rational!!.toDouble()
        val enough = reelLengthOutput(filled.copy(cableNeeded = inches(remaining.toLong() - 120)), AppSettings())
        assertEquals("Left after the pull", enough.results.single { it.key == "spare_after_pull" }.label)
        assertTrue(enough.notices.any { it.level == NoticeLevel.INFO && it.text.startsWith("Enough for the") })
        val short = reelLengthOutput(filled.copy(cableNeeded = inches(remaining.toLong() + 120)), AppSettings())
        val spare = short.results.single { it.key == "spare_after_pull" }
        assertEquals("Short for the pull", spare.label)
        assertTrue(spare.rational!!.signum > 0)
        assertTrue(short.notices.any { it.level == NoticeLevel.WARNING && it.text.startsWith("Short by") })
        // No value: no comparison.
        assertTrue(reelLengthOutput(filled, AppSettings()).results.none { it.key == "spare_after_pull" })
        assertEquals(filled.copy(cableNeeded = inches(5)), ToolInputs.decode<ReelLengthInputs>(ToolInputs.encode(filled.copy(cableNeeded = inches(5)))))
    }
}
