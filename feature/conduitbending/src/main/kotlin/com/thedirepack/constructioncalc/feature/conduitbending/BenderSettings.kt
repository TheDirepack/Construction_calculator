package com.thedirepack.constructioncalc.feature.conduitbending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.ui.components.ActionRow
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderDefaults
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderSpec
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Bendable

/** Reads and writes the user's bender values in this tool's settings store. */
internal object BenderStore {
    private fun takeUpKey(type: ConduitType, size: TradeSize) = "takeup:${type.code}:${size.label}"
    private fun gainKey(type: ConduitType, size: TradeSize) = "gain:${type.code}:${size.label}"

    /** The saved value, or the typical default when the user hasn't changed it. */
    fun load(store: ModuleSettings): BenderTable {
        val specs = buildMap {
            for (type in Bendable.types) for (size in Bendable.sizes(type)) {
                val default = BenderDefaults.spec(type, size) ?: continue
                put(
                    BenderTable.key(type, size),
                    BenderSpec(
                        takeUp = read(store, takeUpKey(type, size)) ?: default.takeUp,
                        gain = read(store, gainKey(type, size)) ?: default.gain,
                    ),
                )
            }
        }
        return BenderTable(specs)
    }

    fun setTakeUp(store: ModuleSettings, type: ConduitType, size: TradeSize, value: Rational?) =
        write(store, takeUpKey(type, size), value, BenderDefaults.spec(type, size)?.takeUp)

    fun setGain(store: ModuleSettings, type: ConduitType, size: TradeSize, value: Rational?) =
        write(store, gainKey(type, size), value, BenderDefaults.spec(type, size)?.gain)

    fun reset(store: ModuleSettings) {
        for (type in Bendable.types) for (size in Bendable.sizes(type)) {
            setTakeUp(store, type, size, null)
            setGain(store, type, size, null)
        }
    }

    private fun read(store: ModuleSettings, key: String): Rational? =
        store.getString(key, "").takeIf { it.isNotEmpty() }?.let { runCatching { Rational.parse(it) }.getOrNull() }

    /** Clearing a field (null) goes back to the typical default. */
    private fun write(store: ModuleSettings, key: String, value: Rational?, default: Rational?) =
        store.setString(key, (value ?: default)?.toString().orEmpty())
}

/** This tool's page in Settings: the bender's take-up and gain for every conduit type and size. */
@Composable
internal fun BenderSettingsPage() {
    val store = LocalAppServices.current.settings.module(ConduitBendingTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    val table = remember(version) { BenderStore.load(store) }

    Text(
        fractionText(
            "Take-up (the deduct) and gain for a 90° on a hand bender. These are typical values for common " +
                "hand benders, not your bender's: check them against the markings and instructions on your own " +
                "bender and change them here. Gain is only used for back-to-back cut lengths.",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    for (type in Bendable.types) {
        SettingsGroup(type.displayName) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (size in Bendable.sizes(type)) {
                    val spec = table.spec(type, size) ?: continue
                    MeasureField(
                        label = "${size.display} ${type.displayName} take-up",
                        value = Quantity.inches(spec.takeUp),
                        onValueChange = { BenderStore.setTakeUp(store, type, size, it?.value) },
                    )
                    MeasureField(
                        label = "${size.display} ${type.displayName} gain",
                        value = Quantity.inches(spec.gain),
                        onValueChange = { BenderStore.setGain(store, type, size, it?.value) },
                    )
                }
            }
        }
    }
    SettingsGroup("Reset") {
        ActionRow(
            "Restore typical values",
            onClick = { BenderStore.reset(store) },
            subtitle = "Replaces every take-up and gain above with the defaults. Clearing a single field does the same for that value.",
        )
    }
}
