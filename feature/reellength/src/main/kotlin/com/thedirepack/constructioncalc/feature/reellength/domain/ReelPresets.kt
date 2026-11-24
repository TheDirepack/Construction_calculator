package com.thedirepack.constructioncalc.feature.reellength.domain

import com.thedirepack.constructioncalc.core.model.ProjectCodec
import kotlinx.serialization.Serializable

private fun validateReelGeometry(
    flangeIn: Double,
    drumIn: Double,
    traverseIn: Double,
    clearanceIn: Double,
) {
    require(flangeIn.isFinite() && flangeIn > 0.0) { "Flange diameter must be positive" }
    require(drumIn.isFinite() && drumIn > 0.0) { "Drum diameter must be positive" }
    require(traverseIn.isFinite() && traverseIn > 0.0) { "Traverse must be positive" }
    require(clearanceIn.isFinite() && clearanceIn >= 0.0) { "Clearance cannot be negative" }
    require(flangeIn > drumIn) { "Flange diameter must be larger than drum diameter" }
    require(flangeIn - 2.0 * clearanceIn > drumIn) { "Clearance leaves no room for cable above the drum" }
}

/**
 * A reel dimension preset (either a nominal built-in electrical reel size or a user-saved custom
 * reel). All dimensions are stored in inches.
 */
@Serializable
data class ReelPreset(
    val id: String,
    val name: String,
    val flangeIn: Double,
    val drumIn: Double,
    val traverseIn: Double,
    val clearanceIn: Double = 2.0,
    val builtIn: Boolean = false,
    val note: String = "",
) {
    init {
        require(id.isNotBlank()) { "Reel preset id cannot be blank" }
        require(name.isNotBlank()) { "Reel preset name cannot be blank" }
        validateReelGeometry(flangeIn, drumIn, traverseIn, clearanceIn)
    }

    /** Converts this preset into a [ReelRequest] for the given [cableDiameterIn]. */
    fun toRequest(
        cableDiameterIn: Double,
        packingFactor: Double = 1.0,
        capacity: Boolean = true,
    ): ReelRequest = ReelRequest(
        flangeDiameter = flangeIn,
        drumDiameter = drumIn,
        traverse = traverseIn,
        cableDiameter = cableDiameterIn,
        topClearance = clearanceIn,
        packingFactor = packingFactor,
        capacity = capacity,
    )
}

/**
 * A combined reel + cable favorite setup so electricians can recall both reel dimensions and cable
 * OD in one tap while preserving any prefilled `cableNeeded` from Wire Pull Planner.
 */
@Serializable
data class ReelCableFavorite(
    val id: String,
    val name: String,
    val flangeIn: Double,
    val drumIn: Double,
    val traverseIn: Double,
    val clearanceIn: Double = 2.0,
    val cableName: String,
    val cableDiameterIn: Double,
    val favorite: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Reel+cable favorite id cannot be blank" }
        require(name.isNotBlank()) { "Reel+cable favorite name cannot be blank" }
        validateReelGeometry(flangeIn, drumIn, traverseIn, clearanceIn)
        require(cableName.isNotBlank()) { "Cable name cannot be blank" }
        require(cableDiameterIn.isFinite() && cableDiameterIn > 0.0) { "Cable diameter must be positive" }
    }

    /** Converts this combined setup into a [ReelRequest]. */
    fun toRequest(
        packingFactor: Double = 1.0,
        capacity: Boolean = true,
    ): ReelRequest = ReelRequest(
        flangeDiameter = flangeIn,
        drumDiameter = drumIn,
        traverse = traverseIn,
        cableDiameter = cableDiameterIn,
        topClearance = clearanceIn,
        packingFactor = packingFactor,
        capacity = capacity,
    )
}

/**
 * Serializable module payload stored under `SettingsRepository.module(ReelLengthTool.ID)`,
 * carrying user-defined reel presets, favorite reel ids, and combined reel + cable setups.
 */
