package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.feature.conduitbending.domain.fmt
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendPlan
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendPlanner
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendType
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Bendable
import com.thedirepack.constructioncalc.feature.conduitbending.domain.FigureKind
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Severity

@Composable
fun ConduitBendingScreen(host: ToolHost) {
    val vm: ConduitBendingViewModel = toolViewModel(ConduitBendingTool.ID, host)

    val settings = rememberAppSettings()
    val store = LocalAppServices.current.settings.module(ConduitBendingTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    val table = remember(version) { BenderStore.load(store) }

    val inputs = vm.inputs
    val plan = remember(inputs, table) { BendPlanner.plan(inputs.toRequest(table)) }
    val results = remember(plan, settings) { plan.toResults(settings) }
    val summary = remember(plan, settings) { plan.summary(settings) }

    LaunchedEffect(results) {
        if (results.isNotEmpty()) host.publish(results, context = summary)
    }

    ToolScaffold(
        title = "Conduit Bending",
        host = host,
        onSave = if (!plan.hasResults) null else ({
            host.saveToProject(
                ItemDraft(ConduitBendingTool.ID, plan.type.label, ToolInputs.encode(inputs), results, summary),
            )
        }),
        actions = {
            IconButton(onClick = host::openSettings) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Bender settings")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConduitFields(inputs, vm::update)
            FormSection("Bend") {
                ChoiceField(
                    label = "Bend type",
                    options = BendType.entries,
                    selected = inputs.bendType,
                    onSelect = { type -> vm.update { it.copy(bend = type.name) } },
                    optionLabel = { it.label },
                )
            }

            BendDiagram(inputs.bendType, inputs.backToBack && inputs.bendType == BendType.STUB_UP)

            BendInputs(inputs, vm::update)

            ResultCard(
                results = plan.figures.filterIndexed { i, f -> !f.isMark || i == 0 }.map { it.toResult(settings) },
                notices = plan.issues.map { Notice(it.text, it.severity.toLevel()) },
                emptyText = "Enter the measurements above to see the marks.",
            )

            if (plan.marks.isNotEmpty()) MarkList(plan, settings)
        }
    }
}

@Composable
private fun ConduitFields(inputs: ConduitBendingInputs, update: ((ConduitBendingInputs) -> ConduitBendingInputs) -> Unit) {
    val type = inputs.conduit
    FormSection("Conduit") {
        ChoiceField(
            label = "Conduit type",
            options = Bendable.types,
            selected = type,
            onSelect = { selected ->
                update { it.copy(conduitType = selected.code, conduitSize = Bendable.sizeOrNearest(selected, it.tradeSize).label) }
            },
            optionLabel = { it.displayName },
            supportingText = type.fullName,
        )
        ChoiceField(
            label = "Trade size",
            options = Bendable.sizes(type),
            selected = inputs.tradeSize,
            onSelect = { size -> update { it.copy(conduitSize = size.label) } },
            optionLabel = { it.display },
            supportingText = "Sizes with hand-bender profiles",
        )
    }
}

@Composable
private fun BendInputs(inputs: ConduitBendingInputs, update: ((ConduitBendingInputs) -> ConduitBendingInputs) -> Unit) {
    val type = inputs.bendType
    FormSection("Measurements") {
        when (type) {
            BendType.OFFSET -> {
                MeasureField("Offset depth", inputs.offsetDepth?.toQuantity(), { q -> update { it.copy(offsetDepth = q?.stored()) } })
                AngleFields(inputs, update)
                DistanceField("Distance to the obstruction", inputs, update)
                SpaceField(inputs, update)
            }
            BendType.ROLLING_OFFSET -> {
                MeasureField("Rise (vertical)", inputs.rise?.toQuantity(), { q -> update { it.copy(rise = q?.stored()) } })
                MeasureField("Roll (sideways)", inputs.roll?.toQuantity(), { q -> update { it.copy(roll = q?.stored()) } })
                AngleFields(inputs, update)
                DistanceField("Distance to the obstruction", inputs, update)
                SpaceField(inputs, update)
            }
            BendType.SADDLE_3 -> {
                MeasureField("Obstruction height", inputs.saddleHeight?.toQuantity(), { q -> update { it.copy(saddleHeight = q?.stored()) } })
                ChoiceField(
                    label = "Center bend",
                    options = ConduitBendingInputs.SADDLE_ANGLES,
                    selected = inputs.saddleAngle,
                    onSelect = { a -> update { it.copy(saddleAngle = a) } },
                    optionLabel = { if (it == "45") "45° (outer bends 22.5°)" else "60° (outer bends 30°)" },
                )
                DistanceField("Distance to the center of the obstruction", inputs, update)
            }
            BendType.SADDLE_4 -> {
                MeasureField("Obstruction height", inputs.saddleHeight?.toQuantity(), { q -> update { it.copy(saddleHeight = q?.stored()) } })
                MeasureField("Obstruction width", inputs.saddleWidth?.toQuantity(), { q -> update { it.copy(saddleWidth = q?.stored()) } })
                AngleFields(inputs, update)
                DistanceField("Distance to the near edge of the obstruction", inputs, update)
            }
            BendType.STUB_UP -> {
                MeasureField("Stub height (to the back of the bend)", inputs.stubHeight?.toQuantity(), { q -> update { it.copy(stubHeight = q?.stored()) } })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Back-to-back 90s", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = inputs.backToBack, onCheckedChange = { on -> update { it.copy(backToBack = on) } })
                }
                if (inputs.backToBack) {
                    MeasureField(
                        "Distance between the backs of the two 90s",
                        inputs.between?.toQuantity(),
                        { q -> update { it.copy(between = q?.stored()) } },
                    )
                    MeasureField(
                        "Second stub height (optional, for cut length)",
                        inputs.secondStub?.toQuantity(),
                        { q -> update { it.copy(secondStub = q?.stored()) } },
                    )
                }
            }
            BendType.KICK -> {
                MeasureField("Kick height", inputs.kickHeight?.toQuantity(), { q -> update { it.copy(kickHeight = q?.stored()) } })
                AngleFields(inputs, update)
                DistanceField("Distance to where the kick reaches its height", inputs, update)
            }
        }
    }
}

