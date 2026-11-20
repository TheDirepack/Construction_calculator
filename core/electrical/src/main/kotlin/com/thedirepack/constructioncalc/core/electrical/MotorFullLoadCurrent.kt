package com.thedirepack.constructioncalc.core.electrical

/**
 * Motor full-load current (FLC) in amperes, by horsepower and rated motor voltage.
 *
 * Sources (verified against the printed NEC 2023 tables, 70-363 and 70-364):
 * - NEC (NFPA 70) 2023, **Table 430.248** "Full-Load Currents in Amperes, Single-Phase
 *   Alternating-Current Motors": 115 / 200 / 208 / 230 V, 1/6 to 10 HP.
 * - NEC (NFPA 70) 2023, **Table 430.250** "Full-Load Current, Three-Phase Alternating-Current Motors":
 *   induction-type squirrel cage and wound rotor, 115 / 200 / 208 / 230 / 460 / 575 / 2300 V,
 *   1/2 to 500 HP. The synchronous-type columns are not included.
 *
 * Per 430.6(A)(1) these table values (not the nameplate) size conductors and short-circuit
 * protection. The tables' notes say the values apply to system voltage ranges of 110-120, 220-240,
 * 440-480, 550-600 and 2300-2400 V, so a 480 V motor uses the 460 V column.
 */
object MotorFullLoadCurrent {
    /** One horsepower row. [amps] is keyed by volts; a voltage with no entry is a blank cell in the table. */
    private class Row(val hp: Double, val label: String, val amps: Map<Int, Double>)

    private fun hp(value: Double, label: String, vararg amps: Pair<Int, Double>) = Row(value, label, amps.toMap())

    val singlePhaseVoltages: List<Int> = listOf(115, 200, 208, 230)
    val threePhaseVoltages: List<Int> = listOf(115, 200, 208, 230, 460, 575, 2300)

    // Table 430.248. Columns: 115, 200, 208, 230 V.
    private val singlePhase = listOf(
        hp(1.0 / 6, "1/6", 115 to 4.4, 200 to 2.5, 208 to 2.4, 230 to 2.2),
        hp(0.25, "1/4", 115 to 5.8, 200 to 3.3, 208 to 3.2, 230 to 2.9),
        hp(1.0 / 3, "1/3", 115 to 7.2, 200 to 4.1, 208 to 4.0, 230 to 3.6),
        hp(0.5, "1/2", 115 to 9.8, 200 to 5.6, 208 to 5.4, 230 to 4.9),
        hp(0.75, "3/4", 115 to 13.8, 200 to 7.9, 208 to 7.6, 230 to 6.9),
        hp(1.0, "1", 115 to 16.0, 200 to 9.2, 208 to 8.8, 230 to 8.0),
        hp(1.5, "1-1/2", 115 to 20.0, 200 to 11.5, 208 to 11.0, 230 to 10.0),
        hp(2.0, "2", 115 to 24.0, 200 to 13.8, 208 to 13.2, 230 to 12.0),
        hp(3.0, "3", 115 to 34.0, 200 to 19.6, 208 to 18.7, 230 to 17.0),
        hp(5.0, "5", 115 to 56.0, 200 to 32.2, 208 to 30.8, 230 to 28.0),
        hp(7.5, "7-1/2", 115 to 80.0, 200 to 46.0, 208 to 44.0, 230 to 40.0),
        hp(10.0, "10", 115 to 100.0, 200 to 57.5, 208 to 55.0, 230 to 50.0),
    )

