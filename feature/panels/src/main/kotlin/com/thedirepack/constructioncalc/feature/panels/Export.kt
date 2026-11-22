package com.thedirepack.constructioncalc.feature.panels

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.design.ScheduleCsv
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Share/export actions for the project's panel schedules and takeoffs. A null list means every panel. */
internal class ExportActions(
    val pdf: (List<String>?) -> Unit,
    val png: (String?) -> Unit,
    val scheduleCsv: (List<String>?) -> Unit,
    val takeoffCsv: () -> Unit,
    val text: (List<String>?) -> Unit,
)

@Composable
internal fun rememberExportActions(ctx: PanelsContext): ExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = LocalAppServices.current
    val settings = rememberAppSettings()
    val length = rememberLengthFormatter()
    val current = rememberUpdatedState(ctx)
    val header = rememberUpdatedState(settings.company.headerLines())
    val lengthNow = rememberUpdatedState(length)
    val unitsNow = rememberUpdatedState(settings.unitSystem)

    return remember(context, services) {
        fun date() = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(services.clock()))

        fun shareFile(
            fileName: String,
            mimeType: String,
            subject: String,
            body: String,
            errorFallback: String,
            writer: (java.io.OutputStream) -> Unit,
        ) {
            val c = current.value
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        ProjectSharing.shareFileIntent(context, fileName, mimeType, subject, body, writer)
                    }
                }.onSuccess { intent ->
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        c.host.showMessage("No app available to share with")
                    }
                }.onFailure { c.host.showMessage(it.message ?: errorFallback) }
            }
        }

        ExportActions(
            pdf = { panelIds ->
                val c = current.value
                if (c.project.panels.isEmpty()) {
                    c.host.showMessage("Add a panel first")
                    return@ExportActions
                }
                val project = c.project
                val design = c.design
                val dateText = date()
                val companyLines = header.value
                val format = lengthNow.value
                val units = unitsNow.value
                shareFile(
                    fileName = "${ProjectCodec.safeFileName(project.name)} panel schedule.pdf",
                    mimeType = "application/pdf",
                    subject = "${project.name} panel schedule",
                    body = ScheduleText.render(project, design, panelIds, companyLines, dateText, format, units),
                    errorFallback = "Couldn't make the PDF",
                ) { out -> PanelPdf.write(out, project, design, panelIds, companyLines, dateText, units) }
            },
            png = { panelId ->
                val c = current.value
                val panel = panelId?.let(c.project::panel) ?: c.project.panels.firstOrNull()
                if (panel == null) {
                    c.host.showMessage("Add a panel first")
                    return@ExportActions
                }
                val project = c.project
                val design = c.design
                val dateText = date()
                val companyLines = header.value
                val format = lengthNow.value
                val units = unitsNow.value
                shareFile(
                    fileName = "${ProjectCodec.safeFileName(project.name)} ${ProjectCodec.safeFileName(panel.name)} schedule.png",
                    mimeType = "image/png",
                    subject = "${project.name} · ${panel.name} panel schedule",
                    body = ScheduleText.render(project, design, listOf(panel.id), companyLines, dateText, format, units),
                    errorFallback = "Couldn't render the PNG",
                ) { out -> PanelPng.write(out, project, design, panel, companyLines, dateText) }
            },
            scheduleCsv = { panelIds ->
                val c = current.value
                if (c.project.panels.isEmpty()) {
                    c.host.showMessage("Add a panel first")
                    return@ExportActions
                }
                val project = c.project
                val design = c.design
                val units = unitsNow.value
                val csv = ScheduleCsv.panelSchedule(project, design, panelIds, units)
                shareFile(
                    fileName = "${ProjectCodec.safeFileName(project.name)} panel schedule.csv",
                    mimeType = "text/csv",
                    subject = "${project.name} panel schedule (CSV)",
                    body = "Panel schedule CSV for ${project.name}.",
                    errorFallback = "Couldn't export CSV",
                ) { out -> out.write(csv.toByteArray(Charsets.UTF_8)) }
            },
            takeoffCsv = {
                val c = current.value
                val project = c.project
                val design = c.design
                val units = unitsNow.value
                val csv = ScheduleCsv.materialTakeoff(project, design, units)
                shareFile(
                    fileName = "${ProjectCodec.safeFileName(project.name)} material takeoff.csv",
                    mimeType = "text/csv",
                    subject = "${project.name} material takeoff (CSV)",
                    body = "Material takeoff CSV for ${project.name}.",
                    errorFallback = "Couldn't export takeoff CSV",
                ) { out -> out.write(csv.toByteArray(Charsets.UTF_8)) }
            },
            text = { panelIds ->
                val c = current.value
                if (c.project.panels.isEmpty()) {
                    c.host.showMessage("Add a panel first")
                    return@ExportActions
                }
                c.host.shareText(
                    "${c.project.name} panel schedule",
                    ScheduleText.render(c.project, c.design, panelIds, header.value, date(), lengthNow.value, unitsNow.value),
                )
            },
        )
    }
}
