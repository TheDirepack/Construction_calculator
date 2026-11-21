package com.thedirepack.constructioncalc.feature.pullplanner

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.electrical.FrictionPreset
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.ui.components.ChoiceRow
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.components.TextSettingRow
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullDefaults

private const val KEY_FRICTION = "default_friction"
private const val KEY_SLACK = "default_slack_ft"
private const val KEY_INCOMING = "default_incoming_lb"

/** Reads the planner's Settings page. */
internal fun ModuleSettings.pullDefaults() = PullDefaults(
    friction = FrictionPreset.entries.firstOrNull { it.name == getString(KEY_FRICTION, "") } ?: FrictionPreset.LUBRICATED,
    slackPerEndFeet = Decimals.parse(getString(KEY_SLACK, ""))?.takeIf { it >= 0 } ?: 0.0,
    incomingTension = Decimals.parse(getString(KEY_INCOMING, ""))?.takeIf { it >= 0 } ?: 0.0,
)

@Composable
internal fun rememberPullDefaults(): PullDefaults {
    val store = LocalAppServices.current.settings.module(PullPlannerTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    return remember(version) { store.pullDefaults() }
}

/** The planner's page in Settings: starting values for runs that don't have pull settings yet. */
@Composable
fun PullPlannerSettingsPage() {
    val store = LocalAppServices.current.settings.module(PullPlannerTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    val defaults = remember(version) { store.pullDefaults() }
    Column {
        SettingsGroup("New pulls start with") {
            ChoiceRow(
                title = "Friction",
                options = FrictionPreset.entries,
                selected = defaults.friction,
                onSelect = { store.setString(KEY_FRICTION, it.name) },
                optionLabel = { "${it.label.substringBefore(' ')} ${Decimals.trim(it.coefficient, 2)}" },
                subtitle = "Design guidance, not a code value. Each run can use its own value.",
            )
            TextSettingRow("Slack at each end (ft)", store.getString(KEY_SLACK, ""), KeyboardType.Decimal) { store.setString(KEY_SLACK, it) }
            TextSettingRow("Incoming tension (lb)", store.getString(KEY_INCOMING, ""), KeyboardType.Decimal) { store.setString(KEY_INCOMING, it) }
        }
    }
}