    // Table 430.250, induction type. Columns: 115, 200, 208, 230, 460, 575, 2300 V.
    private val threePhase = listOf(
        hp(0.5, "1/2", 115 to 4.4, 200 to 2.5, 208 to 2.4, 230 to 2.2, 460 to 1.1, 575 to 0.9),
        hp(0.75, "3/4", 115 to 6.4, 200 to 3.7, 208 to 3.5, 230 to 3.2, 460 to 1.6, 575 to 1.3),
        hp(1.0, "1", 115 to 8.4, 200 to 4.8, 208 to 4.6, 230 to 4.2, 460 to 2.1, 575 to 1.7),
        hp(1.5, "1-1/2", 115 to 12.0, 200 to 6.9, 208 to 6.6, 230 to 6.0, 460 to 3.0, 575 to 2.4),
        hp(2.0, "2", 115 to 13.6, 200 to 7.8, 208 to 7.5, 230 to 6.8, 460 to 3.4, 575 to 2.7),
        hp(3.0, "3", 200 to 11.0, 208 to 10.6, 230 to 9.6, 460 to 4.8, 575 to 3.9),
        hp(5.0, "5", 200 to 17.5, 208 to 16.7, 230 to 15.2, 460 to 7.6, 575 to 6.1),
        hp(7.5, "7-1/2", 200 to 25.3, 208 to 24.2, 230 to 22.0, 460 to 11.0, 575 to 9.0),
        hp(10.0, "10", 200 to 32.2, 208 to 30.8, 230 to 28.0, 460 to 14.0, 575 to 11.0),
        hp(15.0, "15", 200 to 48.3, 208 to 46.2, 230 to 42.0, 460 to 21.0, 575 to 17.0),
        hp(20.0, "20", 200 to 62.1, 208 to 59.4, 230 to 54.0, 460 to 27.0, 575 to 22.0),
        hp(25.0, "25", 200 to 78.2, 208 to 74.8, 230 to 68.0, 460 to 34.0, 575 to 27.0),
        hp(30.0, "30", 200 to 92.0, 208 to 88.0, 230 to 80.0, 460 to 40.0, 575 to 32.0),
        hp(40.0, "40", 200 to 120.0, 208 to 114.0, 230 to 104.0, 460 to 52.0, 575 to 41.0),
        hp(50.0, "50", 200 to 150.0, 208 to 143.0, 230 to 130.0, 460 to 65.0, 575 to 52.0),
        hp(60.0, "60", 200 to 177.0, 208 to 169.0, 230 to 154.0, 460 to 77.0, 575 to 62.0, 2300 to 16.0),
        hp(75.0, "75", 200 to 221.0, 208 to 211.0, 230 to 192.0, 460 to 96.0, 575 to 77.0, 2300 to 20.0),
        hp(100.0, "100", 200 to 285.0, 208 to 273.0, 230 to 248.0, 460 to 124.0, 575 to 99.0, 2300 to 26.0),
        hp(125.0, "125", 200 to 359.0, 208 to 343.0, 230 to 312.0, 460 to 156.0, 575 to 125.0, 2300 to 31.0),
        hp(150.0, "150", 200 to 414.0, 208 to 396.0, 230 to 360.0, 460 to 180.0, 575 to 144.0, 2300 to 37.0),
        hp(200.0, "200", 200 to 552.0, 208 to 528.0, 230 to 480.0, 460 to 240.0, 575 to 192.0, 2300 to 49.0),
        hp(250.0, "250", 460 to 302.0, 575 to 242.0, 2300 to 60.0),
        hp(300.0, "300", 460 to 361.0, 575 to 289.0, 2300 to 72.0),
        hp(350.0, "350", 460 to 414.0, 575 to 336.0, 2300 to 83.0),
        hp(400.0, "400", 460 to 477.0, 575 to 382.0, 2300 to 95.0),
        hp(450.0, "450", 460 to 515.0, 575 to 412.0, 2300 to 103.0),
        hp(500.0, "500", 460 to 590.0, 575 to 472.0, 2300 to 118.0),
    )

    private fun rows(phase: AcPhases) = if (phase == AcPhases.SINGLE) singlePhase else threePhase

    private fun find(phase: AcPhases, hp: Double): Row? = rows(phase).firstOrNull { kotlin.math.abs(it.hp - hp) < 1e-6 }

    /** Voltages with a column in the table for [phase]. */
    fun voltages(phase: AcPhases): List<Int> = if (phase == AcPhases.SINGLE) singlePhaseVoltages else threePhaseVoltages

    /** Horsepower ratings that have a value at [volts] (blank cells are left out), smallest first. */
    fun horsepowers(phase: AcPhases, volts: Int): List<Double> =
        rows(phase).filter { volts in it.amps }.map { it.hp }

    /** Full-load current in amperes, or null if the table has no value for this HP and voltage. */
    fun amps(phase: AcPhases, hp: Double, volts: Int): Double? = find(phase, hp)?.amps?.get(volts)

    /** Table 430.248 value, or null. */
    fun singlePhase(hp: Double, volts: Int): Double? = amps(AcPhases.SINGLE, hp, volts)

    /** Table 430.250 induction-motor value, or null. */
    fun threePhase(hp: Double, volts: Int): Double? = amps(AcPhases.THREE, hp, volts)

    /** Horsepower as the table prints it: "1/6", "1-1/2", "7-1/2", "250". */
    fun label(hp: Double): String =
        (singlePhase + threePhase).firstOrNull { kotlin.math.abs(it.hp - hp) < 1e-6 }?.label
            ?: if (hp % 1.0 == 0.0) hp.toInt().toString() else hp.toString()
}
