package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.DeratingResult
import com.thedirepack.constructioncalc.core.electrical.DeratingRow
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.explanations
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.feature.conduitfill.ConduitFillInputs
import kotlin.math.abs

/** Direction of thermal and conductor-count ampacity adjustment. */
enum class ThermalAdjustmentKind {
    /** 30 °C ambient basis and ≤ 3 CCC (×1.00). */
    NOMINAL,
    /** < 30 °C ambient yields Table 310.15(B)(1)(1) factor > 1.00 and no count derating. */
    RATING_INCREASE,
    /** > 30 °C / rooftop ambient derating and/or > 3 CCC count adjustment (< 1.00). */
    DERATING,
    /** < 30 °C ambient rating increase (> 1.00) combined with > 3 CCC or rooftop derating (< 1.00). */
    MIXED,
}

/** High-level thermal & ampacity summary for top card and collapsible AdvancedSection header. */
data class ThermalStatus(
    val kind: ThermalAdjustmentKind,
    val currentCarrying: Int,
    val adjustmentFactor: Double,
    val hasAmbientIncrease: Boolean,
    val hasAmbientDerating: Boolean,
    val hasNetAmpacityIncrease: Boolean,
    val summary: String,
    val compactLabel: String,
)

/** True when this conductor row receives a Table 310.15(B)(1)(1) ambient factor > 1.00 (< 30 °C). */
fun DeratingRow.hasAmbientRatingIncrease(): Boolean = (ambientFactor ?: 1.0) > 1.0 + 1e-9

/** True when adjusted conductor ampacity (before 110.14(C) terminal cap) exceeds the table rating. */
fun DeratingRow.hasNetAmpacityIncrease(): Boolean {
    val adj = adjusted ?: return false
    val table = tableAmpacity?.toDouble() ?: return false
    return adj > table + 1e-9
}

/** Summarizes whether thermals are nominal, increased (< 30 °C), derated (> 30 °C / > 3 CCC), or mixed. */
fun DeratingResult.thermalStatus(units: UnitSystem = UnitSystem.IMPERIAL): ThermalStatus {
    val hasIncrease = rows.any { it.hasAmbientRatingIncrease() }
    val hasAmbientDerate = rows.any { (it.ambientFactor ?: 1.0) < 1.0 - 1e-9 }
    val hasCountDerate = adjustmentFactor < 1.0 - 1e-9
    val hasNetIncrease = rows.any { it.hasNetAmpacityIncrease() }

    val kind = when {
        hasIncrease && (hasCountDerate || hasAmbientDerate) -> ThermalAdjustmentKind.MIXED
        hasIncrease -> ThermalAdjustmentKind.RATING_INCREASE
        hasAmbientDerate || hasCountDerate -> ThermalAdjustmentKind.DERATING
        else -> ThermalAdjustmentKind.NOMINAL
    }

    val factors = rows.mapNotNull { it.ambientFactor }.distinct()
    val ambientDesc = when {
        factors.isEmpty() -> ""
        factors.size == 1 && factors.single() > 1.0 + 1e-9 -> "ambient ×${Decimals.trim(factors.single())} increase"
        factors.size == 1 && factors.single() < 1.0 - 1e-9 -> "ambient ×${Decimals.trim(factors.single())} derating"
        factors.size > 1 -> "ambient ${factors.min().let(Decimals::trim)}–${factors.max().let(Decimals::trim)}×"
        else -> "ambient ×1"
    }
    val countDesc = if (hasCountDerate) "count ×${Decimals.trim(adjustmentFactor)}" else "≤3 CCC"

    val firstRow = rows.firstOrNull { it.usable != null }
    val firstAmpDesc = firstRow?.let { r ->
        val usableTxt = "${Decimals.trim(r.usable!!)} A"
        val adjTxt = r.adjusted?.takeIf { kotlin.math.abs(it - r.usable!!) > 1e-9 }?.let { " (adj ${Decimals.trim(it)} A)" }.orEmpty()
        "$usableTxt$adjTxt"
    }

    val summary = buildList {
        add("$currentCarrying CCC ($countDesc)")
        if (ambientDesc.isNotEmpty()) add(ambientDesc)
        if (firstAmpDesc != null) add(firstAmpDesc)
    }.joinToString(" · ")

    val compactLabel = when (kind) {
        ThermalAdjustmentKind.NOMINAL -> "$currentCarrying CCC · nominal (×1)"
        ThermalAdjustmentKind.RATING_INCREASE -> {
            val f = factors.maxOrNull()?.let { "×${Decimals.trim(it)}" } ?: ">1.00"
            "$currentCarrying CCC · $f rating increase (<${Measure.TEMPERATURE.format(Code.book.values.ambientBasisC, units, 0)})"
        }
        ThermalAdjustmentKind.MIXED -> {
            val f = factors.maxOrNull()?.let { "×${Decimals.trim(it)}" } ?: ">1.00"
            "$currentCarrying CCC (×${Decimals.trim(adjustmentFactor)}) · $f cold boost"
        }
        ThermalAdjustmentKind.DERATING -> buildString {
            append("$currentCarrying CCC")
            if (hasCountDerate) append(" ×${Decimals.trim(adjustmentFactor)}")
            if (hasAmbientDerate) {
                val f = factors.minOrNull()?.let { "×${Decimals.trim(it)}" } ?: "<1.00"
                append(" · ambient $f")
            }
            append(" derating")
        }
    }

    return ThermalStatus(
        kind = kind,
        currentCarrying = currentCarrying,
        adjustmentFactor = adjustmentFactor,
        hasAmbientIncrease = hasIncrease,
        hasAmbientDerating = hasAmbientDerate,
        hasNetAmpacityIncrease = hasNetIncrease,
        summary = summary,
        compactLabel = compactLabel,
    )
}

