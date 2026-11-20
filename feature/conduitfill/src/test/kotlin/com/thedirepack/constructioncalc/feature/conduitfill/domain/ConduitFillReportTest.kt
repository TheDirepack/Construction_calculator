package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConduitFillReportTest {
    private fun conduit(type: ConduitType, size: TradeSize) = ConduitTable.find(type, size)!!
    private fun thhn(size: ConductorSize) = ConductorSpec.Insulated(InsulationType.THHN, size)
    private fun request(
        trade: TradeSize,
        vararg groups: Pair<Int, ConductorSpec>,
        type: ConduitType = ConduitType.EMT,
        nipple: Boolean = false,
        fit: ConductorSpec? = null,
    ) = FillRequest(conduit(type, trade), nipple, groups.map { ConductorGroup(it.first, it.second) }, fit)

    private fun ConduitFillReport.value(key: String): ResultValue = results.first { it.key == key }
    private fun ConduitFillReport.has(fragment: String, severity: ReportSeverity? = null) =
        notices.any { fragment in it.text && (severity == null || it.severity == severity) }

    @Test fun noConductorsGivesNoReport() {
        assertNull(ConduitFillReport.build(request(TradeSize.S_3_4)))
        assertNull(ConduitFillReport.build(request(TradeSize.S_3_4, 0 to thhn(ConductorSize.AWG_12))))
    }

    @Test fun resultsHaveRightKindsKeysAndExactValues() {
        // 3 x 12 AWG THHN + one bare 12 AWG ground in 3/4" EMT, and "how many 12 AWG THHN fit".
        val report = ConduitFillReport.build(
            request(
                TradeSize.S_3_4,
                3 to thhn(ConductorSize.AWG_12),
                1 to ConductorSpec.Bare(ConductorSize.AWG_12),
                fit = thhn(ConductorSize.AWG_12),
            ),
        )!!
        assertEquals(
            listOf("fill_percent", "fill_limit", "conductor_area", "allowed_area", "min_conduit", "max_count"),
            report.results.map { it.key },
        )

        val percent = report.value(ConduitFillReport.KEY_PERCENT)
        assertEquals(ValueKind.PERCENT, percent.kind)
        assertEquals("8.6%", percent.text)
        assertEquals(8.61, Rational.parse(percent.exact!!).toDouble(), 1e-9)

        val limit = report.value(ConduitFillReport.KEY_LIMIT)
        assertEquals(ValueKind.PERCENT, limit.kind)
        assertEquals("40", limit.exact)

        val conductorArea = report.value(ConduitFillReport.KEY_CONDUCTOR_AREA)
        assertEquals(ValueKind.AREA, conductorArea.kind) // in square inches, the AREA base unit
        assertEquals("0.0459 in²", conductorArea.text)
        assertEquals(0.0459, Rational.parse(conductorArea.exact!!).toDouble(), 1e-12)

        val allowed = report.value(ConduitFillReport.KEY_ALLOWED_AREA)
        assertEquals(ValueKind.AREA, allowed.kind)
        assertEquals("0.2130 in²", allowed.text)
        assertEquals(0.213, Rational.parse(allowed.exact!!).toDouble(), 1e-12)

        val min = report.value(ConduitFillReport.KEY_MIN_CONDUIT)
        assertEquals(ValueKind.TEXT, min.kind)
        assertNull(min.exact)
        assertEquals("1/2\" EMT", min.text) // 0.0459 fits 1/2" EMT (0.122)

        val count = report.value(ConduitFillReport.KEY_MAX_COUNT)
        assertEquals(ValueKind.NUMBER, count.kind)
        assertEquals("16", count.text) // Annex C: 12 AWG THHN in 3/4" EMT = 16
        assertEquals("16", count.exact)
        assertEquals("Most 12 AWG THHN in 3/4\" EMT", count.label)
        assertEquals("3/4\" EMT, 4 conductors: 8.6% fill (limit 40%)", report.summary)
    }

    @Test fun exactValuesCarryNoBinaryNoise() {
        // 3 x 0.0133 is 0.039899999999999996 as a double; the stored exact value is the clean decimal.
        val report = ConduitFillReport.build(request(TradeSize.S_3_4, 3 to thhn(ConductorSize.AWG_12)))!!
        assertEquals("399/10000", report.value(ConduitFillReport.KEY_CONDUCTOR_AREA).exact)
    }

    @Test fun maxCountResultIsOptional() {
        val report = ConduitFillReport.build(request(TradeSize.S_3_4, 3 to thhn(ConductorSize.AWG_12)))!!
        assertNull(report.maxCount)
        assertTrue(report.results.none { it.key == ConduitFillReport.KEY_MAX_COUNT })
    }

    @Test fun overLimitIsAnErrorWithTheFix() {
        val report = ConduitFillReport.build(request(TradeSize.S_1_2, 10 to thhn(ConductorSize.AWG_12)))!!
        assertTrue(report.has("Over the limit", ReportSeverity.ERROR))
        assertTrue(report.has("1/2\" EMT").not() || report.has("smallest EMT that works is 3/4\" EMT"))
        assertEquals("3/4\" EMT", report.value(ConduitFillReport.KEY_MIN_CONDUIT).text)
        assertEquals(TradeSize.S_3_4, report.minimumConduit!!.tradeSize)
    }

    @Test fun noSizeLargeEnoughIsSaid() {
        val report = ConduitFillReport.build(request(TradeSize.S_4, 60 to thhn(ConductorSize.KCMIL_1000)))!!
        assertTrue(report.has("No EMT trade size is large enough", ReportSeverity.ERROR))
        assertEquals("None fits", report.value(ConduitFillReport.KEY_MIN_CONDUIT).text)
    }

    @Test fun note7PermissionIsExplained() {
        val report = ConduitFillReport.build(request(TradeSize.S_1_1_4, 45 to thhn(ConductorSize.AWG_12)))!!
        assertTrue(report.analysis.permittedByNote7)
        assertTrue(report.has("Note 7", ReportSeverity.INFO))
        assertTrue(report.notices.none { it.severity == ReportSeverity.ERROR })
    }

    @Test fun jamWarningOnlyInRangeAndOnlyForThree() {
        val risky = ConduitFillReport.build(request(TradeSize.S_1_1_4, 3 to thhn(ConductorSize.AWG_1)))!!
        assertNotNull(risky.jam)
        assertTrue(risky.has("Jam ratio 3.09", ReportSeverity.WARNING))
        val fine = ConduitFillReport.build(request(TradeSize.S_1_1_4, 3 to thhn(ConductorSize.AWG_2)))!!
        assertTrue(fine.has("Jam ratio 3.59", ReportSeverity.INFO))
        assertTrue(fine.notices.none { it.severity == ReportSeverity.WARNING })
        val four = ConduitFillReport.build(request(TradeSize.S_1_1_4, 4 to thhn(ConductorSize.AWG_1)))!!
        assertNull(four.jam)
        assertTrue(!four.has("Jam ratio"))
    }

    @Test fun nippleModeUsesSixtyPercentAndSaysSo() {
        val report = ConduitFillReport.build(request(TradeSize.S_1_2, 13 to thhn(ConductorSize.AWG_12), nipple = true))!!
        assertEquals("60", report.value(ConduitFillReport.KEY_LIMIT).exact)
        assertTrue(report.analysis.withinLimit)
        assertTrue(report.has("24 in."))
        assertTrue(report.has("310.15(C)(1)"))
        // 12 AWG THHN alone in a 1/2" nipple: 0.182 / 0.0133 = 13.68.
        val fit = ConduitFillReport.build(request(TradeSize.S_1_2, 3 to thhn(ConductorSize.AWG_12), nipple = true, fit = thhn(ConductorSize.AWG_12)))!!
        assertEquals(13, fit.maxCount)
    }

    @Test fun notZeroCountWarning() {
        val report = ConduitFillReport.build(request(TradeSize.S_1_2, 1 to thhn(ConductorSize.AWG_12), fit = thhn(ConductorSize.AWG_4_0)))!!
        assertEquals(0, report.maxCount)
        assertTrue(report.has("Not even one", ReportSeverity.WARNING))
    }

    @Test fun alwaysStatesAssumptionsAndLimits() {
        val report = ConduitFillReport.build(request(TradeSize.S_3_4, 3 to thhn(ConductorSize.AWG_12)))!!
        assertTrue(report.has("NEC 2023 Chapter 9 Table 4"))
        assertTrue(report.has("Equipment grounding conductors"))
        assertTrue(report.has("Not covered"))
    }
}
