package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.ConductorDimensions
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*
import com.thedirepack.constructioncalc.core.electrical.ImpedanceRow
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.code.AmbientBand
import com.thedirepack.constructioncalc.core.electrical.code.AmpacityRow
import com.thedirepack.constructioncalc.core.electrical.code.CountAdjustmentBand
import com.thedirepack.constructioncalc.core.electrical.code.ResistanceRow

/** NEC 2023 conductor tables: Article 310 and Chapter 9 Tables 5, 8 and 9. Data only. */
internal object Nec2023Conductors {

    private fun r(a60: Int?, a75: Int?, a90: Int?) = AmpacityRow(a60, a75, a90)

    /**
     * **Table 310.16** "Ampacities of Insulated Conductors with Not More Than Three Current-Carrying
     * Conductors in Raceway, Cable, or Earth (Directly Buried)", based on an ambient of 30 °C (86 °F)
     * (verified against the printed table, 70-187). Copper, and aluminum or copper-clad aluminum;
     * 18 AWG to 2000 kcmil. Blank cells (copper 18/16 AWG at 60 and 75 °C) are null; copper 14 AWG and
     * smaller aluminum sizes have no row.
     */
    private val ampacityCopper: Map<ConductorSize, AmpacityRow> = mapOf(
        AWG_18 to r(null, null, 14),
        AWG_16 to r(null, null, 18),
        AWG_14 to r(15, 20, 25),
        AWG_12 to r(20, 25, 30),
        AWG_10 to r(30, 35, 40),
        AWG_8 to r(40, 50, 55),
        AWG_6 to r(55, 65, 75),
        AWG_4 to r(70, 85, 95),
        AWG_3 to r(85, 100, 115),
        AWG_2 to r(95, 115, 130),
        AWG_1 to r(110, 130, 145),
        AWG_1_0 to r(125, 150, 170),
        AWG_2_0 to r(145, 175, 195),
        AWG_3_0 to r(165, 200, 225),
        AWG_4_0 to r(195, 230, 260),
        KCMIL_250 to r(215, 255, 290),
        KCMIL_300 to r(240, 285, 320),
        KCMIL_350 to r(260, 310, 350),
        KCMIL_400 to r(280, 335, 380),
        KCMIL_500 to r(320, 380, 430),
        KCMIL_600 to r(350, 420, 475),
        KCMIL_700 to r(385, 460, 520),
        KCMIL_750 to r(400, 475, 535),
        KCMIL_800 to r(410, 490, 555),
        KCMIL_900 to r(435, 520, 585),
        KCMIL_1000 to r(455, 545, 615),
        KCMIL_1250 to r(495, 590, 665),
        KCMIL_1500 to r(525, 625, 705),
        KCMIL_1750 to r(545, 650, 735),
        KCMIL_2000 to r(555, 665, 750),
    )

    /** Table 310.16, "Aluminum or copper-clad aluminum" columns. */
    private val ampacityAluminum: Map<ConductorSize, AmpacityRow> = mapOf(
        AWG_12 to r(15, 20, 25),
        AWG_10 to r(25, 30, 35),
        AWG_8 to r(35, 40, 45),
        AWG_6 to r(40, 50, 55),
        AWG_4 to r(55, 65, 75),
        AWG_3 to r(65, 75, 85),
        AWG_2 to r(75, 90, 100),
        AWG_1 to r(85, 100, 115),
        AWG_1_0 to r(100, 120, 135),
        AWG_2_0 to r(115, 135, 150),
        AWG_3_0 to r(130, 155, 175),
        AWG_4_0 to r(150, 180, 205),
        KCMIL_250 to r(170, 205, 230),
        KCMIL_300 to r(195, 230, 260),
        KCMIL_350 to r(210, 250, 280),
        KCMIL_400 to r(225, 270, 305),
        KCMIL_500 to r(260, 310, 350),
        KCMIL_600 to r(285, 340, 385),
        KCMIL_700 to r(315, 375, 425),
        KCMIL_750 to r(320, 385, 435),
        KCMIL_800 to r(330, 395, 445),
        KCMIL_900 to r(355, 425, 480),
        KCMIL_1000 to r(375, 445, 500),
        KCMIL_1250 to r(405, 485, 545),
        KCMIL_1500 to r(435, 520, 585),
        KCMIL_1750 to r(455, 545, 615),
        KCMIL_2000 to r(470, 560, 630),
    )

