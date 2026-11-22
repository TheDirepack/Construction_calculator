package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values from the printed NEC 2023, pages 70-95 to 70-98. */
class Article220Test {

    @Test fun citationsUseThe2023Numbering() {
        assertEquals("Table 220.45", CodeRule.LIGHTING_DEMAND.cite)
        assertEquals("Table 220.47", CodeRule.RECEPTACLE_DEMAND.cite)
        assertEquals("Table 220.54", CodeRule.DRYER_DEMAND.cite)
        assertEquals("Table 220.55", CodeRule.RANGE_DEMAND.cite)
        assertEquals("220.82(C)", CodeRule.OPTIONAL_DWELLING_HVAC.cite)
    }

    @Test fun lightingDemandTable220_45() {
        // Dwelling: 3,000 @ 100% + 7,000 @ 35% = 5,450 VA
        assertEquals(5_450.0, Article220.lightingDemandVa(10_000.0, LightingOccupancy.DWELLING), 1e-6)
        // 3,000 + 117,000 × 0.35 + 10,000 × 0.25 = 46,450 VA
        assertEquals(46_450.0, Article220.lightingDemandVa(130_000.0, LightingOccupancy.DWELLING), 1e-6)
        // Hotel/motel: 20,000 @ 60% + 80,000 @ 50% + 10,000 @ 35% = 12,000 + 40,000 + 3,500
        assertEquals(55_500.0, Article220.lightingDemandVa(110_000.0, LightingOccupancy.HOTEL_MOTEL), 1e-6)
        // Warehouse: 12,500 @ 100% + 10,000 @ 50%
        assertEquals(17_500.0, Article220.lightingDemandVa(22_500.0, LightingOccupancy.WAREHOUSE), 1e-6)
        assertEquals(15_000.0, Article220.lightingDemandVa(15_000.0, LightingOccupancy.OTHER), 1e-6)
    }

    @Test fun receptacleDemandTable220_47() {
        assertEquals(8_000.0, Article220.receptacleDemandVa(8_000.0), 1e-6)
        assertEquals(17_500.0, Article220.receptacleDemandVa(25_000.0), 1e-6)
    }

    @Test fun dryerDemandTable220_54() {
        assertEquals(5_000.0, Article220.dryerDemandVa(1, 4_500.0), 1e-6) // 5,000 VA minimum
        assertEquals(20_000.0, Article220.dryerDemandVa(4, 5_000.0), 1e-6)
        assertEquals(21_250.0, Article220.dryerDemandVa(5, 5_000.0), 1e-6) // 85%
        assertEquals(25_000.0, Article220.dryerDemandVa(10, 5_000.0), 1e-6) // 50%
        assertEquals(0.43, Article220.dryerDemandFactor(15), 1e-9) // 47% − 4 × 1%
        assertEquals(0.35, Article220.dryerDemandFactor(23), 1e-9) // 47% − 12 × 1%
        assertEquals(0.345, Article220.dryerDemandFactor(24), 1e-9) // 35% − 1 × 0.5%
        assertEquals(0.255, Article220.dryerDemandFactor(42), 1e-9) // 35% − 19 × 0.5%
        assertEquals(0.25, Article220.dryerDemandFactor(60), 1e-9)
    }

    @Test fun rangeDemandTable220_55() {
        assertEquals(8_000.0, Article220.rangeDemandVa(1, 12.0)!!, 1e-6) // Column C
        assertEquals(14_000.0, Article220.rangeDemandVa(3, 12.0)!!, 1e-6)
        assertEquals(27_000.0, Article220.rangeDemandVa(12, 12.0)!!, 1e-6)
        assertEquals(45_000.0, Article220.rangeDemandVa(30, 12.0)!!, 1e-6) // 15 kW + 1 kW × 30
        assertEquals(62_500.0, Article220.rangeDemandVa(50, 12.0)!!, 1e-6) // 25 kW + 0.75 kW × 50
        // Note 1: 16 kW is 4 kW over 12 kW → +20%
        assertEquals(9_600.0, Article220.rangeDemandVa(1, 16.0)!!, 1e-6)
        // Exactly half a kW over is not a "major fraction"; 12.6 kW is.
        assertEquals(8_000.0, Article220.rangeDemandVa(1, 12.5)!!, 1e-6)
        assertEquals(8_400.0, Article220.rangeDemandVa(1, 12.6)!!, 1e-6)
        // Note 3: one 6 kW cooktop, Column B 80% → 4.8 kW; two 3 kW ovens, Column A 75% → 4.5 kW
        assertEquals(4_800.0, Article220.rangeDemandVa(1, 6.0)!!, 1e-6)
        assertEquals(4_500.0, Article220.rangeDemandVa(2, 3.0)!!, 1e-6)
        // Outside the table
        assertNull(Article220.rangeDemandVa(1, 30.0))
        assertNull(Article220.rangeDemandVa(1, 1.5))
    }