@Serializable
data class ReelPresetsPayload(
    val customReels: List<ReelPreset> = emptyList(),
    val favoriteReelIds: Set<String> = emptySet(),
    val reelCableFavorites: List<ReelCableFavorite> = emptyList(),
) {
    /** All built-in and custom reels, sorted with favorites first. */
    fun allReels(): List<ReelPreset> = sortedReels(BuiltInReelPresets.all + customReels, favoriteReelIds)

    /** Favorited reels (built-in or custom), sorted alphabetically. */
    fun favoriteReels(): List<ReelPreset> = allReels().filter { it.id in favoriteReelIds }

    /** Combined reel + cable setups, sorted with starred setups first. */
    fun sortedReelCableFavorites(): List<ReelCableFavorite> = sortedFavorites(reelCableFavorites)

    companion object {
        /** Encodes [payload] as JSON via [ProjectCodec.json]. */
        fun encode(payload: ReelPresetsPayload): String =
            ProjectCodec.json.encodeToString(serializer(), payload)

        /** Decodes [json] produced by [encode], returning an empty payload on blank or invalid input. */
        fun decode(json: String): ReelPresetsPayload {
            val text = json.trim()
            if (text.isEmpty()) return ReelPresetsPayload()
            return runCatching { ProjectCodec.json.decodeFromString(serializer(), text) }
                .getOrDefault(ReelPresetsPayload())
        }

        /** Orders [reels] with [favoriteIds] first, then alphabetically by name. */
        fun sortedReels(reels: List<ReelPreset>, favoriteIds: Set<String>): List<ReelPreset> =
            reels.sortedWith(compareByDescending<ReelPreset> { it.id in favoriteIds }.thenBy { it.name.lowercase() })

        /** Orders [favorites] with starred entries first, then alphabetically by name. */
        fun sortedFavorites(favorites: List<ReelCableFavorite>): List<ReelCableFavorite> =
            favorites.sortedWith(compareByDescending<ReelCableFavorite> { it.favorite }.thenBy { it.name.lowercase() })
    }
}

/**
 * Nominal electrical reel presets. Manufacturers vary in flange/drum thickness and inside traverse,
 * so stamped dimensions on the reel always take precedence.
 */
object BuiltInReelPresets {
    const val NOMINAL_NOTE = "Nominal electrical reel size — verify stamped dimensions on the reel."

    val all: List<ReelPreset> = listOf(
        ReelPreset(
            id = "builtin-12x6",
            name = "12\" × 6\" (compact spool)",
            flangeIn = 12.0,
            drumIn = 5.0,
            traverseIn = 6.0,
            clearanceIn = 1.0,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
        ReelPreset(
            id = "builtin-16x8",
            name = "16\" × 8\" (small wood reel)",
            flangeIn = 16.0,
            drumIn = 8.0,
            traverseIn = 8.0,
            clearanceIn = 1.5,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
        ReelPreset(
            id = "builtin-18x10",
            name = "18\" × 10\" (standard wood reel)",
            flangeIn = 18.0,
            drumIn = 8.0,
            traverseIn = 10.0,
            clearanceIn = 1.5,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
        ReelPreset(
            id = "builtin-24x12",
            name = "24\" × 12\" (medium wood reel)",
            flangeIn = 24.0,
            drumIn = 10.0,
            traverseIn = 12.0,
            clearanceIn = 2.0,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
        ReelPreset(
            id = "builtin-30x16",
            name = "30\" × 16\" (feeder reel)",
            flangeIn = 30.0,
            drumIn = 14.0,
            traverseIn = 16.0,
            clearanceIn = 2.0,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
        ReelPreset(
            id = "builtin-36x18",
            name = "36\" × 18\" (heavy feeder reel)",
            flangeIn = 36.0,
            drumIn = 18.0,
            traverseIn = 18.0,
            clearanceIn = 2.0,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
        ReelPreset(
            id = "builtin-42x22",
            name = "42\" × 22\" (large wood reel)",
            flangeIn = 42.0,
            drumIn = 20.0,
            traverseIn = 22.0,
            clearanceIn = 2.5,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
        ReelPreset(
            id = "builtin-48x24",
            name = "48\" × 24\" (master reel)",
            flangeIn = 48.0,
            drumIn = 24.0,
            traverseIn = 24.0,
            clearanceIn = 3.0,
            builtIn = true,
            note = NOMINAL_NOTE,
        ),
    )
}