@Composable
private fun DistanceField(label: String, inputs: ConduitBendingInputs, update: ((ConduitBendingInputs) -> ConduitBendingInputs) -> Unit) {
    MeasureField(
        label = label,
        value = inputs.distance?.toQuantity(),
        onValueChange = { q -> update { it.copy(distance = q?.stored()) } },
        supportingText = "Optional, from the end of the stick. Leave blank for marks counted from the first mark.",
    )
}

@Composable
private fun SpaceField(inputs: ConduitBendingInputs, update: ((ConduitBendingInputs) -> ConduitBendingInputs) -> Unit) {
    MeasureField(
        label = "Space available (optional)",
        value = inputs.space?.toQuantity(),
        onValueChange = { q -> update { it.copy(space = q?.stored()) } },
        supportingText = "Warns when the marks need more room than this.",
    )
}

@Composable
private fun AngleFields(inputs: ConduitBendingInputs, update: ((ConduitBendingInputs) -> ConduitBendingInputs) -> Unit) {
    ChoiceField(
        label = "Bend angle",
        options = ConduitBendingInputs.ANGLE_CHOICES,
        selected = inputs.angle,
        onSelect = { a -> update { it.copy(angle = a) } },
        optionLabel = { if (it == ConduitBendingInputs.CUSTOM) "Custom" else "$it°" },
    )
    if (inputs.angle == ConduitBendingInputs.CUSTOM) {
        NumberField(
            label = "Custom angle",
            value = inputs.customAngle,
            onValueChange = { text -> update { it.copy(customAngle = text) } },
            unit = "°",
        )
    }
}

/** The marks, in order along the stick: the cut sheet. */
@Composable
private fun MarkList(plan: BendPlan, settings: AppSettings) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (plan.marksFromEnd) "Marks from the end of the stick" else "Marks from the first mark",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            plan.marks.forEach { mark ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(mark.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        fractionText(settings.format(Quantity.inches(mark.position))),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun Severity.toLevel() = when (this) {
    Severity.INFO -> NoticeLevel.INFO
    Severity.WARNING -> NoticeLevel.WARNING
    Severity.ERROR -> NoticeLevel.ERROR
}

private fun com.thedirepack.constructioncalc.feature.conduitbending.domain.Figure.toResult(settings: AppSettings): ResultValue = when (kind) {
    FigureKind.LENGTH -> ResultValue.of(key, label, Quantity.inches(value), settings.format(Quantity.inches(value)))
    FigureKind.ANGLE -> ResultValue.number(key, label, ValueKind.ANGLE, value, formatDecimal(value.toDouble(), 2) + "°")
    FigureKind.RATIO -> ResultValue.number(key, label, ValueKind.NUMBER, value, formatDecimal(value.toDouble(), 3))
}

/** Everything the plan produced as values other tools and projects can read, headline first. */
private fun BendPlan.toResults(settings: AppSettings): List<ResultValue> {
    if (!hasResults) return emptyList()
    val markFigure = figures.any { it.isMark }
    val markResults = if (markFigure) emptyList() else marks.map {
        ResultValue.of(it.key, it.label, Quantity.inches(it.position), settings.format(Quantity.inches(it.position)))
    }
    val degrees = totalDegrees?.let {
        listOf(ResultValue.number(ProjectAnalysis.BEND_DEGREES_KEY, "Total bend", ValueKind.ANGLE, it, "${it.fmt()}°"))
    }.orEmpty()
    return figures.map { it.toResult(settings) } + markResults + degrees
}

private fun BendPlan.summary(settings: AppSettings): String {
    val head = figures.firstOrNull()?.toResult(settings) ?: return type.label
    return "${type.label}: ${head.label.lowercase()} ${head.text}"
}

