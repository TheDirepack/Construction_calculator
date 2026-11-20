package com.thedirepack.constructioncalc.core.electrical

/** Conductor insulation temperature column in Table 310.16 (or a terminal's temperature rating). */
enum class TemperatureRating(val celsius: Int) {
    C60(60), C75(75), C90(90);

    val label: String get() = "$celsius °C"

    companion object {
        fun fromCelsius(value: Int): TemperatureRating? = entries.firstOrNull { it.celsius == value }
    }
}

/**
 * Allowable ampacity of insulated conductors, in amperes.
 *
 * Source: NEC (NFPA 70) 2023, **Table 310.16** "Ampacities of Insulated Conductors with Not More Than
 * Three Current-Carrying Conductors in Raceway, Cable, or Earth (Directly Buried)", based on an
 * ambient of 30 °C (86 °F) (verified against the printed table, 70-187). Copper, and aluminum or
 * copper-clad aluminum; 18 AWG to 2000 kcmil. Blank cells (copper 18/16 AWG at 60 and 75 °C, copper
 * 14 AWG and smaller aluminum sizes) are absent here.
 *
 * Ambient-temperature and more-than-three-conductor corrections (310.15) and the 240.4(D) small-conductor
 * limits are NOT applied. The 60 and 75 °C columns are what termination temperature limits (110.14(C))
 * normally select.
 */
object Ampacity {
    /** (60 °C, 75 °C, 90 °C); 0 means the table cell is blank. */
    private class Row(val a60: Int, val a75: Int, val a90: Int) {
        fun of(rating: TemperatureRating) = when (rating) {
            TemperatureRating.C60 -> a60
            TemperatureRating.C75 -> a75
            TemperatureRating.C90 -> a90
        }
    }

    private fun r(a60: Int, a75: Int, a90: Int) = Row(a60, a75, a90)

    private val copper: Map<ConductorSize, Row> = mapOf(
        ConductorSize.AWG_18 to r(0, 0, 14),
        ConductorSize.AWG_16 to r(0, 0, 18),
        ConductorSize.AWG_14 to r(15, 20, 25),
        ConductorSize.AWG_12 to r(20, 25, 30),
        ConductorSize.AWG_10 to r(30, 35, 40),
        ConductorSize.AWG_8 to r(40, 50, 55),
        ConductorSize.AWG_6 to r(55, 65, 75),
        ConductorSize.AWG_4 to r(70, 85, 95),
        ConductorSize.AWG_3 to r(85, 100, 115),
        ConductorSize.AWG_2 to r(95, 115, 130),
        ConductorSize.AWG_1 to r(110, 130, 145),
        ConductorSize.AWG_1_0 to r(125, 150, 170),
        ConductorSize.AWG_2_0 to r(145, 175, 195),
        ConductorSize.AWG_3_0 to r(165, 200, 225),
        ConductorSize.AWG_4_0 to r(195, 230, 260),
        ConductorSize.KCMIL_250 to r(215, 255, 290),
        ConductorSize.KCMIL_300 to r(240, 285, 320),
        ConductorSize.KCMIL_350 to r(260, 310, 350),
        ConductorSize.KCMIL_400 to r(280, 335, 380),
        ConductorSize.KCMIL_500 to r(320, 380, 430),
        ConductorSize.KCMIL_600 to r(350, 420, 475),
        ConductorSize.KCMIL_700 to r(385, 460, 520),
        ConductorSize.KCMIL_750 to r(400, 475, 535),
        ConductorSize.KCMIL_800 to r(410, 490, 555),
        ConductorSize.KCMIL_900 to r(435, 520, 585),
        ConductorSize.KCMIL_1000 to r(455, 545, 615),
        ConductorSize.KCMIL_1250 to r(495, 590, 665),
        ConductorSize.KCMIL_1500 to r(525, 625, 705),
        ConductorSize.KCMIL_1750 to r(545, 650, 735),
        ConductorSize.KCMIL_2000 to r(555, 665, 750),
    )

    private val aluminum: Map<ConductorSize, Row> = mapOf(
        ConductorSize.AWG_12 to r(15, 20, 25),
        ConductorSize.AWG_10 to r(25, 30, 35),
        ConductorSize.AWG_8 to r(35, 40, 45),
        ConductorSize.AWG_6 to r(40, 50, 55),
        ConductorSize.AWG_4 to r(55, 65, 75),
        ConductorSize.AWG_3 to r(65, 75, 85),
        ConductorSize.AWG_2 to r(75, 90, 100),
        ConductorSize.AWG_1 to r(85, 100, 115),
        ConductorSize.AWG_1_0 to r(100, 120, 135),
        ConductorSize.AWG_2_0 to r(115, 135, 150),
        ConductorSize.AWG_3_0 to r(130, 155, 175),
        ConductorSize.AWG_4_0 to r(150, 180, 205),
        ConductorSize.KCMIL_250 to r(170, 205, 230),
        ConductorSize.KCMIL_300 to r(195, 230, 260),
        ConductorSize.KCMIL_350 to r(210, 250, 280),
        ConductorSize.KCMIL_400 to r(225, 270, 305),
        ConductorSize.KCMIL_500 to r(260, 310, 350),
        ConductorSize.KCMIL_600 to r(285, 340, 385),
        ConductorSize.KCMIL_700 to r(315, 375, 425),
        ConductorSize.KCMIL_750 to r(320, 385, 435),
        ConductorSize.KCMIL_800 to r(330, 395, 445),
        ConductorSize.KCMIL_900 to r(355, 425, 480),
        ConductorSize.KCMIL_1000 to r(375, 445, 500),
        ConductorSize.KCMIL_1250 to r(405, 485, 545),
        ConductorSize.KCMIL_1500 to r(435, 520, 585),
        ConductorSize.KCMIL_1750 to r(455, 545, 615),
        ConductorSize.KCMIL_2000 to r(470, 560, 630),
    )

    private fun table(material: ConductorMaterial) = if (material.isCopper) copper else aluminum // coated Cu → copper; CCA → aluminum (310.16 heading)

    /** Ampacity in amperes, or null if the table has no value for this size, material and column. */
    fun of(size: ConductorSize, material: ConductorMaterial, rating: TemperatureRating): Int? =
        table(material)[size]?.of(rating)?.takeIf { it > 0 }

    /** Sizes that have a value in this column, smallest first. */
    fun sizes(material: ConductorMaterial, rating: TemperatureRating): List<ConductorSize> =
        ConductorSize.entries.filter { of(it, material, rating) != null }

    /** The smallest size whose ampacity is at least [requiredAmps], or null if even 2000 kcmil is too small. */
    fun smallestSize(requiredAmps: Double, material: ConductorMaterial, rating: TemperatureRating): ConductorSize? =
        sizes(material, rating).firstOrNull { (of(it, material, rating) ?: 0) >= requiredAmps - 1e-9 }
}
