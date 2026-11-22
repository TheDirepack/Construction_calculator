package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.EgcRow
import com.thedirepack.constructioncalc.core.electrical.code.GecRow
import com.thedirepack.constructioncalc.core.electrical.code.SmallConductorLimitRow

/** NEC 2023 overcurrent protection (Article 240) and equipment grounding conductors (250.122). Data only. */
internal object Nec2023Protection {

    /** **Table 240.6(A)** "Standard Ampere Ratings for Fuses and Inverse Time Circuit Breakers", 10 A to 6000 A (70-124). */
    val standardOcpdRatings: List<Int> = listOf(
        10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100, 110, 125, 150, 175, 200, 225, 250,
        300, 350, 400, 450, 500, 600, 700, 800, 1000, 1200, 1600, 2000, 2500, 3000, 4000, 5000, 6000,
    )

    /** **240.6(A)**: 1, 3, 6 and 601 A are also standard ratings for fuses. */
    val fuseOnlyRatings: List<Int> = listOf(1, 3, 6, 601)

    /**
     * **240.4(D)** "Small Conductors": maximum overcurrent protection after correction and adjustment,
     * unless 240.4(E) or (G) permits otherwise. 18 and 16 AWG copper and 14 AWG copper-clad aluminum
     * carry extra conditions: a continuous-load limit and devices listed for the size.
     */
    val smallConductorLimits: Map<ConductorMaterial, Map<ConductorSize, SmallConductorLimitRow>> = run {
        val aluminum = mapOf(
            AWG_12 to SmallConductorLimitRow(15),
            AWG_10 to SmallConductorLimitRow(25),
        )
        mapOf(
            ConductorMaterial.COPPER to mapOf(
                AWG_18 to SmallConductorLimitRow(7, maxContinuousAmps = 5.6, conditionsRule = CodeRule.SMALL_CONDUCTOR_18_CU),
                AWG_16 to SmallConductorLimitRow(10, maxContinuousAmps = 8.0, conditionsRule = CodeRule.SMALL_CONDUCTOR_16_CU),
                AWG_14 to SmallConductorLimitRow(15),
                AWG_12 to SmallConductorLimitRow(20),
                AWG_10 to SmallConductorLimitRow(30),
            ),
            ConductorMaterial.ALUMINUM to aluminum,
            ConductorMaterial.COPPER_CLAD_ALUMINUM to mapOf(
                AWG_14 to SmallConductorLimitRow(10, maxContinuousAmps = 8.0, conditionsRule = CodeRule.SMALL_CONDUCTOR_14_CCA),
            ) + aluminum,
        )
    }

    /**
     * **Table 250.122** "Minimum Size Equipment Grounding Conductors for Grounding Raceway and Equipment"
     * (70-158), by the rating of the overcurrent device ahead of the circuit: rating or setting not
     * exceeding, copper, aluminum or copper-clad aluminum.
     */
    val equipmentGroundingConductors: List<EgcRow> = listOf(
        EgcRow(15, AWG_14, AWG_12),
        EgcRow(20, AWG_12, AWG_10),
        EgcRow(60, AWG_10, AWG_8),
        EgcRow(100, AWG_8, AWG_6),
        EgcRow(200, AWG_6, AWG_4),
        EgcRow(300, AWG_4, AWG_2),
        EgcRow(400, AWG_3, AWG_1),
        EgcRow(500, AWG_2, AWG_1_0),
        EgcRow(600, AWG_1, AWG_2_0),
        EgcRow(800, AWG_1_0, AWG_3_0),
        EgcRow(1000, AWG_2_0, AWG_4_0),
        EgcRow(1200, AWG_3_0, KCMIL_250),
        EgcRow(1600, AWG_4_0, KCMIL_350),
        EgcRow(2000, KCMIL_250, KCMIL_400),
        EgcRow(2500, KCMIL_350, KCMIL_600),
        EgcRow(3000, KCMIL_400, KCMIL_600),
        EgcRow(4000, KCMIL_500, KCMIL_750),
        EgcRow(5000, KCMIL_700, KCMIL_1250),
        EgcRow(6000, KCMIL_800, KCMIL_1250),
    )

    /**
     * **Table 250.66** "Grounding Electrode Conductor for Alternating-Current Systems" and
     * **Table 250.102(C)(1)** "Grounded Conductor, Main Bonding Jumper, System Bonding Jumper,
     * and Supply-Side Bonding Jumper for Alternating-Current Systems" (NEC 2023).
     * Sizes are keyed by circular mils of the largest ungrounded service-entrance conductor
     * (or equivalent area for parallel conductors).
     */
    val groundingElectrodeConductors: List<GecRow> = listOf(
        GecRow("2 AWG or smaller", "1/0 AWG or smaller", AWG_2.circularMils.toLong(), AWG_1_0.circularMils.toLong(), AWG_8, AWG_6),
        GecRow("1 or 1/0 AWG", "2/0 or 3/0 AWG", AWG_1_0.circularMils.toLong(), AWG_3_0.circularMils.toLong(), AWG_6, AWG_4),
        GecRow("2/0 or 3/0 AWG", "4/0 AWG or 250 kcmil", AWG_3_0.circularMils.toLong(), KCMIL_250.circularMils.toLong(), AWG_4, AWG_2),
        GecRow("Over 3/0 through 350 kcmil", "Over 250 through 500 kcmil", KCMIL_350.circularMils.toLong(), KCMIL_500.circularMils.toLong(), AWG_2, AWG_1_0),
        GecRow("Over 350 through 600 kcmil", "Over 500 through 900 kcmil", KCMIL_600.circularMils.toLong(), KCMIL_900.circularMils.toLong(), AWG_1_0, AWG_3_0),
        GecRow("Over 600 through 1100 kcmil", "Over 900 through 1750 kcmil", 1_100_000L, KCMIL_1750.circularMils.toLong(), AWG_2_0, AWG_4_0),
        GecRow("Over 1100 kcmil", "Over 1750 kcmil", null, null, AWG_3_0, KCMIL_250),
    )
}
