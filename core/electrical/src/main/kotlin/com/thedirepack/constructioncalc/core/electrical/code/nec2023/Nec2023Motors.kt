package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.MotorProtectiveDevice
import com.thedirepack.constructioncalc.core.electrical.MotorType
import com.thedirepack.constructioncalc.core.electrical.code.MotorFlcRow

/** NEC 2023 Article 430 motor tables. Data only. */
internal object Nec2023Motors {

    private fun hp(value: Double, label: String, vararg amps: Pair<Int, Double>) = MotorFlcRow(value, label, amps.toMap())

    /**
     * **Table 430.248** "Full-Load Currents in Amperes, Single-Phase Alternating-Current Motors":
     * 115 / 200 / 208 / 230 V, 1/6 to 10 HP (verified against the printed table, 70-363).
     */
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

    /**
     * **Table 430.250** "Full-Load Current, Three-Phase Alternating-Current Motors": induction-type
     * squirrel cage and wound rotor, 115 / 200 / 208 / 230 / 460 / 575 / 2300 V, 1/2 to 500 HP (verified
     * against the printed table, 70-364). The synchronous-type columns are not included.
     */
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

    val fullLoadCurrent: Map<AcPhases, List<MotorFlcRow>> = mapOf(
        AcPhases.SINGLE to singlePhase,
        AcPhases.THREE to threePhase,
    )

    /** The voltage columns of Tables 430.248 and 430.250. */
    val fullLoadVoltages: Map<AcPhases, List<Int>> = mapOf(
        AcPhases.SINGLE to listOf(115, 200, 208, 230),
        AcPhases.THREE to listOf(115, 200, 208, 230, 460, 575, 2300),
    )

    // Columns in order: nontime-delay fuse, dual-element fuse, instantaneous-trip breaker, inverse-time breaker.
    private fun percents(nontimeDelay: Int, dualElement: Int, instantaneous: Int, inverseTime: Int) = mapOf(
        MotorProtectiveDevice.NONTIME_DELAY_FUSE to nontimeDelay,
        MotorProtectiveDevice.DUAL_ELEMENT_FUSE to dualElement,
        MotorProtectiveDevice.INSTANTANEOUS_TRIP_BREAKER to instantaneous,
        MotorProtectiveDevice.INVERSE_TIME_BREAKER to inverseTime,
    )

    /**
     * **Table 430.52(C)(1)** "Maximum Rating or Setting of Motor Branch-Circuit Short-Circuit and
     * Ground-Fault Protective Devices", percent of full-load current. Checked against the printed
     * table (70-350).
     */
    val branchProtection: Map<MotorType, Map<MotorProtectiveDevice, Int>> = mapOf(
        MotorType.SINGLE_PHASE to percents(300, 175, 800, 250),
        MotorType.POLYPHASE_AC to percents(300, 175, 800, 250),
        MotorType.SQUIRREL_CAGE to percents(300, 175, 800, 250),
        MotorType.DESIGN_B_EFFICIENT to percents(300, 175, 1100, 250),
        MotorType.SYNCHRONOUS to percents(300, 175, 800, 250),
        MotorType.WOUND_ROTOR to percents(150, 150, 800, 150),
        MotorType.DC_CONSTANT_VOLTAGE to percents(150, 150, 250, 150),
    )
}
