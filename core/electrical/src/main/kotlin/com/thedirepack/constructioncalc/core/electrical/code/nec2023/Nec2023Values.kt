package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.code.CodeValues

/**
 * NEC 2023 single values. Each is the number its rule states in the printed 2023 edition; the
 * comment gives the section. [CodeValues] documents what each one means.
 */
internal object Nec2023Values {
    val values = CodeValues(
        ambientBasisC = 30.0, // Table 310.16 heading and Table 310.15(B)(1)(1), 70-185/70-187
        rooftopAdderC = 33.0, // 310.15(B)(2): 33 °C (60 °F)
        rooftopAdderF = 60.0,
        continuousLoadFactor = 1.25, // 210.19(A)(1), 210.20(A), 215.2(A)(1), 215.3: 125 percent
        nextSizeUpMaxAmps = 800, // 240.4(B)
        minimumSizeCopper = ConductorSize.AWG_14, // 310.3(A), 70-177
        minimumSizeAluminum = ConductorSize.AWG_12, // 310.3(A): aluminum or copper-clad aluminum
        parallelMinimumSize = ConductorSize.AWG_1_0, // 310.10(G)
        terminationLowColumnMaxAmps = 100, // 110.14(C)(1)(a)
        terminationLowColumnMaxSize = ConductorSize.AWG_1, // 110.14(C)(1)(a): 14 AWG through 1 AWG

        fillOneConductorPercent = 53, // Chapter 9 Table 1, 70-715
        fillTwoConductorsPercent = 31,
        fillOverTwoConductorsPercent = 40,
        fillNipplePercent = 60, // Chapter 9 Note (4)
        nippleMaxLengthIn = 24.0, // Chapter 9 Note (4), 310.15(C)(1)(b): 600 mm (24 in.)
        nippleMaxLengthMm = 600.0,
        note7RoundUpFraction = 0.8, // Chapter 9 Note (7)
        jamRatioLow = 2.8, // Chapter 9 Table 1 Informational Note No. 2
        jamRatioHigh = 3.2,
        // 342.24(B), 344.24(B), 348.24(B), 350.24(B), 352.24(B), 353.24(B), 356.24(B), 358.24(B), 362.24(B)
        maxBendDegreesBetweenPullPoints = 360.0,

        resistanceReferenceC = 75.0, // Chapter 9 Table 8 heading, 70-725
        resistanceCoefficientCopper = 0.00323, // Chapter 9 Table 8 Note 2
        resistanceCoefficientAluminum = 0.00330,
        acImpedanceTablePowerFactor = 0.85, // Chapter 9 Table 9 "Effective Z at 0.85 PF" columns, 70-726

        voltageDropBranchPercent = 3.0, // 210.19 Informational Note, 70-83
        voltageDropFeederPercent = 3.0, // 215.2(A)(2) Informational Note No. 2, 70-90
        voltageDropTotalPercent = 5.0, // 210.19 and 215.2(A)(2) Informational Notes

        motorConductorFactor = 1.25, // 430.22
        motorDisconnectFactor = 1.15, // 430.110(A)
        motorOverloadFactor = 1.25, // 430.32(A)(1): service factor 1.15 or more, or rise 40 °C or less
        motorOverloadFactorOther = 1.15, // 430.32(A)(1): all other motors
        motorOverloadServiceFactor = 1.15, // 430.32(A)(1)
        motorOverloadMaxRiseC = 40.0, // 430.32(A)(1)
        driveInputConductorFactor = 1.25, // 430.122(A), 70-359
        driveOutputConductorFactor = 1.25, // 430.122(B)
        driveDisconnectFactor = 1.15, // 430.128, 70-360

        workingSpaceMinWidthIn = 30, // 110.26(A)(2): 762 mm (30 in.), 70-68
        workingSpaceMinWidthMm = 762,
        workingSpaceMinHeightIn = 78, // 110.26(A)(3): 2.0 m (6-1/2 ft)
        workingSpaceMinHeightMm = 2000,

        pullBoxStraightMultiplier = 8, // 314.28(A)(1): not less than 8 times the trade size of the largest raceway
        pullBoxAngleMultiplier = 6, // 314.28(A)(2): not less than 6 times the trade size of the largest raceway
        bondingJumperLargeAreaFraction = 0.125, // Table 250.102(C)(1) Note 1: 12-1/2 percent of the area
        pvcExpansionFittingThresholdIn = 0.25, // 352.44: 6 mm (1/4 in.) or greater
        pvcExpansionInPer100FtPer100F = 4.06, // Table 352.44: 4.06 in. per 100 ft per 100 °F
        pvcExpansionFittingThresholdMm = 6, // 352.44: 6 mm (1/4 in.)
        boxFillLoopLengthIn = 12, // 314.16(B)(1): 300 mm (12 in.) or more
        boxFillLoopLengthMm = 300,
        boxFillEgcCountedAsOne = 4, // 314.16(B)(5): up to four EGCs, a single allowance
        boxFillExtraEgcFraction = 0.25, // 314.16(B)(5): 1/4 allowance for each additional EGC
        boxFillDeviceYokeAllowances = 2, // 314.16(B)(4): double allowance per yoke or strap
        gecRodMaxCopper = ConductorSize.AWG_6, // 250.66(A): 6 AWG copper or 4 AWG aluminum
        gecRodMaxAluminum = ConductorSize.AWG_4,
        gecConcreteEncasedMaxCopper = ConductorSize.AWG_4, // 250.66(B): 4 AWG copper
    )
}
