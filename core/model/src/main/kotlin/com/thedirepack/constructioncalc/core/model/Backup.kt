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
    @SerialName("incomplete_media") val incompleteMedia: Boolean = false,
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

/** Integrity and provenance manifest written inside `.project.zip` and `.backup.zip` archives. */
@Serializable
data class BackupManifest(
    val format: String = AppFileCodec.MANIFEST_FORMAT,
    val kind: String,
    val app: String = "Construction Calc",
    val createdAt: Long,
    val createdIso: String = Instant.ofEpochMilli(createdAt).toString(),
    val documentSha256: String,
    val mediaSha256: Map<String, String> = emptyMap(),
)

/** Any file the app can open. */
sealed interface AppFile {
    data class SingleProject(val project: Project) : AppFile
    data class Backup(val backup: BackupDocument) : AppFile
}

/** Reads and writes both file types, telling them apart by their `format` field. */
object AppFileCodec {
    const val BACKUP_FORMAT = "construction-calc/backup"
    const val MANIFEST_FORMAT = "construction-calc/manifest"
    const val MANIFEST_FILE = "manifest.json"
    const val BACKUP_VERSION = 1
    const val BACKUP_SUFFIX = ".backup.json"
    const val BACKUP_ZIP_SUFFIX = ".backup.zip"

    fun encodeBackup(backup: BackupDocument): String =
        ProjectCodec.fileJson.encodeToString(BackupDocument.serializer(), backup)

    fun encodeManifest(manifest: BackupManifest): String =
        ProjectCodec.fileJson.encodeToString(BackupManifest.serializer(), manifest)