    val ampacity: Map<ConductorMaterial, Map<ConductorSize, AmpacityRow>> = mapOf(
        ConductorMaterial.COPPER to ampacityCopper,
        ConductorMaterial.ALUMINUM to ampacityAluminum,
    )

    /**
     * **Table 310.15(B)(1)(1)** "Ambient Temperature Correction Factors Based on 30°C (86°F)", for use
     * with Table 310.16. Checked against the printed table (70-185). Rows are whole degrees (11–15,
     * 16–20…); null where the table has a dash.
     */
    val ambientCorrection: List<AmbientBand> = listOf(
        AmbientBand(10.0, 1.29, 1.20, 1.15),
        AmbientBand(15.0, 1.22, 1.15, 1.12),
        AmbientBand(20.0, 1.15, 1.11, 1.08),
        AmbientBand(25.0, 1.08, 1.05, 1.04),
        AmbientBand(30.0, 1.00, 1.00, 1.00),
        AmbientBand(35.0, 0.91, 0.94, 0.96),
        AmbientBand(40.0, 0.82, 0.88, 0.91),
        AmbientBand(45.0, 0.71, 0.82, 0.87),
        AmbientBand(50.0, 0.58, 0.75, 0.82),
        AmbientBand(55.0, 0.41, 0.67, 0.76),
        AmbientBand(60.0, null, 0.58, 0.71),
        AmbientBand(65.0, null, 0.47, 0.65),
        AmbientBand(70.0, null, 0.33, 0.58),
        AmbientBand(75.0, null, null, 0.50),
        AmbientBand(80.0, null, null, 0.41),
        AmbientBand(85.0, null, null, 0.29),
    )

    /**
     * **Table 310.15(C)(1)** "Adjustment Factors for More Than Three Current-Carrying Conductors"
     * (70-186). Three or fewer need no adjustment.
     */
    val conductorCountAdjustment: List<CountAdjustmentBand> = listOf(
        CountAdjustmentBand(4, 6, 0.80),
        CountAdjustmentBand(7, 9, 0.70),
        CountAdjustmentBand(10, 20, 0.50),
        CountAdjustmentBand(21, 30, 0.45),
        CountAdjustmentBand(31, 40, 0.40),
        CountAdjustmentBand(41, null, 0.35),
    )

    /**
     * **Table 310.4(1)** "Conductor Applications and Insulations Rated 600 Volts" (70-179 to 70-181):
     * the maximum operating temperature of each insulation, i.e. its Table 310.16 column (the dry and
     * damp rating for THHN, XHHW-2 and RHH). TFN is fixture wire (Table 402.5), not rated in 310.16.
     */
    val insulationRatings: Map<InsulationType, TemperatureRating> = mapOf(
        InsulationType.TW to TemperatureRating.C60,
        InsulationType.THW to TemperatureRating.C75,
        InsulationType.THHN to TemperatureRating.C90,
        InsulationType.XHHW to TemperatureRating.C90,
        InsulationType.XHHW_2 to TemperatureRating.C90,
        InsulationType.RHH_COVERED to TemperatureRating.C90,
        InsulationType.RHH_NO_COVERING to TemperatureRating.C90,
    )

    // ---- Chapter 9 Table 5 ----

    // Consecutive sizes starting at [first], so a table is two aligned arrays as printed.
    private fun table5(first: ConductorSize, areas: DoubleArray, diameters: DoubleArray): Map<ConductorSize, ConductorDimensions> {
        require(areas.size == diameters.size) { "Table 5 arrays must be the same length" }
        val sizes = ConductorSize.entries.drop(first.ordinal).take(areas.size)
        require(sizes.size == areas.size) { "Table 5 table runs past the largest size" }
        return sizes.indices.associate { sizes[it] to ConductorDimensions(areas[it], diameters[it]) }
    }

