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
    constructor(
        source: PlanSheetSource,
        crop: PlanExportCrop?,
        vector: Boolean,
    ) : this(
        source = source,
        crop = crop,
        mode = if (vector) PlanSheetExportMode.VECTOR else PlanSheetExportMode.RASTER,
    )

    val vector: Boolean
        get() = mode == PlanSheetExportMode.VECTOR

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
        if (saved.size < 5) return@listSaver null
        val source = PlanSheetSource(
            saved[0] as String,
            saved[1] as String,
            saved[2] as Int,
            saved[3] as String,
            (saved[4] as String).ifEmpty { null },
        )
        val flagOrMode = saved.getOrNull(5)
        val (mode, cropOffset) = when (flagOrMode) {
            is String -> {
                val parsed = PlanSheetExportMode.entries.firstOrNull { it.name == flagOrMode } ?: PlanSheetExportMode.VECTOR
                parsed to 6
            }
            is Boolean -> {
                val parsed = if (flagOrMode) PlanSheetExportMode.VECTOR else PlanSheetExportMode.RASTER
                parsed to 6
            }
            else -> PlanSheetExportMode.VECTOR to 5
        }
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