    @Test fun standardDwellingCalculation() {
        val result = Article220.standardCalculation(
            StandardLoadInput(
                occupancy = LightingOccupancy.DWELLING,
                generalLightingVa = 6_000.0, // 2,000 ft² × 3 VA
                smallApplianceCircuits = 2, // 3,000 VA
                laundryCircuits = 1, // 1,500 VA → pool 10,500: 3,000 + 7,500 × 0.35 = 5,625
                fixedApplianceVa = 4_800.0,
                fixedApplianceCount = 4, // 75% → 3,600
                dryerCount = 1, // 5,000
                rangeCount = 1, // 8,000
                heatingVa = 10_000.0,
                coolingVa = 4_800.0, // heating governs: 10,000
                largestMotorVa = 1_200.0, // 25% → 300
                lineVoltage = 240,
                phases = AcPhases.SINGLE,
            ),
        )
        assertEquals(32_525.0, result.demandVa, 1e-6)
        assertEquals(32_525.0 / 240.0, result.demandAmps, 1e-6)
        assertEquals(150, result.recommendedOcpdAmps)
        assertTrue(result.breakdown.any { CodeRule.LIGHTING_DEMAND.cite in it.rule })
    }

    @Test fun receptaclesOnlyCountOutsideDwellings() {
        val dwelling = Article220.standardCalculation(StandardLoadInput(receptacleVa = 20_000.0))
        assertEquals(0.0, dwelling.demandVa, 1e-6)
        val office = Article220.standardCalculation(
            StandardLoadInput(occupancy = LightingOccupancy.OTHER, receptacleVa = 20_000.0, lineVoltage = 208, phases = AcPhases.THREE),
        )
        assertEquals(15_000.0, office.demandVa, 1e-6)
    }

    @Test fun optionalDwellingCalculation220_82() {
        val result = Article220.optionalDwellingCalculation(
            OptionalDwellingInput(
                floorAreaSqFt = 2_000.0, // 6,000
                smallApplianceCircuits = 2, // 3,000
                laundryCircuits = 1, // 1,500
                appliancesVa = 21_500.0, // general 32,000 → 10,000 + 22,000 × 0.40 = 18,800
                coolingVa = 5_000.0, // 100% → 5,000
                centralHeatingVa = 10_000.0, // 65% → 6,500 (governs)
                lineVoltage = 240,
            ),
        )
        assertEquals(25_300.0, result.demandVa, 1e-6)
        assertEquals(110, result.recommendedOcpdAmps)
    }

    @Test fun optionalDwellingSpaceHeatingUnits() {
        val four = Article220.optionalDwellingCalculation(
            OptionalDwellingInput(smallApplianceCircuits = 0, laundryCircuits = 0, spaceHeatingUnitsVa = 20_000.0, spaceHeatingUnitCount = 4),
        )
        assertEquals(8_000.0, four.demandVa, 1e-6) // 40% for four or more
        val three = Article220.optionalDwellingCalculation(
            OptionalDwellingInput(smallApplianceCircuits = 0, laundryCircuits = 0, spaceHeatingUnitsVa = 20_000.0, spaceHeatingUnitCount = 3),
        )
        assertEquals(13_000.0, three.demandVa, 1e-6) // 65% for fewer than four
    }
}
