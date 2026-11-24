package com.thedirepack.constructioncalc.feature.reellength

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.reellength.domain.BuiltInReelPresets
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelCableFavorite
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelPreset
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelPresetsPayload

/**
 * Persistence store for custom reel presets, favorite reel ids, and combined reel + cable setups.
 * Stored in `SettingsRepository.module(ReelLengthTool.ID)` so full backups carry and restore them.
 */
class ReelPresetStore(private val settings: ModuleSettings) {

    /** Decodes the stored [ReelPresetsPayload]. */
    fun payload(): ReelPresetsPayload =
        ReelPresetsPayload.decode(settings.getString(SETTINGS_KEY, ""))

    /** All built-in and custom reels, ordered with favorites first. */
    fun allReels(): List<ReelPreset> = payload().allReels()

    /** Favorited reels (built-in and custom), ordered alphabetically. */
    fun favoriteReels(): List<ReelPreset> = payload().favoriteReels()

    /** Toggles whether the reel preset with [id] is marked as a favorite. */
    fun toggleFavoriteReel(id: String) {
        val current = payload()
        val validIds = (BuiltInReelPresets.all + current.customReels).mapTo(mutableSetOf()) { it.id }
        if (id !in validIds) return
        val nextIds = if (id in current.favoriteReelIds) {
            current.favoriteReelIds - id
        } else {
            current.favoriteReelIds + id
        }
        write(current.copy(favoriteReelIds = nextIds))
    }

    /** Saves a user-defined [preset], replacing any existing custom reel with the same id or name. */
    fun saveCustomReel(preset: ReelPreset) {
        val current = payload()
        val clean = preset.copy(name = preset.name.trim(), builtIn = false, note = preset.note.trim())
        val replacedIds = current.customReels
            .filter { it.id == clean.id || it.name.trim().equals(clean.name, ignoreCase = true) }
            .mapTo(mutableSetOf()) { it.id }
        val kept = current.customReels.filterNot { it.id in replacedIds }
        val nextFavorites = if (replacedIds.any { it in current.favoriteReelIds }) {
            (current.favoriteReelIds - replacedIds) + clean.id
        } else {
            current.favoriteReelIds
        }
        write(current.copy(customReels = kept + clean, favoriteReelIds = nextFavorites))
    }

    /** Deletes a custom reel with [id] (built-in presets are never deleted). */
    fun deleteCustomReel(id: String) {
        val current = payload()
        val nextCustom = current.customReels.filterNot { it.id == id }
        if (nextCustom.size == current.customReels.size && id !in current.favoriteReelIds) return
        write(current.copy(customReels = nextCustom, favoriteReelIds = current.favoriteReelIds - id))
    }

    /** Saved reel + cable setups, ordered with starred entries first. */
    fun favorites(): List<ReelCableFavorite> = payload().sortedReelCableFavorites()

    /** Saves a combined reel + cable [setup], replacing one with the same id or name. */
    fun saveFavoriteSetup(setup: ReelCableFavorite) {
        val current = payload()
        val clean = setup.copy(name = setup.name.trim(), cableName = setup.cableName.trim())
        val kept = current.reelCableFavorites.filterNot {
            it.id == clean.id || it.name.trim().equals(clean.name, ignoreCase = true)
        }
        write(current.copy(reelCableFavorites = kept + clean))
    }

    /** Toggles whether the reel + cable setup with [id] is starred. */
    fun toggleFavoriteSetup(id: String) {
        val current = payload()
        val updated = current.reelCableFavorites.map {
            if (it.id == id) it.copy(favorite = !it.favorite) else it
        }
        if (updated != current.reelCableFavorites) {
            write(current.copy(reelCableFavorites = updated))
        }
    }

    /** Deletes the combined reel + cable setup with [id]. */
    fun deleteFavoriteSetup(id: String) {
        val current = payload()
        val next = current.reelCableFavorites.filterNot { it.id == id }
        if (next.size == current.reelCableFavorites.size) return
        write(current.copy(reelCableFavorites = next))
    }

    private fun write(payload: ReelPresetsPayload) {
        settings.setString(SETTINGS_KEY, ReelPresetsPayload.encode(payload))
    }

    companion object {
        const val SETTINGS_KEY = "reel_presets_json"
    }
}

/** Reactive snapshot of the reel presets store for Compose screens. */
data class ReelPresetsState(
    val payload: ReelPresetsPayload,
    val allReels: List<ReelPreset>,
    val favoriteReels: List<ReelPreset>,
    val favorites: List<ReelCableFavorite>,
)

/** Returns [ReelPresetStore] and its reactive [ReelPresetsState], re-read whenever settings change. */
@Composable
fun rememberReelPresets(): Pair<ReelPresetStore, ReelPresetsState> {
    val settings = LocalAppServices.current.settings.module(ReelLengthTool.ID)
    val version by settings.changes.collectAsStateWithLifecycle()
    val store = remember(settings) { ReelPresetStore(settings) }
    val state = remember(store, version) {
        val payload = store.payload()
        ReelPresetsState(
            payload = payload,
            allReels = payload.allReels(),
            favoriteReels = payload.favoriteReels(),
            favorites = payload.sortedReelCableFavorites(),
        )
    }
    return store to state
}
