package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A backup file (`… .backup.json`): everything in the app (or any subset of it) in one plain,
 * pretty-printed JSON file.
 * Each section is optional, so the same format carries a full backup, "all projects", or just
 * settings. Unknown fields are ignored when reading, so newer and older app versions can exchange
 * files.
 *
 * @property settings app-wide settings (theme, animations, measurement display…), as written by
 *   `SettingsRepository.exportSettings()`.
 * @property toolSettings each tool's own settings page values, keyed by tool id.
 * @property toolData data a tool keeps outside projects (e.g. Foot-Inch history), keyed by tool id;
 *   each tool defines its own shape.
 */
@Serializable
data class BackupDocument(
    val format: String = AppFileCodec.BACKUP_FORMAT,
    val version: Int = AppFileCodec.BACKUP_VERSION,
    @SerialName("exported_at") val exportedAt: Long? = null,
    val app: String = "Construction Calc",
    val settings: JsonObject? = null,
    @SerialName("tool_settings") val toolSettings: Map<String, JsonObject> = emptyMap(),
    val projects: List<Project> = emptyList(),
    @SerialName("tool_data") val toolData: Map<String, JsonElement> = emptyMap(),
    val recents: List<RecentResult> = emptyList(),
) {
    val hasSettings: Boolean get() = settings != null || toolSettings.isNotEmpty()

    /** "3 projects · settings · tool data (foot-inch)" style description. */
    fun describe(toolTitle: (String) -> String = { it }): List<String> = buildList {
        if (projects.isNotEmpty()) {
            val items = projects.sumOf { it.itemCount }
            add("${projects.size} project${if (projects.size == 1) "" else "s"}, $items saved item${if (items == 1) "" else "s"}")
        }
        if (settings != null) add("App settings")
        if (toolSettings.isNotEmpty()) add("Tool settings: " + toolSettings.keys.joinToString { toolTitle(it) })
        if (toolData.isNotEmpty()) add("Tool data: " + toolData.keys.joinToString { toolTitle(it) })
        if (recents.isNotEmpty()) add("${recents.size} recent values")
    }
}

/** Any file the app can open. */
sealed interface AppFile {
    data class SingleProject(val project: Project) : AppFile
    data class Backup(val backup: BackupDocument) : AppFile
}

/** Reads and writes both file types, telling them apart by their `format` field. */
object AppFileCodec {
    const val BACKUP_FORMAT = "construction-calc/backup"
    const val BACKUP_VERSION = 1
    const val BACKUP_SUFFIX = ".backup.json"

    fun encodeBackup(backup: BackupDocument): String =
        ProjectCodec.json.encodeToString(BackupDocument.serializer(), backup)

    /** Decodes a project or backup file. Both are JSON; the `format` field says which. */
    fun decode(text: String): AppFile {
        val format = try {
            ProjectCodec.json.parseToJsonElement(text).jsonObject["format"]?.jsonPrimitive?.contentOrNull
        } catch (e: SerializationException) {
            throw ProjectFormatException("This isn't a Construction Calc file.", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("This isn't a Construction Calc file.", e)
        }
        return when (format) {
            ProjectCodec.FORMAT -> AppFile.SingleProject(ProjectCodec.decode(text))
            BACKUP_FORMAT -> try {
                AppFile.Backup(ProjectCodec.json.decodeFromString(BackupDocument.serializer(), text))
            } catch (e: SerializationException) {
                throw ProjectFormatException("This backup file is damaged.", e)
            } catch (e: IllegalArgumentException) {
                throw ProjectFormatException("This backup file is damaged.", e)
            }
            else -> throw ProjectFormatException("This isn't a Construction Calc file.")
        }
    }

    /** E.g. `Construction Calc backup 2026-11-20.backup.json`. */
    fun backupFileName(now: Long, label: String = "Construction Calc backup", zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(Instant.ofEpochMilli(now))
        return "$label $date$BACKUP_SUFFIX"
    }
}

/** How projects from a backup are combined with the ones already on the device. */
enum class ProjectMergeMode(val label: String) {
    /** Add projects that aren't here; update ones that are, if the backup's copy is newer. */
    MERGE("Merge"),
    /** Delete every project on this device, then add the backup's. */
    REPLACE("Replace all"),
}

/**
 * Plans how backup projects merge into existing ones (pure, so it's testable).
 * Returns the projects to write and counts for the summary.
 */
object ProjectMerge {
    data class Plan(val toWrite: List<Project>, val added: Int, val updated: Int, val unchanged: Int)

    fun plan(existing: List<Project>, incoming: List<Project>): Plan {
        val byId = existing.associateBy { it.id }
        var added = 0
        var updated = 0
        var unchanged = 0
        val toWrite = incoming.mapNotNull { project ->
            val mine = byId[project.id]
            when {
                mine == null -> project.also { added++ }
                project.updatedAt > mine.updatedAt -> project.also { updated++ }
                else -> null.also { unchanged++ }
            }
        }
        return Plan(toWrite, added, updated, unchanged)
    }
}