    fun decodeManifest(text: String): BackupManifest {
        val manifest = try {
            ProjectCodec.json.decodeFromString(BackupManifest.serializer(), text)
        } catch (e: SerializationException) {
            throw ProjectFormatException("This archive manifest is damaged.", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("This archive manifest is damaged.", e)
        }
        if (manifest.format != MANIFEST_FORMAT) {
            throw ProjectFormatException("This archive manifest has an unsupported format.")
        }
        return manifest
    }

    /** Decodes a project, backup JSON, or compact share text payload. */
    fun decode(text: String): AppFile {
        if (!text.trimStart().startsWith("{") && CompactShare.isCompactShare(text)) {
            return CompactShare.decode(text)
        }
        return decodeJson(text)
    }

    internal fun decodeJson(text: String): AppFile {
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

    /** E.g. `Construction Calc backup 2026-11-20.backup.zip`. */
    fun backupZipFileName(now: Long, label: String = "Construction Calc backup", zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(Instant.ofEpochMilli(now))
        return "$label $date$BACKUP_ZIP_SUFFIX"
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

    /**
     * Merges [incoming] into [existing] additively across runs, items, panels, circuits, groups,
     * and drawings without deleting any existing data.
     */
    fun mergeSingleProject(existing: Project, incoming: Project, now: Long): Project {
        val seenItemIds = existing.runs.flatMap { it.items }.mapTo(mutableSetOf()) { it.id }
        val incomingRunsById = incoming.runs.associateBy { it.id }
        val existingRunIds = existing.runs.mapTo(mutableSetOf()) { it.id }
        val mergedExistingRuns = existing.runs.map { exRun ->
            val incRun = incomingRunsById[exRun.id] ?: return@map exRun
            mergeRun(exRun, incRun, seenItemIds)
        }
        val addedRuns = incoming.runs
            .filter { it.id !in existingRunIds }
            .map { incRun ->
                val freshItems = incRun.items.filter { seenItemIds.add(it.id) }
                incRun.copy(items = freshItems)
            }

        val incomingPanelsById = incoming.panels.associateBy { it.id }
        val existingPanelIds = existing.panels.mapTo(mutableSetOf()) { it.id }
        val mergedExistingPanels = existing.panels.map { exPanel ->
            val incPanel = incomingPanelsById[exPanel.id] ?: return@map exPanel
            mergePanel(exPanel, incPanel)
        }
        val addedPanels = incoming.panels.filter { it.id !in existingPanelIds }

        val existingGroupIds = existing.groups.mapTo(mutableSetOf()) { it.id }
        val mergedGroups = existing.groups + incoming.groups.filter { it.id !in existingGroupIds }

        val mergedDrawings = mergeDrawings(existing.drawings, incoming.drawings)

        return existing.copy(
            name = existing.name.ifBlank { incoming.name },
            note = existing.note.ifBlank { incoming.note },
            createdAt = minOf(existing.createdAt, incoming.createdAt),
            updatedAt = now,
            runs = mergedExistingRuns + addedRuns,
            panels = mergedExistingPanels + addedPanels,
            groups = mergedGroups,
            drawings = mergedDrawings,
            codeEdition = existing.codeEdition ?: incoming.codeEdition,
            codeRegion = existing.codeRegion ?: incoming.codeRegion,
        )
    }

    private fun mergeRun(existing: Run, incoming: Run, seenItemIds: MutableSet<String>): Run {
        val incomingItemsById = incoming.items.associateBy { it.id }
        val updatedExisting = existing.items.map { exItem ->
            val incItem = incomingItemsById[exItem.id]
            if (incItem != null && incItem.updatedAt > exItem.updatedAt) incItem else exItem
        }
        val addedItems = incoming.items.filter { seenItemIds.add(it.id) }
        return existing.copy(
            note = existing.note.ifBlank { incoming.note },
            items = updatedExisting + addedItems,
            raceway = existing.raceway ?: incoming.raceway,
            path = existing.path.ifEmpty { incoming.path },
            pull = existing.pull ?: incoming.pull,
        )
    }

    private fun mergePanel(existing: Panel, incoming: Panel): Panel {
        val incomingCircuitsById = incoming.circuits.associateBy { it.id }
        val existingCircuitIds = existing.circuits.mapTo(mutableSetOf()) { it.id }
        val updatedExisting = existing.circuits.map { exCircuit ->
            val incCircuit = incomingCircuitsById[exCircuit.id] ?: return@map exCircuit
            exCircuit.copy(
                breakerAmps = exCircuit.breakerAmps ?: incCircuit.breakerAmps,
                description = exCircuit.description.ifBlank { incCircuit.description },
                loadVa = exCircuit.loadVa ?: incCircuit.loadVa,
                groupIds = (exCircuit.groupIds + incCircuit.groupIds).distinct(),
                runIds = (exCircuit.runIds + incCircuit.runIds).distinct(),
                length = exCircuit.length ?: incCircuit.length,
                conduitTag = exCircuit.conduitTag.ifBlank { incCircuit.conduitTag },
                conduitType = exCircuit.conduitType ?: incCircuit.conduitType,
                conduitSize = exCircuit.conduitSize ?: incCircuit.conduitSize,
                conductor = exCircuit.conductor ?: incCircuit.conductor,
                note = exCircuit.note.ifBlank { incCircuit.note },
            )
        }
        val addedCircuits = incoming.circuits.filter { it.id !in existingCircuitIds }
        return existing.copy(
            location = existing.location.ifBlank { incoming.location },
            spaces = maxOf(existing.spaces, incoming.spaces),
            busAmps = existing.busAmps ?: incoming.busAmps,
            mainAmps = existing.mainAmps ?: incoming.mainAmps,
            fedFrom = existing.fedFrom ?: incoming.fedFrom,
            note = existing.note.ifBlank { incoming.note },
            circuits = updatedExisting + addedCircuits,
        )
    }

    private fun mergeDrawings(existing: List<PlanDrawing>, incoming: List<PlanDrawing>): List<PlanDrawing> {
        val incomingById = incoming.associateBy { it.id }
        val mergedIds = mutableSetOf<String>()
        val mergedExisting = existing.map { ex ->
            val inc = incomingById[ex.id]
            if (inc != null && inc.sha256 == ex.sha256) {
                mergedIds += ex.id
                mergeDrawing(ex, inc)
            } else {
                ex
            }
        }
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
        val added = incoming.filter { it.id !in mergedIds }.map { inc ->
            if (inc.id in existingIds) inc.copy(id = newId()) else inc
        }
        return mergedExisting + added
    }

    private fun mergeDrawing(existing: PlanDrawing, incoming: PlanDrawing): PlanDrawing {
        val existingZoneIds = existing.zones.mapTo(mutableSetOf()) { it.id }
        val existingMeasurementIds = existing.measurements.mapTo(mutableSetOf()) { it.id }
        val existingMarkIds = existing.marks.mapTo(mutableSetOf()) { it.id }
        val existingPathIds = existing.paths.mapTo(mutableSetOf()) { it.id }
        val existingRouteIds = existing.routes.mapTo(mutableSetOf()) { it.id }
        val existingRouteRuns = existing.routes.mapTo(mutableSetOf()) { it.runId }

        val mergedCalibration = existing.calibration.copy(
            preset = existing.calibration.preset ?: incoming.calibration.preset,
            references = (existing.calibration.references + incoming.calibration.references).distinct(),
            pageModes = incoming.calibration.pageModes + existing.calibration.pageModes,
        )

        return existing.copy(
            calibration = mergedCalibration,
            zones = existing.zones + incoming.zones.filter { it.id !in existingZoneIds },
            measurements = existing.measurements + incoming.measurements.filter { it.id !in existingMeasurementIds },
            marks = existing.marks + incoming.marks.filter { it.id !in existingMarkIds },
            routes = existing.routes + incoming.routes.filter { it.id !in existingRouteIds && it.runId !in existingRouteRuns },
            paths = existing.paths + incoming.paths.filter { it.id !in existingPathIds },
            sheetRevisions = incoming.sheetRevisions + existing.sheetRevisions,
            scaleLocked = existing.scaleLocked || incoming.scaleLocked,
        )
    }
}
