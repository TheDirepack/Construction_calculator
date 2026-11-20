package com.thedirepack.constructioncalc.core.data

import kotlinx.serialization.json.JsonElement

/**
 * Lets a tool include data it keeps outside projects (history, custom tables…) in backups.
 * A tool provides one through `ToolModule.backup(context)`. Settings-page values don't need this:
 * they're backed up automatically from `SettingsRepository.module(id)`.
 */
interface ToolBackup {
    /** The tool's data in any JSON shape it likes, or null if there's nothing to save. */
    fun export(): JsonElement?

    /**
     * Restores [data] written by [export] (possibly by another app version: be lenient).
     * With [replace], existing data is discarded first; otherwise merge sensibly.
     */
    fun import(data: JsonElement, replace: Boolean)
}
