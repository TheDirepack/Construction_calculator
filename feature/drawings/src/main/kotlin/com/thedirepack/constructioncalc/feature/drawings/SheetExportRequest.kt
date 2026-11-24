package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.runtime.saveable.listSaver
import com.thedirepack.constructioncalc.core.data.PlanExportCrop
import com.thedirepack.constructioncalc.core.data.PlanSheetExportMode
import com.thedirepack.constructioncalc.core.model.PlanSheetSource

internal data class SheetExportRequest(
    val source: PlanSheetSource,
    val crop: PlanExportCrop?,
    val mode: PlanSheetExportMode = PlanSheetExportMode.VECTOR,
) {
    val fileName: String
        get() {
            fun safe(text: String) = text.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim()
            val sheet = safe(source.sheetId).take(48)
            val revision = safe(source.revision ?: "unassigned").take(32)
            val suffix = when (mode) {
                PlanSheetExportMode.VECTOR -> if (crop == null) "annotated" else "crop"
                PlanSheetExportMode.RASTER -> if (crop == null) "snapshot" else "crop-snapshot"
            }
            return "$sheet - Rev $revision - $suffix.pdf"
        }
}

internal val SheetExportSaver = listSaver<SheetExportRequest?, Any>(
    save = { request ->
        if (request == null) emptyList()
        else {
            val source = request.source
            val crop = request.crop
            listOf(source.drawingId, source.sha256, source.page, source.sheetId, source.revision.orEmpty(), request.mode.name) +
                if (crop == null) emptyList() else listOf(crop.left, crop.top, crop.right, crop.bottom)
        }
    },
    restore = { saved ->
        if (saved.isEmpty()) return@listSaver null
        val source = PlanSheetSource(
            saved[0] as String,
            saved[1] as String,
            saved[2] as Int,
            saved[3] as String,
            (saved[4] as String).ifEmpty { null },
        )
        val hasMode = saved.getOrNull(5) is String
        val mode = if (hasMode) {
            PlanSheetExportMode.entries.firstOrNull { it.name == saved[5] } ?: PlanSheetExportMode.VECTOR
        } else {
            PlanSheetExportMode.VECTOR
        }
        val cropOffset = if (hasMode) 6 else 5
        val crop = if (saved.size >= cropOffset + 4) {
            PlanExportCrop(
                saved[cropOffset] as Float,
                saved[cropOffset + 1] as Float,
                saved[cropOffset + 2] as Float,
                saved[cropOffset + 3] as Float,
            )
        } else null
        SheetExportRequest(source, crop, mode)
    },
)
