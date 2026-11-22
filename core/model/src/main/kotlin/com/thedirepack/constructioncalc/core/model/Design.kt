package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.SupplySystem
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.measure.Dimension
import kotlinx.serialization.Serializable

/*
 * Electrical design data that lives in a project and links the tools together:
 * panels → circuits → the runs (conduits) they pass through. A run's conduit, length and thermal
 * conditions, plus everything sharing it, decide each circuit's minimum wire size
 * (see ProjectAnalysis).
 */

/** A panel's supply. [lineToNeutral] is null for systems without a neutral. */
@Serializable
enum class PanelSystem(
    val label: String,
    val phases: Int,
    val lineToLine: Int,
    val lineToNeutral: Int?,
    val supply: SupplySystem,
) {
    SINGLE_120_240("120/240 V 1φ 3W", 1, 240, 120, SupplySystem.SINGLE_PHASE_3W),
    WYE_208_120("208Y/120 V 3φ 4W", 3, 208, 120, SupplySystem.WYE_3PH_4W),
    WYE_480_277("480Y/277 V 3φ 4W", 3, 480, 277, SupplySystem.WYE_3PH_4W),
    DELTA_240("240 V 3φ 3W Δ", 3, 240, null, SupplySystem.DELTA_3PH_3W),
    DELTA_480("480 V 3φ 3W Δ", 3, 480, null, SupplySystem.DELTA_3PH_3W),
}

/** What occupies a breaker position. */
@Serializable
enum class CircuitKind(val label: String) {
    LOAD("Load"),
    SPARE("Spare"),   // a breaker with nothing connected
    SPACE("Space"),   // no breaker
}

/**
 * One breaker in a panel.
 *
 * @property slot the first position number (1-based; odd numbers on the left, even on the right).
 *   A multi-pole breaker also occupies slot + 2 (and + 4) on the same side.
 * @property loadVa connected load in volt-amperes (null if unknown).
 * @property continuous whether the load runs 3 hours or more (sized at 125%).
 * @property runIds the project runs (conduits) this circuit passes through, in order.
 * @property length one-way length override; otherwise the sum of its runs' lengths.
 * @property conductor overrides for this circuit (otherwise [DesignDefaults]).
 */
@Serializable
data class Circuit(
    val id: String = newId(),
    val slot: Int,
    val poles: Int = 1,
    val breakerAmps: Int? = 20,
    val description: String = "",
    val kind: CircuitKind = CircuitKind.LOAD,
    val loadVa: Double? = null,
    val continuous: Boolean = false,
    val neutral: Boolean = poles == 1,
    val nonlinear: Boolean = false,
    val multipleCordReceptacles: Boolean = false,
    val groupIds: List<String> = emptyList(),
    val runIds: List<String> = emptyList(),
    val length: StoredQuantity? = null,
    val conductor: ConductorSpec? = null,
    val note: String = "",
) {
    init {
        // A circuit may extend past its current panel after a resize.
        require(slot in 1..Panel.MAX_SPACES) { "Invalid circuit position: $slot" }
        require(poles in 1..3) { "Invalid circuit pole count: $poles" }
        require(breakerAmps == null || breakerAmps > 0) { "Invalid breaker rating: $breakerAmps" }
        require(loadVa == null || (loadVa.isFinite() && loadVa >= 0.0)) { "Invalid circuit load: $loadVa" }
        require(length == null || (length.dimension == Dimension.LENGTH && length.toQuantity().value.signum >= 0)) {
            "Invalid circuit length."
        }
    }

    /** Positions this breaker occupies. */
    val slots: List<Int> get() = (0 until poles).map { slot + 2 * it }
}

/** Conductor choice for a circuit or a project default. */
@Serializable
data class ConductorSpec(
    val material: ConductorMaterial = ConductorMaterial.COPPER,
    val insulation: InsulationType = InsulationType.THHN,
    /** Insulation temperature column for derating (THHN/THWN-2 and XHHW-2 are 90 °C). */
    val insulationRating: TemperatureRating = TemperatureRating.C90,
    /** Terminal rating limit (110.14(C)); 75 °C is typical for modern equipment. */
    val terminationRating: TemperatureRating = TemperatureRating.C75,
)

@Serializable
data class Panel(
    val id: String = newId(),
    val name: String,
    val location: String = "",
    val system: PanelSystem = PanelSystem.WYE_208_120,
    /** Number of breaker positions (e.g. 42). */
    val spaces: Int = 42,
    val busAmps: Int? = 225,
    /** Main breaker rating, or null for main lugs only (MLO). */
    val mainAmps: Int? = null,
    /** The circuit in another panel that feeds this one, if any. */
    val fedFrom: CircuitRef? = null,
    val note: String = "",
    val circuits: List<Circuit> = emptyList(),
) {
    init {
        require(spaces in 1..MAX_SPACES) { "Invalid panel space count: $spaces" }
        require(busAmps == null || busAmps > 0) { "Invalid panel bus rating: $busAmps" }
        require(mainAmps == null || mainAmps > 0) { "Invalid panel main rating: $mainAmps" }
    }

    companion object {
        const val MAX_SPACES = 120
    }
}

@Serializable
data class CircuitRef(val panelId: String, val circuitId: String)

/** A tag that groups circuits across panels ("Kitchen", "RTU-3", "Emergency"). */
@Serializable
data class CircuitGroup(val id: String = newId(), val name: String, val color: Int = 0)

/**
 * Conduit details for a run: the conduit, its length and the conditions it passes through.
 * Every circuit assigned to the run shares it (conductor count, fill, temperature).
 */
@Serializable
data class Raceway(
    val conduitType: ConduitType = ConduitType.EMT,
    /** Null: let the app pick the smallest that fits. */
    val tradeSize: TradeSize? = null,
    val length: StoredQuantity? = null,
    /** Ambient temperature along the run, °C; null uses the project default. */
    val ambientC: Double? = null,
    /** In direct sunlight less than 3/4" above a roof (310.15(B)(2)). */
    val rooftop: Boolean = false,
    /** Run is 24" or shorter (a nipple): no count adjustment (310.15(C)(1)(b)), 60% fill. */
    val nipple: Boolean = false,
) {
    init {
        require(length == null || (length.dimension == Dimension.LENGTH && length.toQuantity().value.signum >= 0)) {
            "Invalid raceway length."
        }
        require(ambientC == null || (ambientC.isFinite() && ambientC in -100.0..200.0)) {
            "Invalid raceway ambient temperature: $ambientC"
        }
    }
}

/** Project-wide design assumptions every circuit starts from. */
@Serializable
data class DesignDefaults(
    val conductor: ConductorSpec = ConductorSpec(),
    val ambientC: Double = 30.0,
    val voltageDropTargetPercent: Double = Code.book.values.voltageDropBranchPercent,
    /** Upsize conductors to meet [voltageDropTargetPercent] (and the EGC with them, 250.122(B)). */
    val upsizeForVoltageDrop: Boolean = true,
) {
    init {
        require(ambientC.isFinite() && ambientC in -100.0..200.0) { "Invalid ambient temperature: $ambientC" }
        require(voltageDropTargetPercent.isFinite() && voltageDropTargetPercent > 0.0 && voltageDropTargetPercent < 100.0) {
            "Invalid voltage drop target: $voltageDropTargetPercent"
        }
    }
}
