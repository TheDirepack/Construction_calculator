package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalePresetsTest {
    private fun preset(label: String) = ScalePresets.all.single { it.label == label }

    @Test fun architecturalScalesAreExactRealInchesPerPoint() {
        // 1/8" = 1'-0": one paper inch (72 pt) is 96 real inches.
        assertEquals(Rational.of(96), preset("⅛″ = 1′").ratio)
        assertEquals(Rational.of(96, 72), preset("⅛″ = 1′").inchesPerPoint)
        assertEquals(Rational.of(48), preset("¼″ = 1′").ratio)
        assertEquals(Rational.of(128), preset("³⁄₃₂″ = 1′").ratio)
        assertEquals(Rational.of(64), preset("³⁄₁₆″ = 1′").ratio)
        assertEquals(Rational.of(32), preset("⅜″ = 1′").ratio)
        assertEquals(Rational.of(16), preset("¾″ = 1′").ratio)
        assertEquals(Rational.of(12), preset("1″ = 1′").ratio)
        assertEquals(Rational.of(8), preset("1½″ = 1′").ratio)
        assertEquals(Rational.of(4), preset("3″ = 1′").ratio)
    }

    @Test fun engineeringAndMetricScales() {
        assertEquals(Rational.of(240), preset("1″ = 20′").ratio)
        assertEquals(Rational.of(1200), preset("1″ = 100′").ratio)
        assertEquals(Rational.of(50), preset("1:50").ratio)
        assertEquals(Rational.of(50, 72), preset("1:50").inchesPerPoint)
    }

    @Test fun aPresetMeasuresTheDrawnLengthExactly() {
        // A 2'-0" ceiling tile on a 1/8" plan is 1/4" of paper = 18 pt.
        val layer = PlanCalibrationLayer(preset = preset("⅛″ = 1′").stored())
        val tile = layer.fit(0)!!.distance(PlanPoint(100.0, 100.0), PlanPoint(118.0, 100.0))
        assertEquals(24.0, tile.value.toDouble(), 1e-9)
        // And a 4' tile edge along the other axis is 36 pt.
        val long = layer.fit(0)!!.distance(PlanPoint(100.0, 100.0), PlanPoint(100.0, 136.0))
        assertEquals(48.0, long.value.toDouble(), 1e-9)
    }

    @Test fun matchingFindsOnlyExactPresets() {
        assertEquals(preset("¼″ = 1′"), ScalePresets.matching(Rational.of(48, 72)))
        assertNull(ScalePresets.matching(Rational.of(47, 72)))
        assertNull(ScalePresets.matching(null))
    }

    @Test fun labelsAndRatiosAreUnique() {
        assertEquals(ScalePresets.all.size, ScalePresets.all.map { it.label }.toSet().size)
        // Each family has distinct ratios (1:10 metric and 1" = 10' engineering differ: 10 vs 120).
        for (family in ScaleFamily.entries) {
            val ratios = ScalePresets.all.filter { it.family == family }.map { it.ratio }
            assertEquals(ratios.size, ratios.toSet().size)
        }
    }

    @Test fun warnsAboutScaledPrints() {
        // ARCH D (24 × 36) at full size: a standard sheet, no half-size note.
        val archD = ScalePresets.warning(36.0 * 72, 24.0 * 72)!!
        assertTrue(archD.startsWith("Sheet matches ARCH D."))
        // 11 × 17 is commonly a half-size set.
        assertTrue(ScalePresets.warning(17.0 * 72, 11.0 * 72)!!.contains("half-size"))
        // A phone-photo page size matches nothing.
        assertTrue(ScalePresets.warning(3024.0, 4032.0)!!.startsWith("Page size doesn't match"))
        assertNull(ScalePresets.warning(null, 100.0))
    }
}
