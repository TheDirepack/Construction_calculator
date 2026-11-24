package com.thedirepack.constructioncalc.feature.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.PanelDesign
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.ProjectDesign
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.feature.panels.domain.LoadMath
import com.thedirepack.constructioncalc.feature.panels.domain.PanelExportLayout
import com.thedirepack.constructioncalc.feature.panels.domain.PanelFacts
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleBlock
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleLayout
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleText
import java.io.OutputStream
import java.util.Locale

/**
 * Panel schedules as a PDF (android.graphics.pdf.PdfDocument: no library, no permissions).
 * Letter size, one panel per page, supporting [PanelExportLayout] variants:
 * standard two-column branch schedule, large-print panel door directory, or single-column
 * distribution feeder schedule.
 */
internal object PanelPdf {
    internal const val PAGE_W = 612
    internal const val PAGE_H = 792
    private const val MARGIN = 36f

    fun write(
        out: OutputStream,
        project: Project,
        design: ProjectDesign,
        panelIds: List<String>?,
        header: List<String>,
        date: String,
        units: UnitSystem = UnitSystem.IMPERIAL,
        layout: PanelExportLayout = PanelExportLayout.BRANCH_STANDARD,
    ) {
        val panels = panelIds?.mapNotNull(project::panel) ?: project.panels
        // Panels with more warnings than fit on their page get the full list on the extra pages.
        val overflow = panels.mapNotNull { p -> warningsOf(design, p).takeIf { it.size > MAX_PAGE_WARNINGS }?.let { p to it } }
        val warningLines = if (overflow.isEmpty()) emptyList() else buildList {
            add("WARNINGS")
            overflow.forEach { (p, list) -> add("  ${p.name}"); list.forEach { add("    $it") } }
            add("")
        }
        val extras = warningLines + ScheduleText.projectExtras(project, design, units)
        val lineHeight = 14f
        val linesPerExtraPage = ((PAGE_H - 2 * MARGIN - 56f) / lineHeight).toInt().coerceAtLeast(1)
        val extraChunks = if (extras.isEmpty()) emptyList() else extras.chunked(linesPerExtraPage)
        val panelPages = maxOf(panels.size, 1)
        val totalPages = panelPages + extraChunks.size
        val width = PAGE_W - 2 * MARGIN

        val doc = PdfDocument()
        try {
            panels.forEachIndexed { index, panel ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
                drawPanel(page.canvas, project, design, panel, header, date, index + 1, totalPages, layout = layout)
                doc.finishPage(page)
            }
            if (panels.isEmpty()) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
                page.canvas.drawText("No panels.", MARGIN, MARGIN + 20f, paint(12f))
                drawPageFooter(page.canvas, date, 1, totalPages, width)
                doc.finishPage(page)
            }
            // Project-wide pages: pulls and the material takeoff, as plain lines.
            if (extraChunks.isNotEmpty()) {
                var pageNumber = panelPages
                val body = paint(10f)
                val bold = paint(11f, bold = true)
                extraChunks.forEach { chunk ->
                    pageNumber++
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
                    var y = MARGIN + 14f
                    page.canvas.drawText(fit("${project.name} · $date", bold, width), MARGIN, y, bold)
                    y += 24f
                    chunk.forEach { text ->
                        val heading = text.isNotEmpty() && !text.startsWith(" ")
                        page.canvas.drawText(fit(text, if (heading) bold else body, width), MARGIN, y, if (heading) bold else body)
                        y += lineHeight
                    }
                    drawPageFooter(page.canvas, date, pageNumber, totalPages, width)
                    doc.finishPage(page)
                }
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun drawPageFooter(canvas: Canvas, date: String, pageNumber: Int, pageCount: Int, width: Float) {
        val foot = paint(7f, gray = true)
        val pageText = "Construction Calc · $date · page $pageNumber of $pageCount"
        canvas.drawText(pageText, MARGIN + width - foot.measureText(pageText), PAGE_H - MARGIN + 16f, foot)
    }

    private fun paint(size: Float, bold: Boolean = false, gray: Boolean = false, colorOverride: Int? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        color = colorOverride ?: if (gray) 0xFF555555.toInt() else 0xFF000000.toInt()
    }

    private val line = Paint().apply { color = 0xFFAAAAAA.toInt(); strokeWidth = 0.5f; style = Paint.Style.STROKE }
    private val strongLine = Paint().apply { color = 0xFF333333.toInt(); strokeWidth = 0.9f; style = Paint.Style.STROKE }
    private val headerFill = Paint().apply { color = 0xFFE8EDF2.toInt(); style = Paint.Style.FILL }
    private val altRowFill = Paint().apply { color = 0xFFF3F5F8.toInt(); style = Paint.Style.FILL }
    private val solidWhiteFill = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }

    /** [text] cut with an ellipsis to fit [width] points. */
    private fun fit(text: String, p: Paint, width: Float): String =
        TextUtils.ellipsize(text, TextPaint(p), width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()

    /** How many warnings fit under a panel's totals; the rest are counted and listed elsewhere. */
    private const val MAX_PAGE_WARNINGS = 4

    /** Every conflict and note for [panel], in schedule order. */
    internal fun warningsOf(design: ProjectDesign, panel: Panel): List<String> {
        val pd = design.panels.firstOrNull { it.panel.id == panel.id }
        return pd?.conflicts.orEmpty() + pd?.notes.orEmpty().map { it.text }
    }

    internal fun drawPanel(
        canvas: Canvas,
        project: Project,
        design: ProjectDesign,
        panel: Panel,
        header: List<String>,
        date: String,
        pageNumber: Int,
        pageCount: Int,
        overflowHint: String = "see the warnings page",
        layout: PanelExportLayout = PanelExportLayout.BRANCH_STANDARD,
    ) {
        when (layout) {
            PanelExportLayout.BRANCH_STANDARD -> drawBranchStandard(
                canvas, project, design, panel, header, date, pageNumber, pageCount, overflowHint,
            )
            PanelExportLayout.PANEL_DOOR -> drawPanelDoor(
                canvas, project, design, panel, header, date, pageNumber, pageCount, overflowHint,
            )
            PanelExportLayout.DISTRIBUTION_SINGLE -> drawDistributionSingle(
                canvas, project, design, panel, header, date, pageNumber, pageCount, overflowHint,
            )
        }
    }

    private fun drawPanelHeader(
        canvas: Canvas,
        project: Project,
        panel: Panel,
        header: List<String>,
        date: String,
        width: Float,
        title: String,
    ): Float {
        var y = MARGIN
        val small = paint(8f, gray = true)
        header.forEach { y += 10f; canvas.drawText(fit(it, small, width), MARGIN, y, small) }
        if (header.isNotEmpty()) y += 6f

        y += 18f
        canvas.drawText(fit(title, paint(16f, bold = true), width), MARGIN, y, paint(16f, bold = true))
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
        return y
    }

    private fun pageWarnings(design: ProjectDesign, panel: Panel, overflowHint: String): List<String> {
        val all = warningsOf(design, panel)
        return if (all.size <= MAX_PAGE_WARNINGS) {
            all
        } else {
            all.take(MAX_PAGE_WARNINGS - 1) + "${all.size - MAX_PAGE_WARNINGS + 1} more — $overflowHint"
        }
    }

    private fun drawBranchStandard(
        canvas: Canvas,
        project: Project,
        design: ProjectDesign,
        panel: Panel,
        header: List<String>,
        date: String,
        pageNumber: Int,
        pageCount: Int,
        overflowHint: String,
    ) {
        val width = PAGE_W - 2 * MARGIN
        var y = drawPanelHeader(canvas, project, panel, header, date, width, "Panel schedule: ${panel.name}")

        // --- Table ---
        val pd = design.panels.firstOrNull { it.panel.id == panel.id }
        val warnings = pageWarnings(design, panel, overflowHint)
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

        // Column widths, outer edge -> center on the left side (mirrored on the right).
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

        fun label(t: String, x: Float) = canvas.drawText(t, x + 2f, top + 10f, head)
        label("Description", lDesc); label("Wire", lWire); label("Load", lVa); label("Brkr", lBrk); label("Ckt", lCkt)
        label("Ph", phaseX)
        label("Ckt", rCkt); label("Brkr", rBrk); label("Load", rVa); label("Wire", rWire); label("Description", rDesc)

        val bottom = body + rowH * rows
        canvas.drawRect(MARGIN, top, MARGIN + width, bottom, strongLine)
        canvas.drawLine(MARGIN, body, MARGIN + width, body, strongLine)
        listOf(lWire, lVa, lBrk, lCkt, phaseX, rCkt, rBrk, rVa, rWire, rDesc, rightX + side).forEach {
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
            val descText = circuitDescription(c)
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

        drawTotalsAndFooter(canvas, pd, warnings, bottom, width, date, pageNumber, pageCount)
    }

    private fun drawPanelDoor(
        canvas: Canvas,
        project: Project,
        design: ProjectDesign,
        panel: Panel,
        header: List<String>,
        date: String,
        pageNumber: Int,
        pageCount: Int,
        overflowHint: String,
    ) {
        val width = PAGE_W - 2 * MARGIN
        val y = drawPanelHeader(canvas, project, panel, header, date, width, "Panel directory: ${panel.name}")

        val pd = design.panels.firstOrNull { it.panel.id == panel.id }
        val warnings = pageWarnings(design, panel, overflowHint)
        val totalsHeight = 16f + 12f * (3 + warnings.size) + 24f
        val schedule = ScheduleLayout.build(panel)
        val rows = schedule.rows.size
        val headerRow = 16f
        val available = PAGE_H - MARGIN - totalsHeight - y - headerRow
        val rowH = (available / rows.coerceAtLeast(1)).coerceIn(10f, 26f)

        val numFont = (rowH * 0.48f).coerceIn(6.2f, 9.5f)
        val descFont = (rowH * 0.50f).coerceIn(6.8f, 11.5f)
        val hintFont = (rowH * 0.33f).coerceIn(5.5f, 7.5f)
        val numPaint = paint(numFont)
        val brkPaint = paint(numFont, bold = true)
        val descBold = paint(descFont, bold = true)
        val descMuted = paint(descFont, gray = true)
        val sparePaint = paint(descFont, bold = true, gray = true)
        val hintPaint = paint(hintFont, gray = true)
        val head = paint(7.5f, bold = true)

        // Wide outer description columns with breaker and circuit number next to center phase bus.
        val phaseW = 22f
        val side = (width - phaseW) / 2f
        val ckt = 24f
        val brk = 34f
        val desc = side - ckt - brk
        val leftX = MARGIN
        val phaseX = MARGIN + side
        val rightX = phaseX + phaseW
        val lDesc = leftX; val lBrk = lDesc + desc; val lCkt = lBrk + brk
        val rCkt = rightX; val rBrk = rCkt + ckt; val rDesc = rBrk + brk

        val top = y
        val body = top + headerRow
        val bottom = body + rowH * rows

        // Header fill and alternating subtle row shading before borders and text.
        canvas.drawRect(MARGIN, top, MARGIN + width, body, headerFill)
        schedule.rows.forEach { r ->
            if (r.index % 2 == 1) {
                val ry = body + rowH * r.index
                canvas.drawRect(MARGIN, ry, MARGIN + width, ry + rowH, altRowFill)
            }
        }
        // Keep spanned multi-pole breaker cells clean white so shading doesn't bisect them.
        fun clearMultiPole(b: ScheduleBlock, leftSide: Boolean) {
            if (b.rowSpan > 1) {
                val by = body + rowH * b.firstRow
                val x0 = if (leftSide) leftX else rCkt
                val x1 = if (leftSide) leftX + side else rightX + side
                canvas.drawRect(x0, by, x1, by + rowH * b.rowSpan, solidWhiteFill)
            }
        }
        schedule.left.forEach { clearMultiPole(it, true) }
        schedule.right.forEach { clearMultiPole(it, false) }

        fun label(t: String, x: Float, colW: Float, center: Boolean = false) {
            val tx = if (center) x + (colW - head.measureText(t)) / 2f else x + 4f
            canvas.drawText(t, tx, top + 11f, head)
        }
        label("Description", lDesc, desc)
        label("Brkr", lBrk, brk, center = true)
        label("Ckt", lCkt, ckt, center = true)
        label("Ph", phaseX, phaseW, center = true)
        label("Ckt", rCkt, ckt, center = true)
        label("Brkr", rBrk, brk, center = true)
        label("Description", rDesc, desc)

        canvas.drawRect(MARGIN, top, MARGIN + width, bottom, strongLine)
        canvas.drawLine(MARGIN, body, MARGIN + width, body, strongLine)
        listOf(lBrk, lCkt, phaseX, rCkt, rBrk, rDesc).forEach {
            canvas.drawLine(it, top, it, bottom, line)
        }

        // Phase and slot numbers.
        schedule.rows.forEach { r ->
            val ry = body + rowH * r.index
            val baseline = ry + rowH * 0.5f + numFont * 0.35f
            canvas.drawLine(phaseX, ry + rowH, phaseX + phaseW, ry + rowH, line)
            canvas.drawText(
                r.phase.name,
                phaseX + (phaseW - brkPaint.measureText(r.phase.name)) / 2f,
                baseline,
                brkPaint,
            )
            val leftNum = r.leftSlot.toString()
            canvas.drawText(leftNum, lCkt + (ckt - numPaint.measureText(leftNum)) / 2f, baseline, numPaint)
            r.rightSlot?.let { slot ->
                val rightNum = slot.toString()
                canvas.drawText(rightNum, rCkt + (ckt - numPaint.measureText(rightNum)) / 2f, baseline, numPaint)
            }
        }

        fun drawDoorBlock(b: ScheduleBlock, leftSide: Boolean) {
            val by = body + rowH * b.firstRow
            val bh = rowH * b.rowSpan
            val x0 = if (leftSide) leftX else rCkt
            val x1 = if (leftSide) leftX + side else rightX + side
            canvas.drawLine(x0, by + bh, x1, by + bh, if (b.rowSpan > 1 || !b.isEmpty) strongLine else line)

            val dx = if (leftSide) lDesc else rDesc
            val bx = if (leftSide) lBrk else rBrk
            val centerBaseline = by + bh * 0.5f + descFont * 0.35f
            val c = b.circuit ?: run {
                canvas.drawText("space", dx + 4f, centerBaseline, descMuted)
                return
            }
            if (c.kind == CircuitKind.SPACE) {
                canvas.drawText(fit(circuitDescription(c), descMuted, desc - 8f), dx + 4f, centerBaseline, descMuted)
                return
            }

            val brkText = ScheduleLayout.breakerText(c)
            val brkBase = by + bh * 0.5f + numFont * 0.35f
            canvas.drawText(brkText, bx + (brk - brkPaint.measureText(brkText)) / 2f, brkBase, brkPaint)
            if (b.rowSpan > 1) {
                val bxMid = bx + brk - 4f
                canvas.drawLine(bxMid, by + rowH * 0.85f, bxMid, by + bh - rowH * 0.2f, strongLine)
            }

            if (c.kind == CircuitKind.SPARE) {
                canvas.drawText(fit(circuitDescription(c), sparePaint, desc - 8f), dx + 4f, centerBaseline, sparePaint)
                return
            }

            val mainText = c.description.trim().ifEmpty { "(no description)" }
            val d = design.circuits[c.id]
            val wireText = d?.let(Wire::text).orEmpty()
            val vaText = (d?.loadVa ?: c.loadVa)?.let { Wire.number(it, 0) + " VA" }.orEmpty()
            val hint = listOfNotNull(vaText.takeIf { it.isNotEmpty() }, wireText.takeIf { it.isNotEmpty() }).joinToString(" · ")

            if (hint.isNotEmpty() && bh >= 18f) {
                val blockHeight = descFont + hintFont + 2.5f
                val topPad = ((bh - blockHeight) / 2f).coerceAtLeast(1f)
                val line1Base = by + topPad + descFont * 0.85f
                val line2Base = line1Base + hintFont + 2.5f
                canvas.drawText(fit(mainText, descBold, desc - 8f), dx + 4f, line1Base, descBold)
                canvas.drawText(fit(hint, hintPaint, desc - 8f), dx + 4f, line2Base, hintPaint)
            } else {
                canvas.drawText(fit(mainText, descBold, desc - 8f), dx + 4f, centerBaseline, descBold)
            }
        }

        schedule.left.forEach { drawDoorBlock(it, true) }
        schedule.right.forEach { drawDoorBlock(it, false) }

        drawTotalsAndFooter(canvas, pd, warnings, bottom, width, date, pageNumber, pageCount)
    }

    private data class DistRow(
        val slots: String,
        val phase: String,
        val breaker: String,
        val description: String,
        val wire: String,
        val loadVa: String,
        val status: String,
        val muted: Boolean = false,
        val warning: Boolean = false,
    )

    private fun drawDistributionSingle(
        canvas: Canvas,
        project: Project,
        design: ProjectDesign,
        panel: Panel,
        header: List<String>,
        date: String,
        pageNumber: Int,
        pageCount: Int,
        overflowHint: String,
    ) {
        val width = PAGE_W - 2 * MARGIN
        val y = drawPanelHeader(canvas, project, panel, header, date, width, "Distribution schedule: ${panel.name}")

        val pd = design.panels.firstOrNull { it.panel.id == panel.id }
        val warnings = pageWarnings(design, panel, overflowHint)
        val totalsHeight = 16f + 12f * (4 + warnings.size) + 24f

        val schedule = ScheduleLayout.build(panel)
        val unplacedIds = schedule.unplaced.map { it.id }.toSet()
        val distRows = buildList {
            panel.circuits.sortedBy { it.slot }.forEach { c ->
                val d = design.circuits[c.id]
                val slotsText = c.slots.joinToString(",")
                val phaseText = PanelFacts.phaseText(c, panel.system.phases)
                val brkText = if (c.kind == CircuitKind.SPACE) "space" else ScheduleLayout.breakerText(c)
                val descText = when {
                    c.kind == CircuitKind.SPACE -> "Space" + c.description.takeIf { it.isNotBlank() }?.let { " – ${it.trim()}" }.orEmpty()
                    c.kind == CircuitKind.SPARE -> "Spare" + c.description.takeIf { it.isNotBlank() }?.let { " – ${it.trim()}" }.orEmpty()
                    else -> c.description.trim().ifEmpty { "(no description)" }
                }
                val wireText = if (c.kind == CircuitKind.LOAD) d?.let(Wire::text).orEmpty() else ""
                val vaText = if (c.kind == CircuitKind.LOAD) {
                    (d?.loadVa ?: c.loadVa)?.let { va ->
                        String.format(Locale.US, "%,.0f", va) + if (d?.loadFromFedPanels == true) " (fed)" else ""
                    }.orEmpty()
                } else {
                    ""
                }
                val warningText = when {
                    c.id in unplacedIds -> "Unplaced (past ${panel.spaces} / overlap)"
                    else -> d?.notes?.firstOrNull { it.isWarning }?.text
                }
                val statusText = when {
                    warningText != null -> warningText
                    c.kind == CircuitKind.SPACE -> c.note.trim().ifEmpty { "Space" }
                    c.kind == CircuitKind.SPARE -> c.note.trim().ifEmpty { "Spare" }
                    else -> listOfNotNull(
                        d?.amps?.let(Wire::amps),
                        if (c.continuous) "Cont." else null,
                        d?.voltageDropPercent?.let { "VD ${Wire.percent(it)}" },
                        c.note.trim().takeIf { it.isNotEmpty() },
                    ).joinToString(" · ").ifEmpty { if (d?.loadFromFedPanels == true) "Feeder" else "" }
                }
                add(
                    DistRow(
                        slots = slotsText,
                        phase = phaseText,
                        breaker = brkText,
                        description = descText,
                        wire = wireText,
                        loadVa = vaText,
                        status = statusText,
                        muted = c.kind == CircuitKind.SPACE,
                        warning = warningText != null,
                    ),
                )
            }
            val occupied = PanelLayout.occupancy(panel).keys
            val openSlots = (1..panel.spaces).filter { it !in occupied }
            if (openSlots.isNotEmpty()) {
                add(
                    DistRow(
                        slots = formatSlotRanges(openSlots),
                        phase = "—",
                        breaker = "space",
                        description = if (panel.circuits.isEmpty()) "No circuits assigned" else "Available spaces",
                        wire = "",
                        loadVa = "",
                        status = "${openSlots.size} open",
                        muted = true,
                    ),
                )
            }
        }

        val rowCount = distRows.size.coerceAtLeast(1)
        val headerRow = 16f
        val available = PAGE_H - MARGIN - totalsHeight - y - headerRow
        val rowH = (available / rowCount).coerceIn(8.5f, 20f)
        val font = (rowH * 0.54f).coerceIn(5.8f, 8.5f)
        val text = paint(font)
        val bold = paint(font, bold = true)
        val mutedPaint = paint(font, gray = true)
        val warnPaint = paint(font, colorOverride = 0xFFB00020.toInt())
        val head = paint(7.5f, bold = true)

        // Single-column feeder/distribution table: Slots | Ph | Brkr | Description | Wire | Load VA | Notes/Status
        val wSlots = 42f
        val wPh = 36f
        val wBrk = 40f
        val wDesc = 156f
        val wWire = 82f
        val wVa = 58f
        val wStatus = width - wSlots - wPh - wBrk - wDesc - wWire - wVa

        val xSlots = MARGIN
        val xPh = xSlots + wSlots
        val xBrk = xPh + wPh
        val xDesc = xBrk + wBrk
        val xWire = xDesc + wDesc
        val xVa = xWire + wWire
        val xStatus = xVa + wVa

        val top = y
        val body = top + headerRow
        val bottom = body + rowH * rowCount

        canvas.drawRect(MARGIN, top, MARGIN + width, body, headerFill)
        distRows.forEachIndexed { idx, _ ->
            if (idx % 2 == 1) {
                val ry = body + rowH * idx
                canvas.drawRect(MARGIN, ry, MARGIN + width, ry + rowH, altRowFill)
            }
        }

        fun colHead(t: String, x: Float, w: Float, rightAlign: Boolean = false) {
            val tx = if (rightAlign) x + w - 4f - head.measureText(t) else x + 3f
            canvas.drawText(t, tx, top + 11f, head)
        }
        colHead("Slots", xSlots, wSlots)
        colHead("Ph", xPh, wPh)
        colHead("Brkr", xBrk, wBrk)
        colHead("Description", xDesc, wDesc)
        colHead("Wire", xWire, wWire)
        colHead("Load VA", xVa, wVa, rightAlign = true)
        colHead("Notes/Status", xStatus, wStatus)

        canvas.drawRect(MARGIN, top, MARGIN + width, bottom, strongLine)
        canvas.drawLine(MARGIN, body, MARGIN + width, body, strongLine)
        listOf(xPh, xBrk, xDesc, xWire, xVa, xStatus).forEach {
            canvas.drawLine(it, top, it, bottom, line)
        }

        distRows.forEachIndexed { idx, row ->
            val ry = body + rowH * idx
            val base = ry + rowH * 0.5f + font * 0.35f
            if (idx < distRows.lastIndex) {
                canvas.drawLine(MARGIN, ry + rowH, MARGIN + width, ry + rowH, line)
            }
            val rowPaint = if (row.muted) mutedPaint else text
            val brkPaint = if (row.muted) mutedPaint else bold
            val statusPaint = when {
                row.warning -> warnPaint
                row.muted -> mutedPaint
                else -> text
            }
            canvas.drawText(fit(row.slots, rowPaint, wSlots - 6f), xSlots + 3f, base, rowPaint)
            canvas.drawText(fit(row.phase, rowPaint, wPh - 6f), xPh + 3f, base, rowPaint)
            canvas.drawText(fit(row.breaker, brkPaint, wBrk - 6f), xBrk + 3f, base, brkPaint)
            canvas.drawText(fit(row.description, rowPaint, wDesc - 6f), xDesc + 3f, base, rowPaint)
            canvas.drawText(fit(row.wire, rowPaint, wWire - 6f), xWire + 3f, base, rowPaint)
            val vaFit = fit(row.loadVa, rowPaint, wVa - 6f)
            if (vaFit.isNotEmpty()) {
                canvas.drawText(vaFit, xVa + wVa - 3f - rowPaint.measureText(vaFit), base, rowPaint)
            }
            canvas.drawText(fit(row.status, statusPaint, wStatus - 6f), xStatus + 3f, base, statusPaint)
        }

        val usedPositions = panel.circuits.sumOf { if (it.kind == CircuitKind.SPACE) 0 else it.poles }
        val peakAmps = pd?.phaseAmps?.values?.maxOrNull() ?: 0.0
        val extraSummary = buildList {
            add("Positions: $usedPositions of ${panel.spaces} used")
            panel.busAmps?.let { add("Bus: $it A") }
            add("Main: ${panel.mainAmps?.let { "$it A" } ?: "MLO"}")
            add("Peak phase: ${Wire.amps(peakAmps)}")
        }.joinToString(" · ")

        drawTotalsAndFooter(canvas, pd, warnings, bottom, width, date, pageNumber, pageCount, extraLine = extraSummary)
    }

    private fun drawTotalsAndFooter(
        canvas: Canvas,
        pd: PanelDesign?,
        warnings: List<String>,
        bottom: Float,
        width: Float,
        date: String,
        pageNumber: Int,
        pageCount: Int,
        extraLine: String? = null,
    ) {
        var y = bottom + 16f
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
            if (extraLine != null) {
                canvas.drawText(fit(extraLine, t, width), MARGIN, y, t)
                y += 12f
            }
            warnings.forEach {
                canvas.drawText(fit("Warning: $it", t, width), MARGIN, y, t)
                y += 12f
            }
        }
        val foot = paint(7f, gray = true)
        canvas.drawText(
            "Wire: conductors in the cable or conduit (phases + neutral) and G = equipment grounding conductor. Sized per ${Code.label}; verify before installing.",
            MARGIN,
            PAGE_H - MARGIN + 6f,
            foot,
        )
        val pageText = "Construction Calc · $date · page $pageNumber of $pageCount"
        canvas.drawText(pageText, MARGIN + width - foot.measureText(pageText), PAGE_H - MARGIN + 16f, foot)
    }

    private fun circuitDescription(c: Circuit): String = when {
        c.kind == CircuitKind.SPACE -> "space" + c.description.takeIf { it.isNotBlank() }?.let { " – ${it.trim()}" }.orEmpty()
        c.kind == CircuitKind.SPARE -> "Spare" + c.description.takeIf { it.isNotBlank() }?.let { " – ${it.trim()}" }.orEmpty()
        else -> c.description
    }

    /** Formats sorted slot integers into compact ranges like `"19–24, 27, 29–30"`. */
    private fun formatSlotRanges(slots: List<Int>): String {
        if (slots.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = slots.first()
        var prev = start
        for (i in 1 until slots.size) {
            val s = slots[i]
            if (s == prev + 1) {
                prev = s
            } else {
                parts += if (start == prev) "$start" else "$start–$prev"
                start = s
                prev = s
            }
        }
        parts += if (start == prev) "$start" else "$start–$prev"
        return parts.joinToString(", ")
    }
}