    // 14 AWG to 2000 kcmil is 28 rows: 14 12 10 8 6 4 3 2 1 1/0 2/0 3/0 4/0 250 300 350 400 500 600 700 750 800 900 1000 1250 1500 1750 2000

    private val thhn = table5(
        AWG_14,
        doubleArrayOf(
            0.0097, 0.0133, 0.0211, 0.0366, 0.0507, 0.0824, 0.0973, 0.1158, 0.1562, 0.1855, 0.2223, 0.2679, 0.3237,
            0.3970, 0.4608, 0.5242, 0.5863, 0.7073, 0.8676, 0.9887, 1.0496, 1.1085, 1.2311, 1.3478,
        ),
        doubleArrayOf(
            0.111, 0.130, 0.164, 0.216, 0.254, 0.324, 0.352, 0.384, 0.446, 0.486, 0.532, 0.584, 0.642,
            0.711, 0.766, 0.817, 0.864, 0.949, 1.051, 1.122, 1.156, 1.188, 1.252, 1.310,
        ),
    )

    private val tfn = table5(
        AWG_18,
        doubleArrayOf(0.0055, 0.0072),
        doubleArrayOf(0.084, 0.096),
    )

    private val xhhw = table5(
        AWG_14,
        doubleArrayOf(
            0.0139, 0.0181, 0.0243, 0.0437, 0.0590, 0.0814, 0.0962, 0.1146, 0.1534, 0.1825, 0.2190, 0.2642, 0.3197,
            0.3904, 0.4536, 0.5166, 0.5782, 0.6984, 0.8709, 0.9923, 1.0532, 1.1122, 1.2351, 1.3519,
            1.7180, 2.0156, 2.3127, 2.6073,
        ),
        doubleArrayOf(
            0.133, 0.152, 0.176, 0.236, 0.274, 0.322, 0.350, 0.382, 0.442, 0.482, 0.528, 0.580, 0.638,
            0.705, 0.760, 0.811, 0.858, 0.943, 1.053, 1.124, 1.158, 1.190, 1.254, 1.312,
            1.479, 1.602, 1.716, 1.822,
        ),
    )

    /** THW, THW-2, THHW and TW share one set of rows from 14 AWG up; 6 AWG and up also serve RHH*, RHW*, RHW-2*. */
    private val thw = table5(
        AWG_14,
        doubleArrayOf(
            0.0139, 0.0181, 0.0243, 0.0437, 0.0726, 0.0973, 0.1134, 0.1333, 0.1901, 0.2223, 0.2624, 0.3117, 0.3718,
            0.4596, 0.5281, 0.5958, 0.6619, 0.7901, 0.9729, 1.1010, 1.1652, 1.2272, 1.3561, 1.4784,
            1.8602, 2.1695, 2.4773, 2.7818,
        ),
        doubleArrayOf(
            0.133, 0.152, 0.176, 0.236, 0.304, 0.352, 0.380, 0.412, 0.492, 0.532, 0.578, 0.630, 0.688,
            0.765, 0.820, 0.871, 0.918, 1.003, 1.113, 1.184, 1.218, 1.250, 1.314, 1.372,
            1.539, 1.662, 1.776, 1.882,
        ),
    )

    private val rhhCovered = table5(
        AWG_14,
        doubleArrayOf(
            0.0293, 0.0353, 0.0437, 0.0835, 0.1041, 0.1333, 0.1521, 0.1750, 0.2660, 0.3039, 0.3505, 0.4072, 0.4754,
            0.6291, 0.7088, 0.7870, 0.8626, 1.0082, 1.2135, 1.3561, 1.4272, 1.4957, 1.6377, 1.7719,
            2.3479, 2.6938, 3.0357, 3.3719,
        ),
        doubleArrayOf(
            0.193, 0.212, 0.236, 0.326, 0.364, 0.412, 0.440, 0.472, 0.582, 0.622, 0.668, 0.720, 0.778,
            0.895, 0.950, 1.001, 1.048, 1.133, 1.243, 1.314, 1.348, 1.380, 1.444, 1.502,
            1.729, 1.852, 1.966, 2.072,
        ),
    )

