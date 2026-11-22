package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.codeLength
import com.thedirepack.constructioncalc.core.electrical.code.codeNumber
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.electrical.ConduitFill
import com.thedirepack.constructioncalc.core.electrical.FillAnalysis
import com.thedirepack.constructioncalc.core.electrical.JamCheck
import com.thedirepack.constructioncalc.core.electrical.ConductorGroup
import com.thedirepack.constructioncalc.core.electrical.FillConductor
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.PullFormulas
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.tradeSizeLabel
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** How serious a [ReportNotice] is. The screen maps these to its own notice styles. */
enum class ReportSeverity { INFO, WARNING, ERROR }

/** [refId] is the Reference entry the notice is about; the screen opens it when the notice is tapped. */
data class ReportNotice(val text: String, val severity: ReportSeverity = ReportSeverity.INFO, val refId: String? = null)

/** Reference entry ids the notices link to (stable ids from the Reference tool). */
object FillRefs {
    const val FILL_LIMITS = "nec-fill-percent"
    const val CONDUIT_DIMENSIONS = "nec-conduit-dimensions"
    const val CONDUCTOR_DIMENSIONS = "nec-conductor-dimensions"
    const val ADJUSTMENT = "nec-adjustment"
    const val AMBIENT = "nec-ambient"
}

/** Everything the screen needs to calculate: valid, already-resolved inputs. */
data class FillRequest(
    val conduit: ConduitSize,
    val nipple: Boolean,
    val groups: List<ConductorGroup>,
    /** The single conductor type/size for the "how many fit" count, or null to skip it. */
    val fit: FillConductor? = null,
)

