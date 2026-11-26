package com.thedirepack.constructioncalc.core.data

import android.content.SharedPreferences
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.measure.FractionRounding
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {

    @After
    fun resetGlobalProfile() {
        Code.setGlobalProfile(CodeProfile())
    }

    private fun newRepo(
        appPrefs: SharedPreferences = FakeSharedPreferences(),
        modulePrefs: MutableMap<String, FakeSharedPreferences> = mutableMapOf(),
    ): SettingsRepository = SettingsRepository(
        prefs = appPrefs,
        modulePrefs = { toolId -> modulePrefs.getOrPut(toolId) { FakeSharedPreferences() } },
    )

    @Test
    fun defaultsAndPersistsNewSettingsAndSyncsGlobalCodeProfile() {
        val prefs = FakeSharedPreferences()
        val repo = newRepo(prefs)

        assertEquals(CodeEdition.NEC_2023.name, repo.settings.value.codeEdition)
        assertEquals(CodeRegions.STANDARD_ID, repo.settings.value.codeRegion)
        assertEquals(FractionRounding.NEAREST, repo.settings.value.fractionRounding)
        assertEquals(CodeProfile(CodeEdition.NEC_2023, CodeRegions.STANDARD_ID), Code.globalProfile)

        assertEquals(Trade.ELECTRICAL, repo.settings.value.primaryTrade)
        assertEquals(false, repo.settings.value.setupDone)

        repo.setFractionRounding(FractionRounding.UP)
        repo.setCodeEdition(CodeEdition.NEC_2026.name)
        // Picking a jurisdiction selects the edition it adopted.
        repo.setCodeRegion(CodeRegions.CALIFORNIA_ID)
        assertEquals(CodeRegions.CALIFORNIA.baseEdition.name, repo.settings.value.codeEdition)
        // An edition without data can be selected; resolution flags the NEC 2023 stand-in.
        repo.setCodeEdition(CodeEdition.NEC_2026.name)
        repo.setPrimaryTrade(Trade.PLUMBING) // not available yet: ignored
        repo.setSetupDone(true)

        assertEquals(FractionRounding.UP, repo.settings.value.fractionRounding)
        assertEquals(CodeRegions.CALIFORNIA_ID, repo.settings.value.codeRegion)
        assertEquals(CodeEdition.NEC_2026.name, repo.settings.value.codeEdition)
        assertEquals(Trade.ELECTRICAL, repo.settings.value.primaryTrade)
        assertEquals(CodeProfile(CodeEdition.NEC_2026, CodeRegions.CALIFORNIA_ID), Code.globalProfile)
        assertEquals(CodeRegions.CALIFORNIA, Code.region)
        assertEquals(CodeEdition.NEC_2023, Code.edition)
        assertEquals(true, Code.current.usesFallbackEdition)

        // Reset global profile and construct a fresh repository over the same preferences to verify load/init sync.
        Code.setGlobalProfile(CodeProfile())
        val reloaded = newRepo(prefs)
        assertEquals(FractionRounding.UP, reloaded.settings.value.fractionRounding)
        assertEquals(CodeRegions.CALIFORNIA_ID, reloaded.settings.value.codeRegion)
        assertEquals(CodeEdition.NEC_2026.name, reloaded.settings.value.codeEdition)
        assertEquals(true, reloaded.settings.value.setupDone)
        assertEquals(CodeProfile(CodeEdition.NEC_2026, CodeRegions.CALIFORNIA_ID), Code.globalProfile)
    }

    @Test
    fun exportAndImportSettingsRoundTripsNewFieldsAndUpdatesCodeProfile() {
        val source = newRepo()
        source.setFractionRounding(FractionRounding.DOWN)
        source.setCodeRegion(CodeRegions.MASSACHUSETTS_ID)
        source.setCodeEdition(CodeEdition.NEC_2023.name)

        val exported = source.exportSettings()
        assertEquals("DOWN", exported[SettingsRepository.KEY_FRACTION_ROUNDING]?.jsonPrimitive?.content)
        assertEquals("NEC_2023", exported[SettingsRepository.KEY_CODE_EDITION]?.jsonPrimitive?.content)
        assertEquals(CodeRegions.MASSACHUSETTS_ID, exported[SettingsRepository.KEY_CODE_REGION]?.jsonPrimitive?.content)

        Code.setGlobalProfile(CodeProfile())
        val target = newRepo()
        target.importSettings(exported)

        assertEquals(FractionRounding.DOWN, target.settings.value.fractionRounding)
        assertEquals(CodeEdition.NEC_2023.name, target.settings.value.codeEdition)
        assertEquals(CodeRegions.MASSACHUSETTS_ID, target.settings.value.codeRegion)
        assertEquals(CodeProfile(CodeEdition.NEC_2023, CodeRegions.MASSACHUSETTS_ID), Code.globalProfile)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = key in data
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true; pending.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }
    }
}