    // Asterisked rows: 14 to 8 AWG have their own values; 6 AWG and larger equal THW.
    private val rhhNoCovering: Map<ConductorSize, ConductorDimensions> = buildMap {
        put(AWG_14, ConductorDimensions(0.0209, 0.163))
        put(AWG_12, ConductorDimensions(0.0260, 0.182))
        put(AWG_10, ConductorDimensions(0.0333, 0.206))
        put(AWG_8, ConductorDimensions(0.0556, 0.266))
        thw.forEach { (size, dims) -> if (size.ordinal >= AWG_6.ordinal) put(size, dims) }
    }

    /**
     * **Chapter 9 Table 5** "Dimensions of Insulated Conductors and Fixture Wires", columns
     * "Approximate Area, in.²" and "Approximate Diameter, in." (printed pages 70-720 to 70-723); every
     * area and diameter here was checked against the printed table. Values are worst-case for round
     * concentric-lay-stranded conductors and group solid with stranded. Compact conductors (Table 5A),
     * fixture wires other than TFN/TFFN, and the TFE/FEP/PFA/Z families are not included.
     * XHHW-2 uses the XHHW row.
     */
    val insulatedConductorDimensions: Map<InsulationType, Map<ConductorSize, ConductorDimensions>> = mapOf(
        InsulationType.THHN to thhn,
        InsulationType.XHHW to xhhw,
        InsulationType.XHHW_2 to xhhw,
        InsulationType.THW to thw,
        InsulationType.TW to thw,
        InsulationType.RHH_COVERED to rhhCovered,
        InsulationType.RHH_NO_COVERING to rhhNoCovering,
        InsulationType.TFN to tfn,
    )

    // ---- Chapter 9 Table 8 ----

    // One value per ConductorSize, 18 AWG to 2000 kcmil.
    private val strandedAreas = doubleArrayOf(
        0.002, 0.003, 0.004, 0.006, 0.011, 0.017, 0.027, 0.042, 0.053, 0.067, 0.087, 0.109, 0.137, 0.173, 0.219,
        0.260, 0.312, 0.364, 0.416, 0.519, 0.626, 0.730, 0.782, 0.834, 0.940, 1.042, 1.305, 1.566, 1.829, 2.092,
    )
    private val strandedDiameters = doubleArrayOf(
        0.046, 0.058, 0.073, 0.092, 0.116, 0.146, 0.184, 0.232, 0.260, 0.292, 0.332, 0.372, 0.418, 0.470, 0.528,
        0.575, 0.630, 0.681, 0.728, 0.813, 0.893, 0.964, 0.998, 1.030, 1.094, 1.152, 1.289, 1.412, 1.526, 1.632,
    )

    // Solid is listed for 18 to 8 AWG only.
    private val solidAreas = doubleArrayOf(0.001, 0.002, 0.003, 0.005, 0.008, 0.013)
    private val solidDiameters = doubleArrayOf(0.040, 0.051, 0.064, 0.081, 0.102, 0.128)

    /**
     * **Chapter 9 Table 8** "Conductor Properties" (printed page 70-725), columns "Overall" Diameter and
     * Area. Every value was checked against the book. The NEC prints areas to three decimals, so small
     * sizes are coarse (12 AWG stranded is 0.006 in²).
     */
    val bareConductorDimensions: Map<Stranding, Map<ConductorSize, ConductorDimensions>> = mapOf(
        Stranding.STRANDED to ConductorSize.entries.take(strandedAreas.size)
            .associateWith { ConductorDimensions(strandedAreas[it.ordinal], strandedDiameters[it.ordinal]) },
        Stranding.SOLID to ConductorSize.entries.take(solidAreas.size)
            .associateWith { ConductorDimensions(solidAreas[it.ordinal], solidDiameters[it.ordinal]) },
    )

