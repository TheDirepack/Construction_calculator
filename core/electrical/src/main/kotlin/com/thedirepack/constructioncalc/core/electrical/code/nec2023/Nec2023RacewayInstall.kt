package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.TradeSize.*
import com.thedirepack.constructioncalc.core.electrical.code.BurialDepthRow
import com.thedirepack.constructioncalc.core.electrical.code.PvcExpansionRow
import com.thedirepack.constructioncalc.core.electrical.code.RacewaySupportRule

/**
 * NEC 2023 raceway installation tables: Table 300.5 (minimum burial cover), raceway securing
 * and supporting intervals (Articles 342–362), and Table 352.44 (PVC thermal expansion). Data only.
 */
internal object Nec2023RacewayInstall {

    /**
     * **Table 300.5(A)** "Minimum Cover Requirements, 0 to 1000 Volts ac, 1500 Volts dc, Nominal,
     * Burial in Millimeters (Inches)" (NEC 2023, p. 70-169). Columns:
     * 0. Direct burial cables or conductors (UF, USE)
     * 1. Rigid metal conduit (RMC) or intermediate metal conduit (IMC)
     * 2. EMT, nonmetallic raceways listed for direct burial without concrete encasement, or other
     *    approved raceways
     * 3. Residential branch circuits rated 120 V or less with GFCI protection and max 20 A OCPD
     * 4. Circuits for control of irrigation and landscape lighting limited to 30 V or less
     */
    val burialDepths: List<BurialDepthRow> = listOf(
        BurialDepthRow(
            location = "All locations not specified below",
            coverInches = listOf(24, 6, 18, 12, 6),
        ),
        BurialDepthRow(
            location = "In trench below 2-in. (50 mm) thick concrete or equivalent",
            coverInches = listOf(18, 6, 12, 6, 6),
        ),
        BurialDepthRow(
            location = "Under a building",
            coverInches = listOf(0, 0, 0, 0, 0),
            note = "Columns 1, 4 and 5: in a raceway, or Type MC or MI cable identified for direct burial.",
        ),
        BurialDepthRow(
            location = "Under min. 4-in. (100 mm) concrete exterior slab, no vehicular traffic",
            coverInches = listOf(18, 4, 4, 6, 6),
            note = "Slab must extend at least 6 in. beyond the installation. Columns 4 and 5: 6 in. direct burial, 4 in. in a raceway.",
        ),
        BurialDepthRow(
            location = "Under streets, highways, roads, alleys, driveways, and parking lots",
            coverInches = listOf(24, 24, 24, 24, 24),
        ),
        BurialDepthRow(
            location = "One- and two-family dwelling driveways and outdoor parking areas (dwelling use only)",
            coverInches = listOf(18, 18, 18, 12, 18),
        ),
        BurialDepthRow(
            location = "In or under airport runways, including adjacent no-trespassing areas",
            coverInches = listOf(18, 18, 18, 18, 18),
        ),
    )

    private val pvcIntervals: Map<TradeSize, Double> = mapOf(
        S_1_2 to 3.0,
        S_3_4 to 3.0,
        S_1 to 3.0,
        S_1_1_4 to 5.0,
        S_1_1_2 to 5.0,
        S_2 to 5.0,
        S_2_1_2 to 6.0,
        S_3 to 6.0,
        S_3_1_2 to 7.0,
        S_4 to 7.0,
        S_5 to 7.0,
        S_6 to 8.0,
    )

    private val threadedMetalIntervals: Map<TradeSize, Double> = mapOf(
        S_1_2 to 10.0,
        S_3_4 to 10.0,
        S_1 to 12.0,
        S_1_1_4 to 14.0,
        S_1_1_2 to 14.0,
        S_2 to 16.0,
        S_2_1_2 to 16.0,
        S_3 to 20.0,
        S_3_1_2 to 20.0,
        S_4 to 20.0,
        S_5 to 20.0,
        S_6 to 20.0,
    )

