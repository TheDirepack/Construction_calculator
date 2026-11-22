package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.SheetSize
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored

enum class ScaleFamily(val label: String) {
    ARCHITECTURAL("Architectural"),
    ENGINEERING("Engineering"),
    METRIC("Metric"),
}

/**
 * A printed drawing scale. [ratio] is real length per unit of paper length, exactly (¼″ = 1′ is
 * 48: one paper inch is 48 real inches; 1:50 is 50). Page coordinates are PDF points (1/72″ of
 * paper), so the stored calibration is [ratio] / 72 real inches per point, an exact rational.
 */
data class ScalePreset(val label: String, val family: ScaleFamily, val ratio: Rational) {
    /** Exact real inches per page point, as stored in a zone or calibration layer preset. */
    val inchesPerPoint: Rational get() = ratio / Rational.of(72)

    fun stored(): StoredQuantity = Quantity.inches(inchesPerPoint).stored()
}

object ScalePresets {
    private fun arch(label: String, paperInchesPerFoot: Rational) =
        ScalePreset(label, ScaleFamily.ARCHITECTURAL, Rational.of(12) / paperInchesPerFoot)

    private fun eng(feetPerInch: Long) =
        ScalePreset("1″ = $feetPerInch′", ScaleFamily.ENGINEERING, Rational.of(feetPerInch * 12))

    private fun metric(denominator: Long) =
        ScalePreset("1:$denominator", ScaleFamily.METRIC, Rational.of(denominator))

    val all: List<ScalePreset> = listOf(
        arch("³⁄₃₂″ = 1′", Rational.of(3, 32)),
        arch("⅛″ = 1′", Rational.of(1, 8)),
        arch("³⁄₁₆″ = 1′", Rational.of(3, 16)),
        arch("¼″ = 1′", Rational.of(1, 4)),
        arch("⅜″ = 1′", Rational.of(3, 8)),
        arch("½″ = 1′", Rational.of(1, 2)),
        arch("¾″ = 1′", Rational.of(3, 4)),
        arch("1″ = 1′", Rational.of(1)),
        arch("1½″ = 1′", Rational.of(3, 2)),
        arch("3″ = 1′", Rational.of(3)),
        eng(10),
        eng(20),
        eng(30),
        eng(40),
        eng(50),
        eng(60),
        eng(100),
        metric(1),
        metric(5),
        metric(10),
        metric(20),
        metric(25),
        metric(50),
        metric(100),
        metric(200),
        metric(500),
        metric(1000),
    )

    /** The preset a stored inches-per-point value is exactly, or null for a custom value. */
    fun matching(inchesPerPoint: Rational?): ScalePreset? =
        inchesPerPoint?.let { value -> all.firstOrNull { it.inchesPerPoint == value } }

    /**
     * A caution for using a printed scale on this page, or null when the page size is unknown. A
     * preset holds only on a full-size print: a page that matches no standard sheet was probably
     * scanned, photographed or resized, and a standard sheet can still be a reduced set (a 24 × 36
     * set on 11 × 17 is half size).
     */
    fun warning(widthPoints: Double?, heightPoints: Double?): String? {
        if (widthPoints == null || heightPoints == null) return null
        val sheet = SheetSize.of(widthPoints, heightPoints)
            ?: return "Page size doesn't match a standard drawing sheet. It may be a scan, photo or resized print, so " +
                "a preset scale may be off. Check it with a known dimension."
        val reduced = when (sheet) {
            SheetSize.ANSI_B -> " An 11 × 17 page is often a half-size print of a 22 × 34 set."
            SheetSize.ARCH_B -> " A 12 × 18 page is often a half-size print of a 24 × 36 set."
            SheetSize.ISO_A3 -> " An A3 page is often a reduced print of an A1 set."
            else -> ""
        }
        return "Sheet matches ${sheet.label}.$reduced Check the scale with a known dimension if the set was printed at reduced size."
    }
}
