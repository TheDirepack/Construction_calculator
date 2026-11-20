package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import java.util.Locale
import kotlin.math.round

/** How serious a [ReportNotice] is. The screen maps these to its own notice styles. */
enum class ReportSeverity { INFO, WARNING, ERROR }

data class ReportNotice(val text: String, val severity: ReportSeverity = ReportSeverity.INFO)

/** Everything the screen needs to calculate: valid, already-resolved inputs. */
data class FillRequest(
    val conduit: ConduitSize,
    val nipple: Boolean,
    val groups: List<ConductorGroup>,
    /** The single conductor type/size for the "how many fit" count, or null to skip it. */
    val fit: ConductorSpec? = null,
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
        fun build(request: FillRequest): ConduitFillReport? {
            val conduit = request.conduit
            val active = request.groups.filter { it.count > 0 }
            if (active.isEmpty()) return null
            val a = ConduitFill.analyze(conduit, active, request.nipple)
            val minimum = ConduitFill.minimumConduit(conduit.type, active, request.nipple)
            val fitArea = request.fit?.dimensions?.areaSqIn
            val maxCount = fitArea?.let { ConduitFill.maxCount(conduit, it, request.nipple) }
            val jam = ConduitFill.jamCheck(conduit, active)

            val results = buildList {
                add(ResultValue.number(KEY_PERCENT, "Conduit fill", ValueKind.PERCENT, round2(a.percentFill), "${fmt(a.percentFill, 1)}%"))
                add(ResultValue.number(KEY_LIMIT, "Fill limit (Table 1)", ValueKind.PERCENT, a.limitPercent.toDouble(), "${a.limitPercent}%"))
                add(areaResult(KEY_CONDUCTOR_AREA, "Conductor area", a.totalAreaSqIn))
                add(areaResult(KEY_ALLOWED_AREA, "Allowed area", a.allowedAreaSqIn))
                add(ResultValue.text(KEY_MIN_CONDUIT, "Smallest ${conduit.type.displayName}", minimum?.displayName ?: "None fits"))
                if (maxCount != null) {
                    add(
                        ResultValue.number(
                            KEY_MAX_COUNT,
                            "Most ${request.fit.label} in ${conduit.displayName}",
                            ValueKind.NUMBER,
                            maxCount.toDouble(),
                            maxCount.toString(),
                        ),
                    )
                }
            }

            val notices = buildList {
                if (!a.withinLimit) {
                    val fix = if (minimum != null) "The smallest ${conduit.type.displayName} that works is ${minimum.displayName}."
                    else "No ${conduit.type.displayName} trade size is large enough."
                    add(
                        ReportNotice(
                            "Over the limit: ${fmt(a.percentFill, 1)}% fill against the ${a.limitPercent}% maximum " +
                                "(${a.conductorCount} conductors${if (request.nipple) ", nipple" else ""}). $fix",
                            ReportSeverity.ERROR,
                        ),
                    )
                } else if (a.permittedByNote7) {
                    add(
                        ReportNotice(
                            "Fill is ${fmt(a.percentFill, 1)}%, a hair over ${a.limitPercent}%, but allowed: " +
                                "Table 1 Note 7 rounds a count up when the decimal is 0.8 or more and all conductors are the same size.",
                        ),
                    )
                }
                if (maxCount != null && maxCount == 0) {
                    add(ReportNotice("Not even one ${request.fit.label} fits in ${conduit.displayName}.", ReportSeverity.WARNING))
                }
                if (jam != null) {
                    val r = fmt(jam.ratio, 2)
                    val of = if (jam.mixedSizes) "the largest conductor" else "the conductor"
                    if (jam.inJamRange) {
                        add(
                            ReportNotice(
                                "Jam ratio $r (conduit inside diameter / $of outside diameter) is between 2.8 and 3.2. " +
                                    "Three conductors can jam when pulled, so consider another size or an offset pull plan (Table 1 Informational Note 2).",
                                ReportSeverity.WARNING,
                            ),
                        )
                    } else {
                        add(ReportNotice("Jam ratio $r for three conductors, outside the 2.8–3.2 range where jamming can occur."))
                    }
                }
                if (request.nipple) {
                    add(
                        ReportNotice(
                            "Nipple fill (60%) applies only to nipples 24 in. (600 mm) or shorter between boxes, cabinets or similar " +
                                "enclosures. Table 1 Note 4 also waives the 310.15(B)(3)(a) adjustment factors for them.",
                        ),
                    )
                }
                add(
                    ReportNotice(
                        "Areas: NEC Chapter 9 Table 4 (conduit), Table 5 (insulated wire, approximate), Table 8 (bare wire) and the " +
                            "Table 1 limits, using values from the NEC 2017 and 2020 tables. Check the edition your jurisdiction has adopted.",
                    ),
                )
                add(
                    ReportNotice(
                        "Equipment grounding conductors, bare or insulated, count toward fill (Table 1 Note 3). Compact conductors " +
                            "(Table 5A), multiconductor cables and fixture wires aren't included: use their actual dimensions.",
                    ),
                )
                add(
                    ReportNotice(
                        "Not covered: conductor derating for more than 3 current-carrying conductors or ambient temperature " +
                            "(310.15), ampacity, pull length and bends, or box fill.",
                    ),
                )
            }

            val summary = "${conduit.displayName}, ${a.conductorCount} conductors: ${fmt(a.percentFill, 1)}% fill " +
                "(limit ${a.limitPercent}%${if (request.nipple) ", nipple" else ""})"
            return ConduitFillReport(a, minimum, maxCount, jam, results, notices, summary)
        }

        private fun areaResult(key: String, label: String, sqIn: Double) =
            ResultValue.number(key, label, ValueKind.NUMBER, round4(sqIn), "${fmt(sqIn, 4)} in²")

        private fun round2(x: Double) = round(x * 100.0) / 100.0
        private fun round4(x: Double) = round(x * 10_000.0) / 10_000.0

        private fun fmt(x: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", x)
    }
}
