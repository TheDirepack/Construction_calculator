package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.LightingOccupancy
import com.thedirepack.constructioncalc.core.electrical.code.DemandTier
import com.thedirepack.constructioncalc.core.electrical.code.DryerDemandRow
import com.thedirepack.constructioncalc.core.electrical.code.LoadCalculationCode
import com.thedirepack.constructioncalc.core.electrical.code.RangeDemandRow

/**
 * NEC 2023 Article 220 feeder and service load values, checked against printed pages 70-95 to
 * 70-98: Table 220.45, Table 220.47, 220.52, 220.53, Table 220.54, Table 220.55 and its notes,
 * 220.50(A) (430.24), 220.82.
 */
internal object Nec2023LoadCalculations {
    val values = LoadCalculationCode(
        // Table 220.45, 70-95. The 2023 table has no hospital row; "All others" is 100 %.
        lightingDemand = mapOf(
            LightingOccupancy.DWELLING to listOf(
                DemandTier(3_000.0, 1.00),
                DemandTier(120_000.0, 0.35),
                DemandTier(null, 0.25),
            ),
            LightingOccupancy.HOTEL_MOTEL to listOf(
                DemandTier(20_000.0, 0.60),
                DemandTier(100_000.0, 0.50),
                DemandTier(null, 0.35),
            ),
            LightingOccupancy.WAREHOUSE to listOf(
                DemandTier(12_500.0, 1.00),
                DemandTier(null, 0.50),
            ),
        ),
        // Table 220.47, 70-95
        receptacleDemand = listOf(DemandTier(10_000.0, 1.00), DemandTier(null, 0.50)),
        smallApplianceCircuitVa = 1_500.0, // 220.52(A), 70-96
        laundryCircuitVa = 1_500.0, // 220.52(B)
        dwellingApplianceMinCount = 4, // 220.53
        dwellingApplianceFactor = 0.75,
        dryerMinVa = 5_000.0, // 220.54
        // Table 220.54, 70-96
        dryerDemand = listOf(
            DryerDemandRow(1, 4, 100.0),
            DryerDemandRow(5, 5, 85.0),
            DryerDemandRow(6, 6, 75.0),
            DryerDemandRow(7, 7, 65.0),
            DryerDemandRow(8, 8, 60.0),
            DryerDemandRow(9, 9, 55.0),
            DryerDemandRow(10, 10, 50.0),
            DryerDemandRow(11, 11, 47.0),
            DryerDemandRow(12, 23, 47.0, minusPercentPerDryer = 1.0),
            DryerDemandRow(24, 42, 35.0, minusPercentPerDryer = 0.5),
            DryerDemandRow(43, null, 25.0),
        ),
        // Table 220.55, 70-97: Column A (%), Column B (%), Column C (kW)
        rangeDemand = listOf(
            RangeDemandRow(1, 1, 80.0, 80.0, 8.0),
            RangeDemandRow(2, 2, 75.0, 65.0, 11.0),
            RangeDemandRow(3, 3, 70.0, 55.0, 14.0),
            RangeDemandRow(4, 4, 66.0, 50.0, 17.0),
            RangeDemandRow(5, 5, 62.0, 45.0, 20.0),
            RangeDemandRow(6, 6, 59.0, 43.0, 21.0),
            RangeDemandRow(7, 7, 56.0, 40.0, 22.0),
            RangeDemandRow(8, 8, 53.0, 36.0, 23.0),
            RangeDemandRow(9, 9, 51.0, 35.0, 24.0),
            RangeDemandRow(10, 10, 49.0, 34.0, 25.0),
            RangeDemandRow(11, 11, 47.0, 32.0, 26.0),
            RangeDemandRow(12, 12, 45.0, 32.0, 27.0),
            RangeDemandRow(13, 13, 43.0, 32.0, 28.0),
            RangeDemandRow(14, 14, 41.0, 32.0, 29.0),
            RangeDemandRow(15, 15, 40.0, 32.0, 30.0),
            RangeDemandRow(16, 16, 39.0, 28.0, 31.0),
            RangeDemandRow(17, 17, 38.0, 28.0, 32.0),
            RangeDemandRow(18, 18, 37.0, 28.0, 33.0),
            RangeDemandRow(19, 19, 36.0, 28.0, 34.0),
            RangeDemandRow(20, 20, 35.0, 28.0, 35.0),
            RangeDemandRow(21, 21, 34.0, 26.0, 36.0),
            RangeDemandRow(22, 22, 33.0, 26.0, 37.0),
            RangeDemandRow(23, 23, 32.0, 26.0, 38.0),
            RangeDemandRow(24, 24, 31.0, 26.0, 39.0),
            RangeDemandRow(25, 25, 30.0, 26.0, 40.0),
            RangeDemandRow(26, 30, 30.0, 24.0, 15.0, columnCKwPerRange = 1.0),
            RangeDemandRow(31, 40, 30.0, 22.0, 15.0, columnCKwPerRange = 1.0),
            RangeDemandRow(41, 50, 30.0, 20.0, 25.0, columnCKwPerRange = 0.75),
            RangeDemandRow(51, 60, 30.0, 18.0, 25.0, columnCKwPerRange = 0.75),
            RangeDemandRow(61, null, 30.0, 16.0, 25.0, columnCKwPerRange = 0.75),
        ),
        rangeMinKw = 1.75, // 220.55: "in excess of 1-3/4 kW"
        rangeColumnAMaxKw = 3.5, // Column A: less than 3-1/2 kW
        rangeColumnBMaxKw = 8.75, // Column B: 3-1/2 kW through 8-3/4 kW; Note 3
        rangeColumnCMaxKw = 12.0, // Column C: not over 12 kW
        rangeNote1MaxKw = 27.0, // Note 1: over 12 kW through 27 kW
        rangeNote1IncreasePerKw = 0.05, // Note 1: 5 percent
        largestMotorAdder = 0.25, // 220.50(A) → 430.24: 125 % of the largest motor

        optionalMinAmpacity = 100, // 220.82(A), 70-98
        optionalVaPerSqFt = 3.0, // 220.82(B)(1)
        optionalFirstVa = 10_000.0, // 220.82(B)
        optionalRemainderFactor = 0.40,
        optionalCoolingFactor = 1.00, // 220.82(C)(1)
        optionalCentralHeatFactor = 0.65, // 220.82(C)(3)
        optionalSpaceHeatFewFactor = 0.65, // 220.82(C)(4)
        optionalSpaceHeatManyFactor = 0.40, // 220.82(C)(5)
        optionalSpaceHeatManyCount = 4,
    )
}
