package com.thedirepack.constructioncalc.feature.panels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectDesign
import java.io.OutputStream

/**
 * Print-ready high-DPI PNG renderer for one panel schedule, reusing [PanelPdf]'s exact Letter
 * page layout at 3× scale (1836 × 2376 px, 216 DPI) so it prints crisply for a panel door or
 * job binder.
 */
internal object PanelPng {
    private const val SCALE = 3f

    fun write(
        out: OutputStream,
        project: Project,
        design: ProjectDesign,
        panel: Panel,
        header: List<String>,
        date: String,
        pageNumber: Int = 1,
        pageCount: Int = 1,
    ) {
        val widthPx = (PanelPdf.PAGE_W * SCALE).toInt()
        val heightPx = (PanelPdf.PAGE_H * SCALE).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.scale(SCALE, SCALE)
            PanelPdf.drawPanel(canvas, project, design, panel, header, date, pageNumber, pageCount)
            canvas.restore()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bitmap.recycle()
        }
    }
}
