package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulatedConductorArea
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConduitFillTest {
    private fun conduit(type: ConduitType, size: TradeSize): ConduitSize = ConduitTable.find(type, size)!!
    private fun emt(size: TradeSize) = conduit(ConduitType.EMT, size)
    private fun wire(type: InsulationType, size: ConductorSize) = ConductorSpec.Insulated(type, size)
    private fun thhn(size: ConductorSize) = wire(InsulationType.THHN, size)
    private fun area(type: InsulationType, size: ConductorSize) = InsulatedConductorArea.area(type, size)!!

    // ---- Table 1 limits and Note 7 -------------------------------------------------------------------

    @Test fun table1Limits() {
        assertEquals(53, ConduitFill.limitPercent(1))
        assertEquals(31, ConduitFill.limitPercent(2))
        assertEquals(40, ConduitFill.limitPercent(3))
        assertEquals(40, ConduitFill.limitPercent(30))
        // Table 1 Note 4: nipples of 24 in. or less may be filled to 60% whatever the count.
        for (count in listOf(1, 2, 3, 9)) assertEquals(60, ConduitFill.limitPercent(count, nipple = true))
    }

    @Test fun note7RoundsUpFromPointEight() {
        assertEquals(45, ConduitFill.note7Round(44.96)) // 12 AWG THHN in 1-1/4" EMT: 0.598 / 0.0133
        assertEquals(1, ConduitFill.note7Round(0.8))
        assertEquals(0, ConduitFill.note7Round(0.79))
        assertEquals(3, ConduitFill.note7Round(2.8))
        assertEquals(2, ConduitFill.note7Round(2.79))
        assertEquals(9, ConduitFill.note7Round(9.0))
        assertEquals(0, ConduitFill.note7Round(0.0))
    }

    // ---- Annex C: published counts ---------------------------------------------------------------------
    //
    // Rows below are transcribed from NEC 2023 Annex C, Table C.1 (EMT, printed pp. 70-774 and 70-775), Table C.4
    // (IMC, p. 70-790 of the PDF) and Table C.9 (RMC). Columns are trade sizes 1/2" up. The 2023 Annex C also
    // has 5" and 6" EMT columns, which are not checked because EMT 5" and 6" are not offered (see ConduitTable).

    private val sizes10 = listOf(
        TradeSize.S_1_2, TradeSize.S_3_4, TradeSize.S_1, TradeSize.S_1_1_4, TradeSize.S_1_1_2,
        TradeSize.S_2, TradeSize.S_2_1_2, TradeSize.S_3, TradeSize.S_3_1_2, TradeSize.S_4,
    )
    private val sizes12 = sizes10 + listOf(TradeSize.S_5, TradeSize.S_6)

    private fun check(type: ConduitType, trades: List<TradeSize>, label: String, areaSqIn: Double, book: List<Int>, differsFromBook: Map<TradeSize, Int> = emptyMap()) {
        assertEquals(trades.size, book.size)
        trades.forEachIndexed { i, trade ->
            val computed = ConduitFill.maxCount(conduit(type, trade), areaSqIn)
            val expected = differsFromBook[trade] ?: book[i]
            assertEquals("$label in ${trade.display} $type (Annex C says ${book[i]})", expected, computed)
        }
    }

    @Test fun annexCHeadlineCounts() {
        // 12 AWG THHN in 1/2" EMT = 9; 10 AWG THHN in 3/4" EMT = 10 (the two standard worked examples).
        assertEquals(9, ConduitFill.maxCount(emt(TradeSize.S_1_2), area(InsulationType.THHN, ConductorSize.AWG_12)))
        assertEquals(10, ConduitFill.maxCount(emt(TradeSize.S_3_4), area(InsulationType.THHN, ConductorSize.AWG_10)))
        // 12 AWG THHN in 1-1/4" EMT: 0.598 / 0.0133 = 44.96, which Note 7 rounds up to 45 (Table C.1 prints 45).
        assertEquals(45, ConduitFill.maxCount(emt(TradeSize.S_1_1_4), area(InsulationType.THHN, ConductorSize.AWG_12)))
    }

    @Test fun annexC1EmtThhn() {
        val thhn = InsulationType.THHN
        fun row(size: ConductorSize, vararg counts: Int, differs: Map<TradeSize, Int> = emptyMap()) =
            check(ConduitType.EMT, sizes10, "${size.label} THHN", area(thhn, size), counts.toList(), differs)
        row(ConductorSize.AWG_14, 12, 22, 35, 61, 84, 138, 241, 364, 476, 608)
        row(ConductorSize.AWG_12, 9, 16, 26, 45, 61, 101, 176, 266, 347, 443)
        row(ConductorSize.AWG_10, 5, 10, 16, 28, 38, 63, 111, 167, 219, 279)
        row(ConductorSize.AWG_8, 3, 6, 9, 16, 22, 36, 64, 96, 126, 161)
        // 387 mm² / 32.71 mm² = 11.83 gives 12 in the book's metric working; in square inches the quotient is
        // 11.795, just under Note 7's 0.8, so this tool gives 11.
        row(ConductorSize.AWG_6, 2, 4, 7, 12, 16, 26, 46, 69, 91, 116, differs = mapOf(TradeSize.S_1_1_4 to 11))
        row(ConductorSize.AWG_4, 1, 2, 4, 7, 10, 16, 28, 43, 56, 71)
        row(ConductorSize.AWG_3, 1, 1, 3, 6, 8, 13, 24, 36, 47, 60)
        row(ConductorSize.AWG_2, 1, 1, 3, 5, 7, 11, 20, 30, 40, 51)
        row(ConductorSize.AWG_1, 1, 1, 1, 4, 5, 8, 15, 22, 29, 37)
    }

    @Test fun annexC1EmtOtherInsulations() {
        // TW, THW, THHW, THW-2 and XHHW at 14 to 8 AWG share areas.
        fun thw(size: ConductorSize, vararg counts: Int) =
            check(ConduitType.EMT, sizes10, "${size.label} THW", area(InsulationType.THW, size), counts.toList())
        thw(ConductorSize.AWG_14, 8, 15, 25, 43, 58, 96, 168, 254, 332, 424)
        thw(ConductorSize.AWG_12, 6, 11, 19, 33, 45, 74, 129, 195, 255, 326)
        thw(ConductorSize.AWG_10, 5, 8, 14, 24, 33, 55, 96, 145, 190, 243)
        thw(ConductorSize.AWG_8, 2, 5, 8, 13, 18, 30, 53, 81, 105, 135)
        thw(ConductorSize.AWG_6, 1, 3, 4, 8, 11, 18, 32, 48, 63, 81)
        thw(ConductorSize.AWG_4, 1, 1, 3, 6, 8, 13, 24, 36, 47, 60)
        thw(ConductorSize.AWG_1, 1, 1, 1, 3, 4, 7, 12, 18, 24, 31)

        fun rhhUncovered(size: ConductorSize, vararg counts: Int) =
            check(ConduitType.EMT, sizes10, "${size.label} RHH*", area(InsulationType.RHH_NO_COVERING, size), counts.toList())
        rhhUncovered(ConductorSize.AWG_14, 6, 10, 16, 28, 39, 64, 112, 169, 221, 282)
        rhhUncovered(ConductorSize.AWG_12, 4, 8, 13, 23, 31, 51, 90, 136, 177, 227)
        rhhUncovered(ConductorSize.AWG_10, 3, 6, 10, 18, 24, 40, 70, 106, 138, 177)
        rhhUncovered(ConductorSize.AWG_8, 1, 4, 6, 10, 14, 24, 42, 63, 83, 106)

        fun rhhCovered(size: ConductorSize, differs: Map<TradeSize, Int>, vararg counts: Int) =
            check(ConduitType.EMT, sizes10, "${size.label} RHH", area(InsulationType.RHH_COVERED, size), counts.toList(), differs)
        // 222 mm² / 18.90 mm² = 11.75 in the book (11), but 0.346 / 0.0293 = 11.81 in square inches, which Note 7 rounds to 12.
        rhhCovered(ConductorSize.AWG_14, mapOf(TradeSize.S_1 to 12), 4, 7, 11, 20, 27, 46, 80, 120, 157, 201)
        rhhCovered(ConductorSize.AWG_12, mapOf(TradeSize.S_1 to 10), 3, 6, 9, 17, 23, 38, 66, 100, 131, 167)
        rhhCovered(ConductorSize.AWG_10, emptyMap(), 2, 5, 8, 13, 18, 30, 53, 81, 105, 135)
        rhhCovered(ConductorSize.AWG_8, emptyMap(), 1, 2, 4, 7, 9, 16, 28, 42, 55, 70)

        fun xhhw(size: ConductorSize, differs: Map<TradeSize, Int>, vararg counts: Int) =
            check(ConduitType.EMT, sizes10, "${size.label} XHHW", area(InsulationType.XHHW, size), counts.toList(), differs)
        // 526 mm² / 38.06 mm² = 13.82 gives 14 in the book; in square inches 0.814 / 0.0590 = 13.797, just under 0.8, so 13.
        xhhw(ConductorSize.AWG_6, mapOf(TradeSize.S_1_1_2 to 13), 1, 3, 6, 10, 14, 22, 39, 60, 78, 100)
        xhhw(ConductorSize.AWG_4, emptyMap(), 1, 2, 4, 7, 10, 16, 28, 43, 56, 72)
        xhhw(ConductorSize.AWG_3, emptyMap(), 1, 1, 3, 6, 8, 14, 24, 36, 48, 61)
        xhhw(ConductorSize.AWG_2, emptyMap(), 1, 1, 3, 5, 7, 11, 20, 31, 40, 51)
    }

    @Test fun annexC4ImcThhn() {
        val thhn = InsulationType.THHN
        fun row(size: ConductorSize, vararg counts: Int) =
            check(ConduitType.IMC, sizes12, "${size.label} THHN", area(thhn, size), counts.toList())
        row(ConductorSize.AWG_14, 14, 24, 39, 68, 91, 149, 211, 326, 436, 562, 879, 1268)
        row(ConductorSize.AWG_12, 10, 17, 29, 49, 67, 109, 154, 238, 318, 410, 641, 925)
        row(ConductorSize.AWG_10, 6, 11, 18, 31, 42, 69, 97, 150, 200, 258, 404, 583)
        row(ConductorSize.AWG_8, 3, 6, 10, 18, 24, 39, 56, 86, 115, 149, 233, 336)
        row(ConductorSize.AWG_6, 2, 4, 7, 13, 17, 28, 40, 62, 83, 107, 168, 242)
        row(ConductorSize.AWG_4, 1, 3, 4, 8, 11, 17, 25, 38, 51, 66, 103, 149)
        row(ConductorSize.AWG_3, 1, 2, 4, 6, 9, 15, 21, 32, 43, 56, 87, 126)
        row(ConductorSize.AWG_2, 1, 1, 3, 5, 7, 12, 17, 27, 36, 47, 73, 106)
        row(ConductorSize.AWG_1, 1, 1, 2, 4, 5, 9, 13, 20, 27, 35, 54, 78)
    }

    @Test fun annexC9RmcThhn() {
        val thhn = InsulationType.THHN
        fun row(size: ConductorSize, vararg counts: Int) =
            check(ConduitType.RMC, sizes12, "${size.label} THHN", area(thhn, size), counts.toList())
        row(ConductorSize.AWG_14, 13, 22, 36, 63, 85, 140, 200, 309, 412, 531, 833, 1202)
        row(ConductorSize.AWG_12, 9, 16, 26, 46, 62, 102, 146, 225, 301, 387, 608, 877)
        row(ConductorSize.AWG_10, 6, 10, 17, 29, 39, 64, 92, 142, 189, 244, 383, 552)
        row(ConductorSize.AWG_8, 3, 6, 9, 16, 22, 37, 53, 82, 109, 140, 221, 318)
        row(ConductorSize.AWG_6, 2, 4, 7, 12, 16, 27, 38, 59, 79, 101, 159, 230)
        row(ConductorSize.AWG_4, 1, 2, 4, 7, 10, 16, 23, 36, 48, 62, 98, 141)
        row(ConductorSize.AWG_3, 1, 1, 3, 6, 8, 14, 20, 31, 41, 53, 83, 120)
        row(ConductorSize.AWG_2, 1, 1, 3, 5, 7, 11, 17, 26, 34, 44, 70, 100)
        row(ConductorSize.AWG_1, 1, 1, 1, 4, 5, 8, 12, 19, 25, 33, 51, 74)
    }

    @Test fun oneAndTwoConductorLimitsInCounts() {
        // A conductor that fits a conduit alone always counts at least 1; one that does not is 0.
        val c = emt(TradeSize.S_1_2) // 53% = 0.161 in^2
        assertEquals(1, ConduitFill.maxCount(c, area(InsulationType.THHN, ConductorSize.AWG_1)))   // 0.1562
        // Note 7 permits a single conductor when allowed / area is 0.8 or more: 0.161 / 0.1855 = 0.87, and Annex C prints 1.
        assertEquals(1, ConduitFill.maxCount(c, area(InsulationType.THHN, ConductorSize.AWG_1_0)))
        assertEquals(0, ConduitFill.maxCount(c, area(InsulationType.THHN, ConductorSize.AWG_2_0))) // 0.161 / 0.2223 = 0.72
        // 3/4" EMT: 31% = 0.165, so two 3 AWG (0.0973 each, 0.1946) don't fit, but one does (Annex C prints 1).
        assertEquals(1, ConduitFill.maxCount(emt(TradeSize.S_3_4), area(InsulationType.THHN, ConductorSize.AWG_3)))
    }

    @Test fun nippleCountsUseSixtyPercent() {
        val c = emt(TradeSize.S_1_2) // 60% = 0.182
        val a = area(InsulationType.THHN, ConductorSize.AWG_12) // 0.0133
        assertEquals(13, ConduitFill.maxCount(c, a, nipple = true)) // 0.182 / 0.0133 = 13.68
        assertEquals(9, ConduitFill.maxCount(c, a))
    }

    // ---- analysis ---------------------------------------------------------------------------------------

    private fun groups(vararg g: Pair<Int, ConductorSpec>) = g.map { ConductorGroup(it.first, it.second) }

    @Test fun mixedCircuitAnalysis() {
        // 3 x 12 AWG THHN plus a bare 12 AWG ground in 3/4" EMT.
        val groups = groups(3 to thhn(ConductorSize.AWG_12), 1 to ConductorSpec.Bare(ConductorSize.AWG_12))
        val a = ConduitFill.analyze(emt(TradeSize.S_3_4), groups)
        assertEquals(4, a.conductorCount)
        assertEquals(3 * 0.0133 + 0.006, a.totalAreaSqIn, 1e-12)
        assertEquals(40, a.limitPercent)
        assertEquals(0.213, a.allowedAreaSqIn, 1e-12)
        assertEquals(0.0459 / 0.533 * 100, a.percentFill, 1e-9)
        assertTrue(a.withinLimit)
        assertFalse(a.permittedByNote7)
    }

    @Test fun overTheLimit() {
        // 10 x 12 AWG THHN in 1/2" EMT: 0.133 against 0.122, and 10 > the Annex C count of 9.
        val a = ConduitFill.analyze(emt(TradeSize.S_1_2), groups(10 to thhn(ConductorSize.AWG_12)))
        assertFalse(a.withinLimit)
        assertFalse(a.permittedByNote7)
        assertEquals(0.133 / 0.304 * 100, a.percentFill, 1e-9)
    }

    @Test fun note7LetsASameSizeSetSlightlyOverThroughButNotMixedSizes() {
        val c = emt(TradeSize.S_1_1_4) // 40% = 0.598
        val same = ConduitFill.analyze(c, groups(45 to thhn(ConductorSize.AWG_12)))
        assertEquals(0.5985, same.totalAreaSqIn, 1e-9) // 0.0005 over 0.598
        assertTrue(same.withinLimit)
        assertTrue(same.permittedByNote7)
        assertFalse(ConduitFill.analyze(c, groups(46 to thhn(ConductorSize.AWG_12))).withinLimit)
        // Split into two groups of the same wire is still "all the same size".
        assertTrue(ConduitFill.analyze(c, groups(40 to thhn(ConductorSize.AWG_12), 5 to thhn(ConductorSize.AWG_12))).permittedByNote7)
        // The same total with one different wire gets no rounding: 44 x 12 AWG + 2 x 14 AWG = 0.6046 > 0.598.
        val mixed = ConduitFill.analyze(c, groups(44 to thhn(ConductorSize.AWG_12), 2 to thhn(ConductorSize.AWG_14)))
        assertFalse(mixed.withinLimit)
        // Different insulation with identical area is still the same size (THW and XHHW share 14 AWG).
        val sameArea = groups(23 to wire(InsulationType.THW, ConductorSize.AWG_14), 22 to wire(InsulationType.XHHW, ConductorSize.AWG_14))
        assertEquals(45 * 0.0139, ConduitFill.totalArea(sameArea), 1e-12)
    }

    @Test fun singleAndPairLimits() {
        // One 2/0 AWG THHN (0.2223) in 3/4" EMT: 53% = 0.283 fits; in 1/2" EMT (0.161) it does not.
        val one = groups(1 to thhn(ConductorSize.AWG_2_0))
        assertTrue(ConduitFill.analyze(emt(TradeSize.S_3_4), one).withinLimit)
        assertEquals(53, ConduitFill.analyze(emt(TradeSize.S_3_4), one).limitPercent)
        assertFalse(ConduitFill.analyze(emt(TradeSize.S_1_2), one).withinLimit)
        // One 1/0 AWG THHN (0.1855) is 0.87 of the 1/2" allowance; Note 7 rounds that up to one conductor.
        val oneOhOh = ConduitFill.analyze(emt(TradeSize.S_1_2), groups(1 to thhn(ConductorSize.AWG_1_0)))
        assertTrue(oneOhOh.withinLimit)
        assertTrue(oneOhOh.permittedByNote7)
        // Two 8 AWG THHN (0.0732) in 1/2" EMT use the 31% limit (0.094).
        val two = ConduitFill.analyze(emt(TradeSize.S_1_2), groups(2 to thhn(ConductorSize.AWG_8)))
        assertEquals(31, two.limitPercent)
        assertTrue(two.withinLimit)
        // Two 6 AWG THHN (0.1014) are over 31% of 1/2" EMT (0.094), but 0.094 / 0.0507 = 1.85, which Note 7 rounds up to 2
        // (Annex C prints 2).
        val pairNote7 = ConduitFill.analyze(emt(TradeSize.S_1_2), groups(2 to thhn(ConductorSize.AWG_6)))
        assertTrue(pairNote7.withinLimit)
        assertTrue(pairNote7.permittedByNote7)
        // A 6 AWG THHN with an 8 AWG THW (0.0507 + 0.0437 = 0.0944) is over 31% and, being different sizes, gets no rounding,
        // although the same total would pass at the 40% (0.122) used for three or more.
        val mixedPair = groups(1 to thhn(ConductorSize.AWG_6), 1 to wire(InsulationType.THW, ConductorSize.AWG_8))
        assertFalse(ConduitFill.analyze(emt(TradeSize.S_1_2), mixedPair).withinLimit)
    }

    @Test fun nippleAnalysisAndEmptyInput() {
        val g = groups(13 to thhn(ConductorSize.AWG_12)) // 0.1729
        assertFalse(ConduitFill.analyze(emt(TradeSize.S_1_2), g).withinLimit)
        val nipple = ConduitFill.analyze(emt(TradeSize.S_1_2), g, nipple = true)
        assertTrue(nipple.withinLimit)
        assertEquals(60, nipple.limitPercent)
        // No conductors: zero fill, and no jam or minimum size.
        val none = ConduitFill.analyze(emt(TradeSize.S_1_2), groups(0 to thhn(ConductorSize.AWG_12)))
        assertEquals(0, none.conductorCount)
        assertEquals(0.0, none.percentFill, 0.0)
        assertNull(ConduitFill.minimumConduit(ConduitType.EMT, emptyList()))
    }

    @Test fun bareGroundsCountTowardFill() {
        // Table 1 Note 3 and Note 8: a bare 6 AWG ground uses its Table 8 area (0.027).
        val bare = ConductorSpec.Bare(ConductorSize.AWG_6)
        assertEquals(0.027, bare.dimensions!!.areaSqIn, 1e-12)
        assertEquals("6 AWG bare", bare.label)
        assertEquals(0.027 * 2, ConduitFill.totalArea(groups(2 to bare)), 1e-12)
    }

    // ---- smallest conduit ------------------------------------------------------------------------------

    @Test fun smallestConduit() {
        // 4 x 10 AWG THHN = 0.0844 fits 1/2" EMT (0.122).
        assertEquals(TradeSize.S_1_2, ConduitFill.minimumConduit(ConduitType.EMT, groups(4 to thhn(ConductorSize.AWG_10)))!!.tradeSize)
        // 20 x 12 AWG THHN = 0.266: 3/4" holds 0.213, so 1".
        assertEquals(TradeSize.S_1, ConduitFill.minimumConduit(ConduitType.EMT, groups(20 to thhn(ConductorSize.AWG_12)))!!.tradeSize)
        // The smaller PVC Schedule 80 ID needs a larger size for the same wire: 25 x 12 AWG = 0.3325 fits 1" EMT (0.346)
        // but not 1" Sch 80 (0.275), so it needs 1-1/4".
        assertEquals(TradeSize.S_1, ConduitFill.minimumConduit(ConduitType.EMT, groups(25 to thhn(ConductorSize.AWG_12)))!!.tradeSize)
        assertEquals(TradeSize.S_1_1_4, ConduitFill.minimumConduit(ConduitType.PVC_80, groups(25 to thhn(ConductorSize.AWG_12)))!!.tradeSize)
        // Nipple: 13 x 12 AWG fits 1/2" EMT at 60%.
        assertEquals(TradeSize.S_1_2, ConduitFill.minimumConduit(ConduitType.EMT, groups(13 to thhn(ConductorSize.AWG_12)), nipple = true)!!.tradeSize)
        // Nothing big enough.
        assertNull(ConduitFill.minimumConduit(ConduitType.EMT, groups(50 to thhn(ConductorSize.KCMIL_1000))))
        // The 1-conductor case uses 53%, so one 2/0 AWG THHN needs 3/4" EMT, not 1/2".
        assertEquals(TradeSize.S_3_4, ConduitFill.minimumConduit(ConduitType.EMT, groups(1 to thhn(ConductorSize.AWG_2_0)))!!.tradeSize)
    }

    // ---- jam ratio --------------------------------------------------------------------------------------

    @Test fun jamRatioForThreeConductors() {
        val c = emt(TradeSize.S_1_1_4) // ID 1.380
        // 1 AWG THHN is 0.446 in. across: 1.380 / 0.446 = 3.094, inside 2.8 to 3.2.
        val risky = ConduitFill.jamCheck(c, groups(3 to thhn(ConductorSize.AWG_1)))!!
        assertEquals(1.380 / 0.446, risky.ratio, 1e-9)
        assertTrue(risky.inJamRange)
        assertFalse(risky.mixedSizes)
        // 2 AWG THHN (0.384): 3.59, fine.
        assertFalse(ConduitFill.jamCheck(c, groups(3 to thhn(ConductorSize.AWG_2)))!!.inJamRange)
        // Three separate groups of one still count three; the largest governs a mixed set.
        val mixed = ConduitFill.jamCheck(c, groups(2 to thhn(ConductorSize.AWG_1), 1 to thhn(ConductorSize.AWG_6)))!!
        assertTrue(mixed.mixedSizes)
        assertEquals(1.380 / 0.446, mixed.ratio, 1e-9)
        // Only exactly three conductors are checked.
        assertNull(ConduitFill.jamCheck(c, groups(2 to thhn(ConductorSize.AWG_1))))
        assertNull(ConduitFill.jamCheck(c, groups(4 to thhn(ConductorSize.AWG_1))))
    }

    @Test fun unlistedSpecsAreNotSilentlyCounted() {
        // THHN has no 16 AWG row: the spec reports no dimensions rather than a made-up area.
        assertNull(thhn(ConductorSize.AWG_16).dimensions)
        assertNotNull(thhn(ConductorSize.AWG_14).dimensions)
    }
}
