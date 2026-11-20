package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import kotlinx.serialization.Serializable

/** Which of the three related quantities the user types in. */
@Serializable
enum class PowerTerm(val label: String, val unit: String) {
    AMPS("Amps", "A"),
    KW("kW", "kW"),
    KVA("kVA", "kVA"),
}

data class CurrentPowerResult(val amps: Double, val kw: Double, val kva: Double)

/**
 * Amps ↔ kW and kVA for a balanced load:
 * `kVA = V × I × k / 1000` (k = 1 single-phase, √3 three-phase, V line-to-line) and `kW = kVA × PF`.
 */
object CurrentPower {
    /** Reasons the numbers can't be used; empty when they can. */
    fun problems(value: Double, volts: Double, powerFactor: Double): List<String> = buildList {
        if (!value.isFinite() || value < 0) add("Enter a finite value of zero or more.")
        if (!volts.isFinite() || volts <= 0) add("Voltage must be finite and more than zero.")
        if (!powerFactor.isFinite() || powerFactor <= 0 || powerFactor > 1) add("Power factor must be above 0 and at most 1.")
    }

    /** Call only when [problems] is empty. */
    fun calculate(from: PowerTerm, value: Double, phase: AcPhases, volts: Double, powerFactor: Double): CurrentPowerResult {
        val voltAmpsPerAmp = volts * phase.powerFactorK / 1000.0
        return when (from) {
            PowerTerm.AMPS -> {
                val kva = value * voltAmpsPerAmp
                CurrentPowerResult(value, kva * powerFactor, kva)
            }
            PowerTerm.KW -> {
                val kva = value / powerFactor
                CurrentPowerResult(kva / voltAmpsPerAmp, value, kva)
            }
            PowerTerm.KVA -> CurrentPowerResult(value / voltAmpsPerAmp, value * powerFactor, value)
        }
    }
}