    /**
     * **Chapter 9 Table 8** "Conductor Properties", "Direct-Current Resistance at 75°C (167°F)", the
     * ohm/kFT columns for uncoated copper, coated copper and aluminum, 18 AWG to 2000 kcmil, checked
     * against the printed 2023 table (70-725). 18 through 8 AWG are listed twice: solid, and 7-strand
     * Class B; larger sizes are stranded only. Arguments: uncoated, coated, aluminum (stranded), then
     * uncoated, coated, aluminum (solid).
     */
    val conductorResistance: Map<ConductorSize, ResistanceRow> = linkedMapOf(
        AWG_18 to ResistanceRow(7.95, 8.45, 13.1, 7.77, 8.08, 12.8),
        AWG_16 to ResistanceRow(4.99, 5.29, 8.21, 4.89, 5.08, 8.05),
        AWG_14 to ResistanceRow(3.14, 3.26, 5.17, 3.07, 3.19, 5.06),
        AWG_12 to ResistanceRow(1.98, 2.05, 3.25, 1.93, 2.01, 3.18),
        AWG_10 to ResistanceRow(1.24, 1.29, 2.04, 1.21, 1.26, 2.00),
        AWG_8 to ResistanceRow(0.778, 0.809, 1.28, 0.764, 0.786, 1.26),
        AWG_6 to ResistanceRow(0.491, 0.510, 0.808),
        AWG_4 to ResistanceRow(0.308, 0.321, 0.508),
        AWG_3 to ResistanceRow(0.245, 0.254, 0.403),
        AWG_2 to ResistanceRow(0.194, 0.201, 0.319),
        AWG_1 to ResistanceRow(0.154, 0.160, 0.253),
        AWG_1_0 to ResistanceRow(0.122, 0.127, 0.201),
        AWG_2_0 to ResistanceRow(0.0967, 0.101, 0.159),
        AWG_3_0 to ResistanceRow(0.0766, 0.0797, 0.126),
        AWG_4_0 to ResistanceRow(0.0608, 0.0626, 0.100),
        KCMIL_250 to ResistanceRow(0.0515, 0.0535, 0.0847),
        KCMIL_300 to ResistanceRow(0.0429, 0.0446, 0.0707),
        KCMIL_350 to ResistanceRow(0.0367, 0.0382, 0.0605),
        KCMIL_400 to ResistanceRow(0.0321, 0.0331, 0.0529),
        KCMIL_500 to ResistanceRow(0.0258, 0.0265, 0.0424),
        KCMIL_600 to ResistanceRow(0.0214, 0.0223, 0.0353),
        KCMIL_700 to ResistanceRow(0.0184, 0.0189, 0.0303),
        KCMIL_750 to ResistanceRow(0.0171, 0.0176, 0.0282),
        KCMIL_800 to ResistanceRow(0.0161, 0.0166, 0.0265),
        KCMIL_900 to ResistanceRow(0.0143, 0.0147, 0.0235),
        KCMIL_1000 to ResistanceRow(0.0129, 0.0132, 0.0212),
        KCMIL_1250 to ResistanceRow(0.0103, 0.0106, 0.0169),
        KCMIL_1500 to ResistanceRow(0.00858, 0.00883, 0.0141),
        KCMIL_1750 to ResistanceRow(0.00735, 0.00756, 0.0121),
        KCMIL_2000 to ResistanceRow(0.00643, 0.00662, 0.0106),
    )

    // ---- Chapter 9 Table 9 ----

    /** Arguments: R in PVC, R in aluminum, R in steel conduit, X in PVC and aluminum, X in steel. */
    private fun z(rp: Double, ra: Double, rs: Double, xn: Double, xs: Double) = ImpedanceRow(rp, ra, rs, xn, xs)

