package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.panels.domain.BuiltInPanelTemplates
import com.thedirepack.constructioncalc.feature.panels.domain.PanelExportLayout
import com.thedirepack.constructioncalc.feature.panels.domain.PanelTemplateCategory
import com.thedirepack.constructioncalc.feature.panels.domain.PanelTemplatesCodec
import com.thedirepack.constructioncalc.feature.panels.domain.SavedPanelTemplate

/** Persists custom saved panel templates and default export layout in the Panels module settings. */
internal object PanelTemplateStore {
    private const val KEY_TEMPLATES = "custom_panel_templates"
    private const val KEY_DEFAULT_LAYOUT = "default_export_layout"

    fun customTemplates(store: ModuleSettings): List<SavedPanelTemplate> =
        PanelTemplatesCodec.decode(store.getString(KEY_TEMPLATES, ""))
            .map {
                it.copy(
                    category = PanelTemplateCategory.CUSTOM,
                    builtIn = false,
                    spaces = Panel.snapSpacesToBus(it.spaces, it.system.phases),
                )
            }

    fun allSavedTemplates(store: ModuleSettings): List<SavedPanelTemplate> =
        BuiltInPanelTemplates.all + customTemplates(store)

    fun saveFromPanel(store: ModuleSettings, panel: Panel, templateName: String): SavedPanelTemplate {
        val template = SavedPanelTemplate.fromPanel(panel, templateName)
        val updated = customTemplates(store) + template
        store.setString(KEY_TEMPLATES, PanelTemplatesCodec.encode(updated))
        return template
    }

    fun deleteTemplate(store: ModuleSettings, templateId: String) {
        val updated = customTemplates(store).filterNot { it.id == templateId }
        store.setString(KEY_TEMPLATES, PanelTemplatesCodec.encode(updated))
    }

    fun defaultLayout(store: ModuleSettings): PanelExportLayout =
        PanelExportLayout.fromName(store.getString(KEY_DEFAULT_LAYOUT, ""))

    fun setDefaultLayout(store: ModuleSettings, layout: PanelExportLayout) {
        store.setString(KEY_DEFAULT_LAYOUT, layout.name)
    }
}

@Composable
private inline fun <T> rememberPanelSetting(crossinline read: (ModuleSettings) -> T): T {
    val store = LocalAppServices.current.settings.module(PanelsTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    return remember(version) { read(store) }
}

@Composable
internal fun rememberSavedPanelTemplates(): List<SavedPanelTemplate> =
    rememberPanelSetting { PanelTemplateStore.allSavedTemplates(it) }

@Composable
internal fun rememberCustomPanelTemplates(): List<SavedPanelTemplate> =
    rememberPanelSetting { PanelTemplateStore.customTemplates(it) }

@Composable
internal fun rememberDefaultExportLayout(): PanelExportLayout =
    rememberPanelSetting { PanelTemplateStore.defaultLayout(it) }
