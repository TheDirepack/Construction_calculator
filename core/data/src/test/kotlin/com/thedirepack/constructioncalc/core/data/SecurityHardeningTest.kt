package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectFormatException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecurityHardeningTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun projectStoreRejectsPathTraversalIds() {
        val root = temp.newFolder("projects")
        val store = FileProjectStore(root)
        assertThrows(IllegalArgumentException::class.java) {
            store.delete("../escaped")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Project(id = "../escaped", name = "Bad", createdAt = 1L)
        }
        assertFalse(File(temp.root, "escaped.json").exists())
    }

    @Test fun backupValidationRejectsInvalidToolIds() {
        assertThrows(ProjectFormatException::class.java) {
            SettingsRepository.validateBackup(
                settings = null,
                toolSettings = mapOf("../evil" to JsonObject(emptyMap())),
            )
        }
        assertThrows(ProjectFormatException::class.java) {
            SettingsRepository.validateBackup(
                settings = null,
                toolSettings = mapOf("bad/id" to JsonObject(emptyMap())),
            )
        }
    }
}
