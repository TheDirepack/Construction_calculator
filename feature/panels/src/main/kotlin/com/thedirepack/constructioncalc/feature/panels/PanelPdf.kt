package com.thedirepack.constructioncalc.feature.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectDesign
import com.thedirepack.constructioncalc.feature.panels.domain.LoadMath
import com.thedirepack.constructioncalc.feature.panels.domain.PanelFacts
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleBlock
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleLayout
import com.thedirepack.constructioncalc.feature.panels.domain.Wire
import java.io.OutputStream

/**
 * Panel schedules as a PDF (android.graphics.pdf.PdfDocument: no library, no permissions).
 * Letter size, one panel per page: company header, panel facts, the two-column schedule
 * (descriptions at the outer edges, circuit numbers beside the phase column), totals and the date.
 */
internal object PanelPdf {
    private const val PAGE_W = 612
    private const val PAGE_H = 792
    private const val MARGIN = 36f

    fun write(
        out: OutputStream,
        project: Project,
        design: ProjectDesign,
        panelIds: List<String>?,
        header: List<String>,
        date: String,
        length: (Quantity) -> String,
    ) {
        val panels = panelIds?.mapNotNull(project::panel) ?: project.panels
        val doc = PdfDocument()
        try {
            panels.forEachIndexed { index, panel ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
                drawPanel(page.canvas, project, design, panel, header, date, index + 1, panels.size)
                doc.finishPage(page)
            }
            if (panels.isEmpty()) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
                page.canvas.drawText("No panels.", MARGIN, MARGIN + 20f, paint(12f))
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun paint(size: Float, bold: Boolean = false, gray: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        color = if (gray) 0xFF555555.toInt() else 0xFF000000.toInt()
    }

    private val line = Paint().apply { color = 0xFFAAAAAA.toInt(); strokeWidth = 0.5f; style = Paint.Style.STROKE }
    private val strongLine = Paint().apply { color = 0xFF333333.toInt(); strokeWidth = 0.9f; style = Paint.Style.STROKE }

    /** [text] cut with an ellipsis to fit [width] points. */
    private fun fit(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && p.measureText(text.substring(0, end) + "…") > width) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun drawPanel(
        canvas: Canvas,
        project: Project,
        design: ProjectDesign,
        panel: Panel,
        header: List<String>,
        date: String,
        pageNumber: Int,
        pageCount: Int,
    ) {
        val width = PAGE_W - 2 * MARGIN
        var y = MARGIN
        val small = paint(8f, gray = true)
        header.forEach { y += 10f; canvas.drawText(fit(it, small, width), MARGIN, y, small) }
        if (header.isNotEmpty()) y += 6f

        y += 18f
        canvas.drawText("Panel schedule: ${panel.name}", MARGIN, y, paint(16f, bold = true))
        val sub = paint(9f, gray = true)
        y += 13f
        canvas.drawText(fit("${project.name} · $date", sub, width), MARGIN, y, sub)

        val info = paint(9f)
        val facts = buildList {
            add(PanelFacts.summary(panel))
            if (panel.location.isNotBlank()) add("Location: ${panel.location.trim()}")
            PanelFacts.fedFromText(project, panel)?.let { add("Fed from: $it") }
            if (panel.note.isNotBlank()) add("Note: ${panel.note.trim().replace('\n', ' ')}")
        }
        y += 4f
        facts.forEach { y += 12f; canvas.drawText(fit(it, info, width), MARGIN, y, info) }
        y += 10f

        // --- Table ---
        val pd = design.panels.firstOrNull { it.panel.id == panel.id }
        val warnings = (pd?.conflicts.orEmpty() + pd?.notes.orEmpty().map { it.text }).take(4)
        val totalsHeight = 16f + 12f * (3 + warnings.size) + 24f
        val schedule = ScheduleLayout.build(panel)
        val rows = schedule.rows.size
        val headerRow = 14f
        val available = PAGE_H - MARGIN - totalsHeight - y - headerRow
        val rowH = (available / rows.coerceAtLeast(1)).coerceIn(8f, 18f)
        val font = (rowH * 0.62f).coerceIn(5.5f, 8f)
        val text = paint(font)
        val bold = paint(font, bold = true)
        val head = paint(7f, bold = true)

        // Column widths, outer edge → center on the left side (mirrored on the right).
        val phaseW = 22f
        val side = (width - phaseW) / 2f
        val ckt = 22f
        val brk = 28f
        val va = 46f
        val wire = 66f
        val desc = side - ckt - brk - va - wire
        val leftX = MARGIN
        val phaseX = MARGIN + side
        val rightX = phaseX + phaseW
        // x of each column's left edge.
        val lDesc = leftX; val lWire = lDesc + desc; val lVa = lWire + wire; val lBrk = lVa + va; val lCkt = lBrk + brk
        val rCkt = rightX; val rBrk = rCkt + ckt; val rVa = rBrk + brk; val rWire = rVa + va; val rDesc = rWire + wire
        val top = y
        val body = top + headerRow

        fun label(t: String, x: Float, w: Float) = canvas.drawText(t, x + 2f, top + 10f, head)
        label("Description", lDesc, desc); label("Wire", lWire, wire); label("Load", lVa, va); label("Brkr", lBrk, brk); label("Ckt", lCkt, ckt)
        label("Ph", phaseX, phaseW)
        label("Ckt", rCkt, ckt); label("Brkr", rBrk, brk); label("Load", rVa, va); label("Wire", rWire, wire); label("Description", rDesc, desc)

        val bottom = body + rowH * rows
        canvas.drawRect(MARGIN, top, MARGIN + width, bottom, strongLine)
        canvas.drawLine(MARGIN, body, MARGIN + width, body, strongLine)
        listOf(lWire, lVa, lBrk, lCkt, phaseX, rCkt, rBrk, rVa, rWire, rDesc, rightX + side - 0f).forEach {
            canvas.drawLine(it, top, it, bottom, line)
        }
        // Phase column.
        schedule.rows.forEach { r ->
            val ry = body + rowH * r.index
            canvas.drawLine(phaseX, ry + rowH, phaseX + phaseW, ry + rowH, line)
            canvas.drawText(r.phase.name, phaseX + phaseW / 2f - text.measureText(r.phase.name) / 2f, ry + rowH * 0.72f, bold)
        }
        // Circuit numbers for every row, then each block.
        schedule.rows.forEach { r ->
            val ry = body + rowH * r.index + rowH * 0.72f
            canvas.drawText(r.leftSlot.toString(), lCkt + 2f, ry, text)
            r.rightSlot?.let { canvas.drawText(it.toString(), rCkt + 2f, ry, text) }
        }

        fun drawBlock(b: ScheduleBlock, leftSide: Boolean) {
            val by = body + rowH * b.firstRow
            val bh = rowH * b.rowSpan
            val x0 = if (leftSide) leftX else rCkt
            val x1 = if (leftSide) leftX + side else rightX + side
            canvas.drawLine(x0, by + bh, x1, by + bh, if (b.rowSpan > 1 || !b.isEmpty) strongLine else line)
            val c: Circuit = b.circuit ?: run {
                val p = paint(font, gray = true)
                val dx = if (leftSide) lDesc else rDesc
                canvas.drawText("space", dx + 2f, by + rowH * 0.72f, p)
                return
            }
            val d = design.circuits[c.id]
            val base = by + rowH * 0.72f
            val descText = when {
                c.kind == CircuitKind.SPACE -> "space" + c.description.takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty()
                c.kind == CircuitKind.SPARE -> "Spare" + c.description.takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty()
                else -> c.description
            }
            val wireText = d?.let(Wire::text).orEmpty()
            val vaText = if (c.kind == CircuitKind.LOAD) (d?.loadVa ?: c.loadVa)?.let { Wire.number(it, 0) }.orEmpty() else ""
            val brkText = if (c.kind == CircuitKind.SPACE) "" else ScheduleLayout.breakerText(c)
            val dx = if (leftSide) lDesc else rDesc
            val wx = if (leftSide) lWire else rWire
            val vx = if (leftSide) lVa else rVa
            val bx = if (leftSide) lBrk else rBrk
            canvas.drawText(fit(descText, text, desc - 4f), dx + 2f, base, text)
            canvas.drawText(fit(wireText, text, wire - 4f), wx + 2f, base, text)
            canvas.drawText(fit(vaText, text, va - 4f), vx + 2f, base, text)
            canvas.drawText(brkText, bx + 2f, base, bold)
            if (b.rowSpan > 1) {
                // A bracket down the breaker column shows which rows one breaker takes.
                val bxMid = bx + brk - 4f
                canvas.drawLine(bxMid, by + rowH * 0.9f, bxMid, by + bh - rowH * 0.2f, strongLine)
            }
        }
        schedule.left.forEach { drawBlock(it, true) }
        schedule.right.forEach { drawBlock(it, false) }

        // --- Totals ---
        y = bottom + 16f
        val t = paint(9f)
        val tb = paint(9f, bold = true)
        if (pd != null) {
            val phaseLine = pd.phaseVa.entries.joinToString("    ") { (ph, v) ->
                "Phase ${ph.name}: ${Wire.va(v)} (${Wire.amps(pd.phaseAmps.getValue(ph))})"
            }
            canvas.drawText(fit(phaseLine, t, width), MARGIN, y, t)
            y += 12f
            val imbalance = LoadMath.imbalancePercent(pd.phaseVa.values)?.let { " · imbalance ${Wire.percent(it)}" }.orEmpty()
            canvas.drawText("Total connected: ${Wire.va(pd.totalVa)}$imbalance", MARGIN, y, tb)
            y += 12f
            warnings.forEach {
                canvas.drawText(fit("Warning: $it", t, width), MARGIN, y, t)
                y += 12f
            }
        }
        val foot = paint(7f, gray = true)
        canvas.drawText(
            "Wire: conductors in the cable or conduit (phases + neutral) and G = equipment grounding conductor. Sized per NEC 2023; verify before installing.",
            MARGIN, PAGE_H - MARGIN + 6f, foot,
        )
        val pageText = "Construction Calc · $date · page $pageNumber of $pageCount"
        canvas.drawText(pageText, MARGIN + width - foot.measureText(pageText), PAGE_H - MARGIN + 16f, foot)
    }
}
