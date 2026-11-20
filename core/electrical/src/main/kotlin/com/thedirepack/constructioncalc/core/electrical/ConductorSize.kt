package com.thedirepack.constructioncalc.core.electrical

/**
 * Standard conductor sizes, smallest to largest, with their area in circular mils.
 * Source: NEC (NFPA 70) 2023, Chapter 9, Table 8 "Conductor Properties", Area column.
 *
 * Shared by every electrical tool. Per-size properties (resistance, insulated area, ampacity…)
 * live in their own tables keyed by this enum.
 */
enum class ConductorSize(val label: String, val circularMils: Int) {
    AWG_18("18 AWG", 1_620),
    AWG_16("16 AWG", 2_580),
    AWG_14("14 AWG", 4_110),
    AWG_12("12 AWG", 6_530),
    AWG_10("10 AWG", 10_380),
    AWG_8("8 AWG", 16_510),
    AWG_6("6 AWG", 26_240),
    AWG_4("4 AWG", 41_740),
    AWG_3("3 AWG", 52_620),
    AWG_2("2 AWG", 66_360),
    AWG_1("1 AWG", 83_690),
    AWG_1_0("1/0 AWG", 105_600),
    AWG_2_0("2/0 AWG", 133_100),
    AWG_3_0("3/0 AWG", 167_800),
    AWG_4_0("4/0 AWG", 211_600),
    KCMIL_250("250 kcmil", 250_000),
    KCMIL_300("300 kcmil", 300_000),
    KCMIL_350("350 kcmil", 350_000),
    KCMIL_400("400 kcmil", 400_000),
    KCMIL_500("500 kcmil", 500_000),
    KCMIL_600("600 kcmil", 600_000),
    KCMIL_700("700 kcmil", 700_000),
    KCMIL_750("750 kcmil", 750_000),
    KCMIL_800("800 kcmil", 800_000),
    KCMIL_900("900 kcmil", 900_000),
    KCMIL_1000("1000 kcmil", 1_000_000),
    KCMIL_1250("1250 kcmil", 1_250_000),
    KCMIL_1500("1500 kcmil", 1_500_000),
    KCMIL_1750("1750 kcmil", 1_750_000),
    KCMIL_2000("2000 kcmil", 2_000_000);

    /** Cross-sectional area in mm² (1 circular mil = π/4 × 0.0254² mm²). */
    val squareMillimeters: Double get() = circularMils * Math.PI / 4 * 0.0254 * 0.0254

    /** Short label for tight spaces: "12", "1/0", "250". */
    val shortLabel: String get() = label.substringBefore(' ')

    companion object {
        fun fromLabel(text: String): ConductorSize? =
            entries.firstOrNull { it.label.equals(text.trim(), ignoreCase = true) || it.shortLabel == text.trim() }
    }
}

/**
 * Conductor metal.
 *
 * NEC tables don't all have a column for every material. [tableBasis] is the material whose
 * values are used where a table has no column of its own:
 * - Coated (tinned) copper has its own Table 8 resistance column, but Table 9 and the ampacity
 *   tables list plain copper, which is used for it.
 * - Copper-clad aluminum is grouped with aluminum in the ampacity tables ("Aluminum or
 *   copper-clad aluminum", Table 310.16) and isn't in Table 8 or 9; aluminum values are used
 *   (Table 8 note 4 rates aluminum at 61% IACS; copper-clad aluminum is similar).
 */
enum class ConductorMaterial(val label: String, val shortLabel: String) {
    COPPER("Copper", "Cu"),
    COATED_COPPER("Coated (tinned) copper", "Cu coated"),
    ALUMINUM("Aluminum", "Al"),
    COPPER_CLAD_ALUMINUM("Copper-clad aluminum", "CCA");

    /** The material whose table values stand in for this one where a table has no own column. */
    val tableBasis: ConductorMaterial
        get() = when (this) {
            COPPER, COATED_COPPER -> COPPER
            ALUMINUM, COPPER_CLAD_ALUMINUM -> ALUMINUM
        }

    val isCopper: Boolean get() = tableBasis == COPPER
}