/** True when >3 CCC, ambient temperature differs from 30 °C, or rooftop sunlight adder is active. */
fun ConduitFillInputs.hasNonDefaultThermals(derating: DeratingResult? = null): Boolean {
    val ambient = ambientC.trim().replace(',', '.').toDoubleOrNull()
    val nonDefaultAmbient = ambient != null && abs(ambient - Code.book.values.ambientBasisC) > 1e-6
    val overThreeCcc = (derating?.currentCarrying ?: 0) > 3
    return rooftop || nonDefaultAmbient || overThreeCcc
}

/**
 * Thermal adjustment and ampacity results as [ResultValue] items, explicitly surfacing both
 * >30 °C derating and <30 °C rating increases (Table 310.15(B)(1)(1) factors > 1.00).
 */
fun DeratingResult.toResults(): List<ResultValue> = buildList {
    val countNote = if (adjustmentFactor < 1.0 - 1e-9) {
        "$currentCarrying · adjustment factor ×${Decimals.trim(adjustmentFactor)}"
    } else {
        "$currentCarrying · ×1 (no count adjustment)"
    }
    add(
        ResultValue.number(
            "derate_ccc",
            "Current-carrying conductors",
            ValueKind.NUMBER,
            currentCarrying.toDouble(),
            countNote,
        ),
    )
    rows.forEachIndexed { i, row ->
        val usable = row.usable ?: return@forEachIndexed
        val g = row.group
        val code = g.insulation?.code.orEmpty()
        val labelSuffix = when {
            row.hasNetAmpacityIncrease() -> "ampacity (increased)"
            row.hasAmbientRatingIncrease() -> "adjusted ampacity"
            (row.ambientFactor ?: 1.0) < 1.0 - 1e-9 || adjustmentFactor < 1.0 - 1e-9 -> "derated"
            else -> "ampacity"
        }
        val adj = row.adjusted
        val detail = buildString {
            append("${Decimals.trim(usable)} A")
            if (adj != null && abs(adj - usable) > 1e-9) {
                val factorPart = row.ambientFactor?.takeIf { abs(it - 1.0) > 1e-9 }?.let { ", ×${Decimals.trim(it)}" }.orEmpty()
                append(" (adjusted ${Decimals.trim(adj)} A$factorPart)")
            } else if (row.hasAmbientRatingIncrease() && row.ambientFactor != null) {
                append(" (ambient ×${Decimals.trim(row.ambientFactor!!)}, count ×${Decimals.trim(adjustmentFactor)})")
            }
            row.maxOcpd?.let { append(" · max $it A OCPD") }
        }
        add(
            ResultValue.number(
                "derate_$i",
                "${g.count} × ${g.size.label} $code $labelSuffix".trim(),
                ValueKind.CURRENT,
                kotlin.math.round(usable * 10) / 10.0,
                detail,
            ),
        )
    }
}

/**
 * The arithmetic lines and notes under the thermal/ampacity results; temperatures are in [units].
 * Includes explicit < 30 °C rating-increase explanation whenever any row has ambient factor > 1.00.
 */
fun DeratingResult.noticeTexts(units: UnitSystem): List<String> {
    val extra = if (rows.any { it.hasAmbientRatingIncrease() }) {
        val basis = Measure.TEMPERATURE.format(Code.book.values.ambientBasisC, units, 0)
        listOf(
            "Ambient rating increase (< $basis): ${CodeRule.AMBIENT_CORRECTION.cite} applies correction factors > 1.00, increasing conductor ampacity before ${CodeRule.TERMINATION_TEMPERATURE.cite} terminal limits and helping offset >3 CCC adjustment.",
        )
    } else {
        emptyList()
    }
    return explanations(units = units) + extra + notes
}
