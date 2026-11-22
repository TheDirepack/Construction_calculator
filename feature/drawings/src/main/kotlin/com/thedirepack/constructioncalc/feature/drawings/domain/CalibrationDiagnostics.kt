package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.model.PlanCalibration
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanScaleMode
import com.thedirepack.constructioncalc.core.model.PlanZone
import kotlin.math.abs

enum class CalibrationStatus(val label: String) {
    UNCALIBRATED("Uncalibrated"),
    PRESET("Preset"),
    FITTED("Fitted"),
    VERIFIED("Verified"),
    NEEDS_REVIEW("Needs review"),
}

data class ReferenceResidual(
    val index: Int,
    val reference: PlanReference,
    /** Signed estimated-minus-entered difference in inches, or null if not fitted. */
    val deltaInches: Double?,
    /** Signed percentage difference, or null if not fitted. */
    val percentError: Double?,
    val exceedsTolerance: Boolean,
)

data class CalibrationSummary(
    val status: CalibrationStatus,
    val activeMode: PlanScaleMode,
    val fit: PlanCalibration?,
    val supportedModes: Set<PlanScaleMode>,
    val residuals: List<ReferenceResidual>,
    val maxAbsPercentError: Double?,
    val message: String,
)

object CalibrationDiagnostics {
    const val DEFAULT_TOLERANCE_PERCENT = 1.0

    fun evaluate(
        calibration: PlanCalibrationLayer,
        page: Int,
        tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT,
    ): CalibrationSummary {
        val activeMode = calibration.modeFor(page)
        val fit = calibration.fit(page)
        val supported = PlanScaleMode.entries.filterTo(linkedSetOf()) { mode ->
            val probe = if (calibration.pageModes.containsKey(page)) {
                calibration.copy(pageModes = calibration.pageModes + (page to mode))
            } else {
                calibration.copy(mode = mode)
            }
            probe.fit(page) != null
        }
        return summarize(
            activeMode = activeMode,
            hasPreset = calibration.preset != null,
            references = calibration.references,
            fit = fit,
            supportedModes = supported,
            tolerancePercent = tolerancePercent,
        )
    }

    fun evaluateZone(
        zone: PlanZone,
        tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT,
    ): CalibrationSummary {
        val fit = zone.fit()
        val supported = PlanScaleMode.entries.filterTo(linkedSetOf()) { mode ->
            zone.copy(mode = mode).fit() != null
        }
        return summarize(
            activeMode = zone.mode,
            hasPreset = zone.preset != null,
            references = zone.references,
            fit = fit,
            supportedModes = supported,
            tolerancePercent = tolerancePercent,
        )
    }

    private fun summarize(
        activeMode: PlanScaleMode,
        hasPreset: Boolean,
        references: List<PlanReference>,
        fit: PlanCalibration?,
        supportedModes: Set<PlanScaleMode>,
        tolerancePercent: Double,
    ): CalibrationSummary {
        val fittingCount = references.count { !it.check }
        val checkCount = references.count { it.check }
        val residuals = references.mapIndexed { index, ref ->
            val delta = fit?.residuals?.getOrNull(index)
            val entered = ref.length.toQuantity().value.toDouble()
            val pct = if (delta != null && entered > 0.0) 100.0 * delta / entered else null
            val exceeds = pct != null && abs(pct) > tolerancePercent
            ReferenceResidual(index, ref, delta, pct, exceeds)
        }
        val maxAbsPct = residuals.mapNotNull { it.percentError?.let(::abs) }.maxOrNull()
        val hasConflict = residuals.any { it.exceedsTolerance }

        val status = when {
            fit == null && references.isEmpty() && !hasPreset -> CalibrationStatus.UNCALIBRATED
            fit == null -> CalibrationStatus.NEEDS_REVIEW
            hasConflict -> CalibrationStatus.NEEDS_REVIEW
            fittingCount == 0 && hasPreset && checkCount > 0 -> CalibrationStatus.VERIFIED
            fittingCount == 0 && hasPreset -> CalibrationStatus.PRESET
            checkCount > 0 -> CalibrationStatus.VERIFIED
            else -> CalibrationStatus.FITTED
        }

        val message = when (status) {
            CalibrationStatus.UNCALIBRATED ->
                "Pick a preset or use Calibrate to tap known dimensions."
            CalibrationStatus.PRESET ->
                "Preset scale active. Add a check dimension to verify."
            CalibrationStatus.FITTED ->
                "Fitted from $fittingCount dimension(s). Add an independent check to verify."
            CalibrationStatus.VERIFIED ->
                "Verified against $checkCount check dimension(s) within ${formatTol(tolerancePercent)}%."
            CalibrationStatus.NEEDS_REVIEW -> when {
                fit == null -> modeRequirementHint(activeMode)
                else -> "One or more dimensions differ by more than ${formatTol(tolerancePercent)}%. Review references or try another correction mode."
            }
        }

        return CalibrationSummary(
            status = status,
            activeMode = activeMode,
            fit = fit,
            supportedModes = supportedModes,
            residuals = residuals,
            maxAbsPercentError = maxAbsPct,
            message = message,
        )
    }

    private fun modeRequirementHint(mode: PlanScaleMode): String = when (mode) {
        PlanScaleMode.UNIFORM -> "Add at least 1 known dimension or pick a preset."
        PlanScaleMode.AXES -> "X/Y mode needs at least 2 references along different axes."
        PlanScaleMode.AFFINE -> "Skew mode needs at least 3 references in independent directions (e.g., horizontal, vertical, diagonal)."
        PlanScaleMode.DISTORTED -> "2D Warp needs at least 2 spread-out references."
    }

    private fun formatTol(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
