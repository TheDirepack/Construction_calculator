package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.ProjectFormatException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsBackupTest {
    @Test fun malformedKnownAppSettingIsRejected() {
        val settings = Json.parseToJsonElement("""{"theme":{}}""").jsonObject
        assertThrows(ProjectFormatException::class.java) {
            SettingsRepository.validateBackup(settings, emptyMap())
        }
    }

    @Test fun malformedToolSettingIsRejected() {
        val tool = Json.parseToJsonElement("""{"hint":{"type":"string","value":[]}}""").jsonObject
        assertThrows(ProjectFormatException::class.java) {
            SettingsRepository.validateBackup(null, mapOf("foot-inch" to tool))
        }
    }

    @Test fun currentSettingsAndUnknownAppKeysPassValidation() {
        val settings = Json.parseToJsonElement("""{"theme":"DARK","animations":false,"future":{}}""").jsonObject
        val tool = Json.parseToJsonElement("""{"hint":{"type":"string","value":"example"}}""").jsonObject
        SettingsRepository.validateBackup(settings, mapOf("foot-inch" to tool))
    }
}
