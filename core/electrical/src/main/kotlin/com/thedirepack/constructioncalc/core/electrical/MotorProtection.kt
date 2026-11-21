package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/** The protective device columns of Table 430.52(C)(1). */
enum class MotorProtectiveDevice(val label: String) {
    NONTIME_DELAY_FUSE("Nontime-delay fuse"),
    DUAL_ELEMENT_FUSE("Dual-element (time-delay) fuse"),
    INSTANTANEOUS_TRIP_BREAKER("Instantaneous-trip breaker"),
    INVERSE_TIME_BREAKER("Inverse-time breaker"),
}

/** The motor-type rows of Table 430.52(C)(1). */
enum class MotorType(val label: String) {
    SINGLE_PHASE("Single-phase"),
    POLYPHASE_AC("AC polyphase, other than wound-rotor"),
    SQUIRREL_CAGE("Squirrel cage, other than Design B energy-efficient and premium efficiency"),
    DESIGN_B_EFFICIENT("Design B energy-efficient and premium efficiency"),
    SYNCHRONOUS("Synchronous"),
    WOUND_ROTOR("Wound-rotor"),
    DC_CONSTANT_VOLTAGE("DC (constant voltage)"),
}

/**
 * [CodeRule.MOTOR_BRANCH_PROTECTION] (Table 430.52(C)(1) in NEC 2023), "Maximum Rating or Setting of
 * Motor Branch-Circuit Short-Circuit and Ground-Fault Protective Devices", as a percentage of motor
 * full-load current, from [Code.book].
 *
 * The table's notes, in short: the nontime-delay fuse column also covers time-delay Class CC fuses;
 * the inverse-time breaker values also cover nonadjustable breakers that 430.52(C)(1)(a) and (b) let
 * you increase; low-torque, low-speed synchronous motors that start unloaded don't need more than
 * 200%. 430.54 has exceptions.
 */
object MotorProtection {
    /** Maximum rating or setting as a percent of full-load current. */
    fun percent(motor: MotorType, device: MotorProtectiveDevice): Int =
        Code.book.motorBranchProtection.getValue(motor).getValue(device)

    /** [percent] as a multiplier, e.g. 2.5 for 250%. */
    fun factor(motor: MotorType, device: MotorProtectiveDevice): Double = percent(motor, device) / 100.0
}
