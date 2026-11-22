package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectFormatException
import com.thedirepack.constructioncalc.core.model.ProjectMergeMode
import com.thedirepack.constructioncalc.core.model.RecentResult
import com.thedirepack.constructioncalc.core.model.ResultValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupServiceTest {
    private class MemoryProjectStore : ProjectStore {
        val map = mutableMapOf<String, Project>()
        override fun loadAll() = map.values.toList()
        override fun save(project: Project) { map[project.id] = project }
        override fun delete(projectId: String) { map.remove(projectId) }
    }

    private class FakeSettingsPort(
        var appSettings: JsonObject = buildJsonObject { put("theme", "DARK") },
        var toolSettings: Map<String, JsonObject> = mapOf("foot-inch" to buildJsonObject {
            put("hint", buildJsonObject { put("type", "boolean"); put("value", true) })
        }),
        var activeId: String? = null,
    ) : BackupSettingsPort {
        override fun exportSettings() = appSettings
        override fun exportToolSettings() = toolSettings
        override fun importSettings(json: JsonObject) { appSettings = json }
        override fun importToolSettings(toolSettings: Map<String, JsonObject>, replace: Boolean) {
            this.toolSettings = if (replace) toolSettings else this.toolSettings + toolSettings
        }
        override fun activeProjectId() = activeId
        override fun clearActiveTarget() { activeId = null }
    }

    private class FakeRecentsPort(var list: List<RecentResult> = emptyList()) : BackupRecentsPort {
        override fun current() = list
        override fun importAll(results: List<RecentResult>, replace: Boolean) {
            list = if (replace) results else results + list
        }
    }

    private class FakeToolBackup(var value: JsonElement? = null) : ToolBackup {
        override fun export(): JsonElement? = value
        override fun import(data: JsonElement, replace: Boolean) { value = data }
    }

    @Test fun createAndRestoreAllSectionsRoundTrips() {
        var now = 1_000L
        val store = MemoryProjectStore()
        val projects = ProjectRepository(store, CoroutineScope(Dispatchers.Unconfined), clock = { now++ })
        val p1 = projects.create("Site A")
        val settings = FakeSettingsPort(activeId = p1.id)
        val recent = RecentResult(ResultValue.text("k", "L", "10'"), "foot-inch", "Foot-Inch", at = 50L)
        val recents = FakeRecentsPort(listOf(recent))
        val footBackup = FakeToolBackup(JsonPrimitive("tape-history"))
        val service = BackupService(settings, projects, recents, mapOf("foot-inch" to footBackup), clock = { 5_000L })

        val doc = service.create(BackupSections.ALL)
        assertEquals(5_000L, doc.exportedAt)
        assertEquals(listOf(p1), doc.projects)
        assertEquals(listOf(recent), doc.recents)
        assertEquals(JsonPrimitive("tape-history"), doc.toolData["foot-inch"])

        // Restore onto a fresh target with REPLACE mode
        val store2 = MemoryProjectStore()
        val projects2 = ProjectRepository(store2, CoroutineScope(Dispatchers.Unconfined), clock = { now++ })
        val existing = projects2.create("Old Local")
        val settings2 = FakeSettingsPort(appSettings = buildJsonObject { put("theme", "LIGHT") }, toolSettings = emptyMap(), activeId = existing.id)
        val recents2 = FakeRecentsPort()
        val footBackup2 = FakeToolBackup()
        val service2 = BackupService(settings2, projects2, recents2, mapOf("foot-inch" to footBackup2), clock = { 6_000L })

        val report = service2.restore(doc, BackupSections.ALL, ProjectMergeMode.REPLACE)
        assertEquals(listOf(p1.id), projects2.projects.value.map { it.id })
        // Active project pointed to "Old Local", which was replaced -> cleared
        assertNull(settings2.activeId)
        assertEquals(doc.settings, settings2.appSettings)
        assertEquals(JsonPrimitive("tape-history"), footBackup2.value)
        assertEquals(listOf(recent), recents2.list)
        assertTrue(report.toString().contains("1 projects restored"))
    }

    @Test fun mergeModeAddsNewAndUpdatesNewerOnly() {
        val store = MemoryProjectStore()
        val projects = ProjectRepository(store, CoroutineScope(Dispatchers.Unconfined), clock = { 100L })
        val localOld = Project.create("A", now = 100L)
        val localNewer = Project.create("B", now = 500L)
        projects.putAll(listOf(localOld, localNewer))

        val incomingNewerA = localOld.renamed("A updated", now = 300L)
        val incomingOlderB = localNewer.renamed("B stale", now = 200L)
        val incomingC = Project.create("C", now = 50L)

        val settings = FakeSettingsPort()
        val recents = FakeRecentsPort()
        val service = BackupService(settings, projects, recents, emptyMap(), clock = { 1_000L })

        val report = service.restore(
            BackupDocument(projects = listOf(incomingNewerA, incomingOlderB, incomingC)),
            BackupSections.PROJECTS_ONLY,
            ProjectMergeMode.MERGE,
        )

        val byId = projects.projects.value.associateBy { it.id }
        assertEquals("A updated", byId[localOld.id]?.name)
        assertEquals("B", byId[localNewer.id]?.name)
        assertEquals("C", byId[incomingC.id]?.name)
        assertTrue(report.toString().contains("1 added"))
        assertTrue(report.toString().contains("1 updated"))
        assertTrue(report.toString().contains("1 already up to date"))
    }

    @Test fun restoreRejectsMalformedSettingsBeforeMutatingProjects() {
        val store = MemoryProjectStore()
        val projects = ProjectRepository(store, CoroutineScope(Dispatchers.Unconfined), clock = { 100L })
        val kept = projects.create("Keep Me")
        val service = BackupService(FakeSettingsPort(), projects, FakeRecentsPort(), emptyMap(), clock = { 100L })
        val badBackup = BackupDocument(
            settings = buildJsonObject { put("theme", buildJsonObject { }) },
            projects = listOf(Project.create("Incoming", now = 200L)),
        )

        assertThrows(ProjectFormatException::class.java) {
            service.restore(badBackup, BackupSections.ALL, ProjectMergeMode.REPLACE)
        }
        assertEquals(listOf(kept.id), projects.projects.value.map { it.id })
    }
}
