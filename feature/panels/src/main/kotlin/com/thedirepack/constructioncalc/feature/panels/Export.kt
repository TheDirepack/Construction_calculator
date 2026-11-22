package com.thedirepack.constructioncalc.feature.panels

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Share actions for the project's panel schedules. A null list means every panel. */
internal class ExportActions(val pdf: (List<String>?) -> Unit, val text: (List<String>?) -> Unit)

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
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            ProjectSharing.shareFileIntent(
                                context,
                                fileName = "${ProjectCodec.safeFileName(project.name)} panel schedule.pdf",
                                mimeType = "application/pdf",
                                subject = "${project.name} panel schedule",
                                body = ScheduleText.render(project, design, panelIds, companyLines, dateText, format, units),
                            ) { out -> PanelPdf.write(out, project, design, panelIds, companyLines, dateText, units) }
                        }
                    }.onSuccess { intent ->
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            c.host.showMessage("No app available to share with")
                        }
                    }.onFailure { c.host.showMessage(it.message ?: "Couldn't make the PDF") }
                }
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