    /** Securing and supporting rules per raceway type (NEC 2023). */
    val racewaySupport: Map<ConduitType, RacewaySupportRule> = mapOf(
        ConduitType.EMT to RacewaySupportRule(
            section = "358.30(A)",
            withinBoxFt = 3.0,
            defaultIntervalFt = 10.0,
            note = "May be increased to 5 ft from box where structural members don't permit 3 ft.",
        ),
        ConduitType.IMC to RacewaySupportRule(
            section = "342.30(B)",
            withinBoxFt = 3.0,
            defaultIntervalFt = 10.0,
            byTradeSizeFt = threadedMetalIntervals,
            note = "10 ft general rule; extended intervals in Table 344.30(B)(2) apply only to straight runs with threaded couplings.",
        ),
        ConduitType.RMC to RacewaySupportRule(
            section = "344.30(B)",
            withinBoxFt = 3.0,
            defaultIntervalFt = 10.0,
            byTradeSizeFt = threadedMetalIntervals,
            note = "10 ft general rule; extended intervals in Table 344.30(B)(2) apply only to straight runs with threaded couplings.",
        ),
        ConduitType.PVC_40 to RacewaySupportRule(
            section = "Table 352.30",
            withinBoxFt = 3.0,
            defaultIntervalFt = 3.0,
            byTradeSizeFt = pvcIntervals,
            note = "Support spacing increases with trade size per Table 352.30.",
        ),
        ConduitType.PVC_80 to RacewaySupportRule(
            section = "Table 352.30",
            withinBoxFt = 3.0,
            defaultIntervalFt = 3.0,
            byTradeSizeFt = pvcIntervals,
            note = "Support spacing increases with trade size per Table 352.30.",
        ),
        ConduitType.FMC to RacewaySupportRule(
            section = "348.30(A)",
            withinBoxFt = 1.0,
            defaultIntervalFt = 4.5,
            note = "Within 12 in. (300 mm) of each box and at intervals not exceeding 4-1/2 ft.",
        ),
        ConduitType.LFMC to RacewaySupportRule(
            section = "350.30(A)",
            withinBoxFt = 1.0,
            defaultIntervalFt = 4.5,
            note = "Within 12 in. (300 mm) of each box and at intervals not exceeding 4-1/2 ft.",
        ),
        ConduitType.ENT to RacewaySupportRule(
            section = "362.30(A)",
            withinBoxFt = 3.0,
            defaultIntervalFt = 3.0,
            note = "Within 3 ft of each box and at intervals not exceeding 3 ft.",
        ),
    )

    /**
     * **Table 352.44** "Expansion Characteristics of PVC Rigid Nonmetallic Conduit" (NEC 2023):
     * temperature change (°F) → length change in inches per 100 ft of PVC conduit.
     */
    val pvcThermalExpansion: List<PvcExpansionRow> = listOf(
        PvcExpansionRow(5, 0.20),
        PvcExpansionRow(10, 0.41),
        PvcExpansionRow(15, 0.61),
        PvcExpansionRow(20, 0.81),
        PvcExpansionRow(25, 1.01),
        PvcExpansionRow(30, 1.22),
        PvcExpansionRow(35, 1.42),
        PvcExpansionRow(40, 1.62),
        PvcExpansionRow(45, 1.83),
        PvcExpansionRow(50, 2.03),
        PvcExpansionRow(55, 2.23),
        PvcExpansionRow(60, 2.44),
        PvcExpansionRow(65, 2.64),
        PvcExpansionRow(70, 2.84),
        PvcExpansionRow(75, 3.05),
        PvcExpansionRow(80, 3.25),
        PvcExpansionRow(85, 3.45),
        PvcExpansionRow(90, 3.65),
        PvcExpansionRow(95, 3.86),
        PvcExpansionRow(100, 4.06),
        PvcExpansionRow(110, 4.47),
        PvcExpansionRow(120, 4.87),
        PvcExpansionRow(130, 5.28),
        PvcExpansionRow(140, 5.68),
        PvcExpansionRow(150, 6.09),
        PvcExpansionRow(160, 6.50),
        PvcExpansionRow(170, 6.90),
        PvcExpansionRow(180, 7.31),
        PvcExpansionRow(190, 7.71),
        PvcExpansionRow(200, 8.12),
    )
}