    /** Table 9, uncoated copper, 14 AWG to 1000 kcmil. */
    private val impedanceCopper: Map<ConductorSize, ImpedanceRow> = mapOf(
        AWG_14 to z(3.1, 3.1, 3.1, 0.058, 0.073),
        AWG_12 to z(2.0, 2.0, 2.0, 0.054, 0.068),
        AWG_10 to z(1.2, 1.2, 1.2, 0.050, 0.063),
        AWG_8 to z(0.78, 0.78, 0.78, 0.052, 0.065),
        AWG_6 to z(0.49, 0.49, 0.49, 0.051, 0.064),
        AWG_4 to z(0.31, 0.31, 0.31, 0.048, 0.060),
        AWG_3 to z(0.25, 0.25, 0.25, 0.047, 0.059),
        AWG_2 to z(0.19, 0.20, 0.20, 0.045, 0.057),
        AWG_1 to z(0.15, 0.16, 0.16, 0.046, 0.057),
        AWG_1_0 to z(0.12, 0.13, 0.12, 0.044, 0.055),
        AWG_2_0 to z(0.10, 0.10, 0.10, 0.043, 0.054),
        AWG_3_0 to z(0.077, 0.082, 0.079, 0.042, 0.052),
        AWG_4_0 to z(0.062, 0.067, 0.063, 0.041, 0.051),
        KCMIL_250 to z(0.052, 0.057, 0.054, 0.041, 0.052),
        KCMIL_300 to z(0.044, 0.049, 0.045, 0.041, 0.051),
        KCMIL_350 to z(0.038, 0.043, 0.039, 0.040, 0.050),
        KCMIL_400 to z(0.033, 0.038, 0.035, 0.040, 0.049),
        KCMIL_500 to z(0.027, 0.032, 0.029, 0.039, 0.048),
        KCMIL_600 to z(0.023, 0.028, 0.025, 0.039, 0.048),
        KCMIL_750 to z(0.019, 0.024, 0.021, 0.038, 0.048),
        KCMIL_1000 to z(0.015, 0.019, 0.018, 0.037, 0.046),
    )

    /** Table 9, aluminum, 12 AWG to 1000 kcmil (no 700, 800 or 900 kcmil rows). */
    private val impedanceAluminum: Map<ConductorSize, ImpedanceRow> = mapOf(
        AWG_12 to z(3.2, 3.2, 3.2, 0.054, 0.068),
        AWG_10 to z(2.0, 2.0, 2.0, 0.050, 0.063),
        AWG_8 to z(1.3, 1.3, 1.3, 0.052, 0.065),
        AWG_6 to z(0.81, 0.81, 0.81, 0.051, 0.064),
        AWG_4 to z(0.51, 0.51, 0.51, 0.048, 0.060),
        AWG_3 to z(0.40, 0.41, 0.40, 0.047, 0.059),
        AWG_2 to z(0.32, 0.32, 0.32, 0.045, 0.057),
        AWG_1 to z(0.25, 0.26, 0.25, 0.046, 0.057),
        AWG_1_0 to z(0.20, 0.21, 0.20, 0.044, 0.055),
        AWG_2_0 to z(0.16, 0.16, 0.16, 0.043, 0.054),
        AWG_3_0 to z(0.13, 0.13, 0.13, 0.042, 0.052),
        AWG_4_0 to z(0.10, 0.11, 0.10, 0.041, 0.051),
        KCMIL_250 to z(0.085, 0.090, 0.086, 0.041, 0.052),
        KCMIL_300 to z(0.071, 0.076, 0.072, 0.041, 0.051),
        KCMIL_350 to z(0.061, 0.066, 0.063, 0.040, 0.050),
        KCMIL_400 to z(0.054, 0.059, 0.055, 0.040, 0.049),
        KCMIL_500 to z(0.043, 0.048, 0.045, 0.039, 0.048),
        KCMIL_600 to z(0.036, 0.041, 0.038, 0.039, 0.048),
        KCMIL_750 to z(0.029, 0.034, 0.031, 0.038, 0.048),
        KCMIL_1000 to z(0.023, 0.027, 0.025, 0.037, 0.046),
    )

    /**
     * **Chapter 9 Table 9** "Alternating-Current Resistance and Reactance for 600-Volt Cables, 3-Phase,
     * 60 Hz, 75°C (167°F) — Three Single Conductors in Conduit", ohms per 1000 ft: columns "XL
     * (reactance) for all wires" and "Alternating-current resistance" for uncoated copper and aluminum.
     * The table's "Effective Z at 0.85 PF" columns are not stored; they are computed. Checked against
     * the printed 2023 table (70-726).
     */
    val acImpedance: Map<ConductorMaterial, Map<ConductorSize, ImpedanceRow>> = mapOf(
        ConductorMaterial.COPPER to impedanceCopper,
        ConductorMaterial.ALUMINUM to impedanceAluminum,
    )
}