/** The finished calculation: values for projects and other tools, plus notices to show. */
data class ConduitFillReport(
    val analysis: FillAnalysis,
    val minimumConduit: ConduitSize?,
    val maxCount: Int?,
    val jam: JamCheck?,
    val results: List<ResultValue>,
    val notices: List<ReportNotice>,
    val summary: String,
) {
    companion object {
        const val KEY_PERCENT = "fill_percent"
        const val KEY_LIMIT = "fill_limit"
        const val KEY_CONDUCTOR_AREA = "conductor_area"
        const val KEY_ALLOWED_AREA = "allowed_area"
        const val KEY_MIN_CONDUIT = "min_conduit"
        const val KEY_MAX_COUNT = "max_count"

        /** The report for [request], or null when there are no conductors to check. */
        fun build(request: FillRequest, settings: AppSettings = AppSettings()): ConduitFillReport? {
            val conduit = request.conduit
            val conduitName = conduitName(conduit, settings)
            fun area(x: Double) = if (settings.isMetric) settings.show(Measure.SMALL_AREA, x) else "${fmt(x, 4)} in²"
            val active = request.groups.filter { it.count > 0 }
            if (active.isEmpty()) return null
            val a = ConduitFill.analyze(conduit, active, request.nipple)
            val minimum = ConduitFill.minimumConduit(conduit.type, active, request.nipple)
            val fitArea = request.fit?.dimensions?.areaSqIn
            val maxCount = fitArea?.let { ConduitFill.maxCount(conduit, it, request.nipple) }
            val jam = ConduitFill.jamCheck(conduit, active)

            val results = buildList {
                add(decimalResult(KEY_PERCENT, "Conduit fill", ValueKind.PERCENT, a.percentFill, 2, "${fmt(a.percentFill, 1)}%"))
                add(ResultValue.number(KEY_LIMIT, "Fill limit (${CodeRule.FILL_PERCENT.cite})", ValueKind.PERCENT, Rational.of(a.limitPercent.toLong()), "${a.limitPercent}%"))
                add(decimalResult(KEY_CONDUCTOR_AREA, "Conductor area", ValueKind.AREA, a.totalAreaSqIn, 4, area(a.totalAreaSqIn)))
                add(decimalResult(KEY_ALLOWED_AREA, "Allowed area", ValueKind.AREA, a.allowedAreaSqIn, 4, area(a.allowedAreaSqIn)))
                add(ResultValue.text(KEY_MIN_CONDUIT, "Smallest ${conduit.type.displayName}", minimum?.let { conduitName(it, settings) } ?: "None fits"))
                if (maxCount != null) {
                    add(
                        ResultValue.number(
                            KEY_MAX_COUNT,
                            "Most ${request.fit.shortLabel} in $conduitName",
                            ValueKind.NUMBER,
                            Rational.of(maxCount.toLong()),
                            maxCount.toString(),
                        ),
                    )
                }
            }

            val notices = buildList {
                if (!a.withinLimit) {
                    val fix = if (minimum != null) "The smallest ${conduit.type.displayName} that works is ${conduitName(minimum, settings)}."
                    else "No ${conduit.type.displayName} trade size is large enough."
                    add(
                        ReportNotice(
                            "Over the limit: ${fmt(a.percentFill, 1)}% fill against the ${a.limitPercent}% maximum " +
                                "(${a.conductorCount} conductors${if (request.nipple) ", nipple" else ""}). $fix",
                            ReportSeverity.ERROR,
                            FillRefs.FILL_LIMITS,
                        ),
                    )
                } else if (a.permittedByNote7) {
                    add(
                        ReportNotice(
                            "Fill is ${fmt(a.percentFill, 1)}%, a hair over ${a.limitPercent}%, but allowed: " +
                                "${CodeRule.FILL_ROUNDING.cite} rounds a count up when the decimal is ${codeNumber(Code.book.values.note7RoundUpFraction)} or more and all conductors are the same size.",
                            refId = FillRefs.FILL_LIMITS,
                        ),
                    )
                }
                if (maxCount != null && maxCount == 0) {
                    add(ReportNotice("Not even one ${request.fit.shortLabel} fits in $conduitName.", ReportSeverity.WARNING))
                }
                if (jam != null) {
                    val r = fmt(jam.ratio, 2)
                    val of = if (jam.mixedSizes) "the largest conductor" else "the conductor"
                    if (jam.inJamRange) {
                        add(
                            ReportNotice(
                                "Jam ratio $r (${PullFormulas.OVALITY_LABEL}: conduit inside diameter D over $of outside diameter d, plus 5% for bend ovality) is between ${codeNumber(ConduitFill.JAM_LOW)} and ${codeNumber(ConduitFill.JAM_HIGH)}. " +
                                    "Three conductors can jam when pulled, so consider another size or an offset pull plan (${CodeRule.JAM_RATIO.cite}).",
                                ReportSeverity.WARNING,
                                FillRefs.FILL_LIMITS,
                            ),
                        )
                    } else {
                        add(ReportNotice("Jam ratio $r (${PullFormulas.OVALITY_LABEL}) for three conductors, outside the ${codeNumber(ConduitFill.JAM_LOW)}–${codeNumber(ConduitFill.JAM_HIGH)} range where jamming can occur.", refId = FillRefs.FILL_LIMITS))
                    }
                }
                val cables = active.map { it.spec }.filterIsInstance<FillConductor.Custom>()
                if (cables.isNotEmpty()) {
                    add(
                        ReportNotice(
                            "Named cables use their own outside diameter and area (${cables.joinToString { it.name }}), not the NEC tables. " +
                                "Each counts as one conductor for the fill limit; derating below covers only the table conductors.",
                        ),
                    )
                }
                if (request.nipple) {
                    add(
                        ReportNotice(
                            "Nipple fill (${codePercentValue(Code.book.values.fillNipplePercent, spaced = false)}) applies only to nipples ${Code.book.values.let { codeLength(it.nippleMaxLengthIn, it.nippleMaxLengthMm, settings.unitSystem) }} or shorter between boxes, cabinets or similar " +
                                "enclosures. ${CodeRule.FILL_NIPPLES.cite} also waives the ${CodeRule.CONDUCTOR_COUNT_ADJUSTMENT.cite} adjustment factors for them.",
                            refId = FillRefs.FILL_LIMITS,
                        ),
                    )
                }
                add(
                    ReportNotice(
                        "Areas: ${Code.label} ${CodeRule.CONDUIT_DIMENSIONS.cite} (conduit), ${CodeRule.CONDUCTOR_DIMENSIONS.cite} (insulated wire, approximate), ${CodeRule.CONDUCTOR_PROPERTIES.cite} (bare wire) and the " +
                            "${CodeRule.FILL_PERCENT.cite} limits. Check the edition your jurisdiction has adopted.",
                        refId = FillRefs.CONDUCTOR_DIMENSIONS,
                    ),
                )
                add(
                    ReportNotice(
                        "Equipment grounding conductors, bare or insulated, count toward fill (${CodeRule.FILL_GROUNDING_CONDUCTORS.cite}). Compact conductors " +
                            "(${CodeRule.COMPACT_CONDUCTOR_DIMENSIONS.cite}), multiconductor cables and fixture wires aren't included: add them as named cables with their actual dimensions.",
                        refId = FillRefs.FILL_LIMITS,
                    ),
                )
                add(
                    ReportNotice(
                        "With an ambient temperature entered, derating covers the insulated building-wire groups: ambient correction and more than 3 current-carrying " +
                            "conductors (${CodeRule.AMPACITY.cite}). Named cables aren't derated here. Pull length, bends and box fill are in their own tools.",
                    ),
                )
            }

            val summary = "$conduitName, ${a.conductorCount} conductors: ${fmt(a.percentFill, 1)}% fill " +
                "(limit ${a.limitPercent}%${if (request.nipple) ", nipple" else ""})"
            return ConduitFillReport(a, minimum, maxCount, jam, results, notices, summary)
        }

        /** [value] rounded to [scale] decimals as an exact rational, so no binary-double noise is stored. */
        private fun decimalResult(key: String, label: String, kind: ValueKind, value: Double, scale: Int, text: String) =
            ResultValue(key, label, kind, Rational.of(BigDecimal(value).setScale(scale, RoundingMode.HALF_UP)).toString(), text)

        /** `3/4 EMT`, or `3/4 (21) EMT` in metric. */
        fun conduitName(size: ConduitSize, settings: AppSettings) = "${settings.tradeSizeLabel(size.tradeSize)} ${size.type.displayName}"

        private fun fmt(x: Double, decimals: Int) = com.thedirepack.constructioncalc.core.measure.Decimals.fixed(x, decimals)
    }
}
