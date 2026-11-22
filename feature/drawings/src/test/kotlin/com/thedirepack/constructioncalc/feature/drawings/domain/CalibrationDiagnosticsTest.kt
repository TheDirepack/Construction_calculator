package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanScaleMode
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationDiagnosticsTest {
    private fun inches(v: Double) = Quantity.inches(Rational.of(v)).stored()

    @Test fun uncalibratedAndPresetStatuses() {
        val empty = PlanCalibrationLayer()
        assertEquals(CalibrationStatus.UNCALIBRATED, CalibrationDiagnostics.evaluate(empty, 0).status)

        val preset = PlanCalibrationLayer(preset = inches(1.0))
        val presetSummary = CalibrationDiagnostics.evaluate(preset, 0)
        assertEquals(CalibrationStatus.PRESET, presetSummary.status)
        assertTrue(PlanScaleMode.UNIFORM in presetSummary.supportedModes)
    }

    @Test fun fittedVerifiedAndNeedsReviewStatuses() {
        val hRef = PlanReference(PlanPoint(0.0, 0.0), PlanPoint(100.0, 0.0), inches(100.0), check = false)
        val fitted = CalibrationDiagnostics.evaluate(PlanCalibrationLayer(references = listOf(hRef)), 0)
        assertEquals(CalibrationStatus.FITTED, fitted.status)
        assertTrue(PlanScaleMode.UNIFORM in fitted.supportedModes)
        assertFalse(PlanScaleMode.AXES in fitted.supportedModes)

        val goodCheck = PlanReference(PlanPoint(0.0, 20.0), PlanPoint(0.0, 120.0), inches(100.5), check = true)
        val verified = CalibrationDiagnostics.evaluate(PlanCalibrationLayer(references = listOf(hRef, goodCheck)), 0)
        assertEquals(CalibrationStatus.VERIFIED, verified.status)
        assertFalse(verified.residuals[1].exceedsTolerance)

        val badCheck = PlanReference(PlanPoint(0.0, 20.0), PlanPoint(0.0, 120.0), inches(110.0), check = true)
        val review = CalibrationDiagnostics.evaluate(PlanCalibrationLayer(references = listOf(hRef, badCheck)), 0)
        assertEquals(CalibrationStatus.NEEDS_REVIEW, review.status)
        assertTrue(review.residuals[1].exceedsTolerance)
    }

    @Test fun detectsSupportedModesForAxesAndAffineAndZoneDiagnostics() {
        val hRef = PlanReference(PlanPoint(0.0, 0.0), PlanPoint(100.0, 0.0), inches(100.0))
        val vRef = PlanReference(PlanPoint(0.0, 0.0), PlanPoint(0.0, 100.0), inches(105.0))
        val dRef = PlanReference(PlanPoint(0.0, 0.0), PlanPoint(100.0, 100.0), inches(145.0))

        val axesSummary = CalibrationDiagnostics.evaluate(
            PlanCalibrationLayer(references = listOf(hRef, vRef), mode = PlanScaleMode.AXES),
            0,
        )
        assertEquals(CalibrationStatus.FITTED, axesSummary.status)
        assertTrue(PlanScaleMode.UNIFORM in axesSummary.supportedModes)
        assertTrue(PlanScaleMode.AXES in axesSummary.supportedModes)
        assertFalse(PlanScaleMode.AFFINE in axesSummary.supportedModes)

        val affineSummary = CalibrationDiagnostics.evaluate(
            PlanCalibrationLayer(references = listOf(hRef, vRef, dRef), mode = PlanScaleMode.AFFINE),
            0,
        )
        assertTrue(PlanScaleMode.AFFINE in affineSummary.supportedModes)

        val zone = PlanZone(
            name = "Enlarged detail",
            page = 0,
            first = PlanPoint(0.0, 0.0),
            second = PlanPoint(200.0, 200.0),
            references = listOf(hRef),
            mode = PlanScaleMode.AFFINE,
        )
        val underdetermined = CalibrationDiagnostics.evaluateZone(zone)
        assertEquals(CalibrationStatus.NEEDS_REVIEW, underdetermined.status)
    }
}
