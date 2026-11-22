package com.thedirepack.constructioncalc.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.core.electrical.NamedCableCodec
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import java.util.UUID

/**
 * The user's named cables, shared by Conduit Fill, the Wire Pull Planner and Reel Length. Kept in
 * `module(NamedCable.MODULE_ID)`, so a full backup carries them.
 */
class NamedCableStore(private val settings: ModuleSettings) {
    fun all(): List<NamedCable> = NamedCableCodec.decode(settings.getString(NamedCable.SETTINGS_KEY, ""))

    /** Adds [cable], replacing one with the same id or (ignoring case) the same name. */
    fun save(cable: NamedCable) {
        val kept = all().filterNot { it.id == cable.id || it.name.trim().equals(cable.name.trim(), ignoreCase = true) }
        write((kept + cable).sortedBy { it.name.lowercase() })
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(cables: List<NamedCable>) = settings.setString(NamedCable.SETTINGS_KEY, NamedCableCodec.encode(cables))
}

/** The named cable store, and its cables re-read whenever they change. */
@Composable
fun rememberNamedCables(): Pair<NamedCableStore, List<NamedCable>> {
    val settings = LocalAppServices.current.settings.module(NamedCable.MODULE_ID)
    val version by settings.changes.collectAsStateWithLifecycle()
    val store = remember(settings) { NamedCableStore(settings) }
    val cables = remember(store, version) { store.all() }
    return store to cables
}

/** "Saved cables…" and "Save as named cable…" buttons, with their dialogs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NamedCableActions(
    onPick: (NamedCable) -> Unit,
    modifier: Modifier = Modifier,
    /** What "Save as named cable" starts from (the cable currently entered); null hides the button. */
    current: NamedCable? = null,
    pickLabel: String = "Saved cables…",
) {
    val (store, cables) = rememberNamedCables()
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NamedCable?>(null) }
    var creating by remember { mutableStateOf(false) }
    FlowRow(modifier) {
        TextButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(pickLabel) }
        if (current != null) {
            TextButton(onClick = { editing = current }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save as named cable…") }
        }
    }
    if (picking) {
        NamedCablePickerDialog(
            cables = cables,
            onPick = { picking = false; onPick(it) },
            onNew = { picking = false; creating = true },
            onDelete = { store.delete(it.id) },
            onDismiss = { picking = false },
        )
    }
    if (creating || editing != null) {
        NamedCableEditorDialog(
            initial = editing,
            onSave = { cable ->
                store.save(cable)
                if (creating) onPick(cable)
                creating = false
                editing = null
            },
            onDismiss = { creating = false; editing = null },
        )
    }
}

/** A list of the saved cables to pick from, with delete and "New cable". */
@Composable
fun NamedCablePickerDialog(
    cables: List<NamedCable>,
    onPick: (NamedCable) -> Unit,
    onNew: () -> Unit,
    onDelete: (NamedCable) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Named cables") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cables.isEmpty()) {
                    Text(
                        "No saved cables yet. Save a cable's measured or catalog OD, weight and ratings once and use it in Conduit Fill, the Wire Pull Planner and Reel Length.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                cables.forEach { cable ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onPick(cable) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(cable.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    describe(cable, settings),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(cable) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${cable.name}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("New cable") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One line about a cable: OD, weight and ratings that are set. */
fun describe(cable: NamedCable, settings: com.thedirepack.constructioncalc.core.data.AppSettings): String = buildList {
    add("OD ${settings.show(Measure.SMALL_LENGTH, cable.diameterIn)}")
    cable.weightLbPerFt?.let { add(settings.show(Measure.LINEAR_WEIGHT, it)) }
    cable.maxTensionLb?.let { add("max ${settings.show(Measure.FORCE, it)}") }
    cable.maxSwbpLbPerFt?.let { add("SWBP ${settings.show(Measure.SIDEWALL_PRESSURE, it)}") }
    if (cable.note.isNotBlank()) add(cable.note)
}.joinToString(" · ")

/** Creates or edits a named cable. Values are typed in the user's units and stored in base units. */
@Composable
fun NamedCableEditorDialog(initial: NamedCable?, onSave: (NamedCable) -> Unit, onDismiss: () -> Unit) {
    fun text(v: Double?) = v?.let { Decimals.trim(it, 6) }.orEmpty()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var od by remember { mutableStateOf(text(initial?.diameterIn)) }
    // Area left blank means "round cable": π d² / 4.
    var area by remember {
        mutableStateOf(initial?.takeIf { kotlin.math.abs(it.areaSqIn - NamedCable.circularArea(it.diameterIn)) > 1e-6 }?.areaSqIn.let(::text))
    }
    var weight by remember { mutableStateOf(text(initial?.weightLbPerFt)) }
    var tension by remember { mutableStateOf(text(initial?.maxTensionLb)) }
    var swbp by remember { mutableStateOf(text(initial?.maxSwbpLbPerFt)) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }

    fun positive(s: String): Double? = Decimals.parse(s)?.takeIf { it.isFinite() && it > 0.0 }
    fun optional(s: String): Result<Double?> =
        if (s.isBlank()) Result.success(null) else positive(s)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException())

    val built: NamedCable? = run {
        val d = positive(od) ?: return@run null
        if (name.isBlank()) return@run null
        val a = optional(area).getOrElse { return@run null } ?: NamedCable.circularArea(d)
        val w = optional(weight).getOrElse { return@run null }
        val t = optional(tension).getOrElse { return@run null }
        val p = optional(swbp).getOrElse { return@run null }
        runCatching {
            NamedCable(
                id = initial?.id?.takeIf { initial.name.trim().equals(name.trim(), ignoreCase = true) } ?: UUID.randomUUID().toString(),
                name = name.trim(),
                diameterIn = d,
                areaSqIn = a,
                weightLbPerFt = w,
                maxTensionLb = t,
                maxSwbpLbPerFt = p,
                note = note.trim(),
            )
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New named cable" else "Save named cable") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                UnitField("Outside diameter", Measure.SMALL_LENGTH, od, { od = it })
                UnitField("Area (optional)", Measure.SMALL_AREA, area, { area = it }, supportingText = "Blank: a round cable of that diameter")
                UnitField("Weight (optional)", Measure.LINEAR_WEIGHT, weight, { weight = it })
                UnitField("Max pulling tension (optional)", Measure.FORCE, tension, { tension = it })
                UnitField("Max sidewall pressure (optional)", Measure.SIDEWALL_PRESSURE, swbp, { swbp = it })
                OutlinedTextField(note, { note = it.take(120) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Use the manufacturer's data sheet. These values replace the NEC table dimensions for this cable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = built != null, onClick = { built?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
