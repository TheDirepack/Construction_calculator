package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.runtime.saveable.listSaver
import com.thedirepack.constructioncalc.core.data.PlanExportCrop
import com.thedirepack.constructioncalc.core.model.PlanSheetSource

internal data class SheetExportRequest(val source: PlanSheetSource, val crop: PlanExportCrop?) {
    val fileName: String
        get() {
            fun safe(text: String) = text.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim()
            val sheet = safe(source.sheetId).take(48)
            val revision = safe(source.revision ?: "unassigned").take(32)
            val suffix = if (crop == null) "annotated" else "crop"
            return "$sheet - Rev $revision - $suffix.pdf"
        }
}

internal val SheetExportSaver = listSaver<SheetExportRequest?, Any>(
    save = { request ->
        if (request == null) emptyList()
        else {
            val source = request.source
            val crop = request.crop
            listOf(source.drawingId, source.sha256, source.page, source.sheetId, source.revision.orEmpty()) +
                if (crop == null) emptyList() else listOf(crop.left, crop.top, crop.right, crop.bottom)
        }
    },
    restore = { saved ->
        val source = PlanSheetSource(saved[0] as String, saved[1] as String, saved[2] as Int,
            saved[3] as String, (saved[4] as String).ifEmpty { null })
        val crop = if (saved.size == 9) {
            PlanExportCrop(saved[5] as Float, saved[6] as Float, saved[7] as Float, saved[8] as Float)
        } else null
        SheetExportRequest(source, crop)
    },
)
