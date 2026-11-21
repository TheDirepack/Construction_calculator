package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.MotorFlcRow

/**
 * Motor full-load current (FLC) in amperes, by horsepower and rated motor voltage, from [Code.book]:
 * - [CodeRule.MOTOR_FLC_SINGLE_PHASE] (Table 430.248 in NEC 2023): single-phase AC motors;
 * - [CodeRule.MOTOR_FLC_THREE_PHASE] (Table 430.250 in NEC 2023): three-phase induction-type squirrel
 *   cage and wound rotor motors. The synchronous-type columns are not included.
 *
 * Per [CodeRule.MOTOR_TABLE_FLC] these table values (not the nameplate) size conductors and
 * short-circuit protection. The tables' notes say the values apply to system voltage ranges of
 * 110-120, 220-240, 440-480, 550-600 and 2300-2400 V, so a 480 V motor uses the 460 V column.
 */
object MotorFullLoadCurrent {
    val singlePhaseVoltages: List<Int> get() = voltages(AcPhases.SINGLE)
    val threePhaseVoltages: List<Int> get() = voltages(AcPhases.THREE)

    private fun rows(phase: AcPhases): List<MotorFlcRow> = Code.book.motorFullLoadCurrent.getValue(phase)

    private fun find(phase: AcPhases, hp: Double): MotorFlcRow? = rows(phase).firstOrNull { kotlin.math.abs(it.hp - hp) < 1e-6 }

    /** Voltages with a column in the table for [phase]. */
    fun voltages(phase: AcPhases): List<Int> = Code.book.motorFullLoadVoltages.getValue(phase)

    /** Horsepower ratings that have a value at [volts] (blank cells are left out), smallest first. */
    fun horsepowers(phase: AcPhases, volts: Int): List<Double> =
        rows(phase).filter { volts in it.amps }.map { it.hp }

    /** Full-load current in amperes, or null if the table has no value for this HP and voltage. */
    fun amps(phase: AcPhases, hp: Double, volts: Int): Double? = find(phase, hp)?.amps?.get(volts)

    /** Single-phase table value, or null. */
    fun singlePhase(hp: Double, volts: Int): Double? = amps(AcPhases.SINGLE, hp, volts)

    /** Three-phase induction-motor table value, or null. */
    fun threePhase(hp: Double, volts: Int): Double? = amps(AcPhases.THREE, hp, volts)

    /** Horsepower as the table prints it: "1/6", "1-1/2", "7-1/2", "250". */
    fun label(hp: Double): String =
        AcPhases.entries.flatMap { rows(it) }.firstOrNull { kotlin.math.abs(it.hp - hp) < 1e-6 }?.label
            ?: if (hp % 1.0 == 0.0) hp.toInt().toString() else hp.toString()
}
