package com.thedirepack.constructioncalc.feature.pullplanner

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.design.PullResult
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PathProfile
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullPdfReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.SegmentReportRow
import java.io.OutputStream
import kotlin.math.max

/**
 * Multi-page Letter PDF export for a Wire Pull Planner calculation
 * (`android.graphics.pdf.PdfDocument`: no library, no permissions).
 *
 * Uses a two-pass buffered layout collector so wrapped text, tables, and notes paginate cleanly
 * across pages with repeated table headers and an exact `page X of Y` footer on every page.
 */
internal object PullReportPdf {
    internal const val PAGE_W = 612
    internal const val PAGE_H = 792
    private const val MARGIN = 36f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val BOTTOM_Y = PAGE_H - MARGIN - 20f
    private const val SECTION_HEADER_H = 18f
    private const val DIAGRAM_BOX_H = 116f

    private const val INK = 0xFF111111.toInt()
    private const val GRAY = 0xFF555555.toInt()
    private const val ACCENT = 0xFF1B4F72.toInt()
    private const val WARN_RED = 0xFFB00020.toInt()
    private const val OK_GREEN = 0xFF1B5E20.toInt()
    private const val CALLOUT_BG = 0xFFF4F6F9.toInt()
    private const val RECOMMENDED_BG = 0xFFE8EEF5.toInt()
    private const val HEADER_BG = 0xFFEAECEF.toInt()
    private const val ALT_ROW_BG = 0xFFFAFAFA.toInt()
    private const val WARN_BG = 0xFFFDF2F2.toInt()
    private const val DIAGRAM_BG = 0xFFFCFCFD.toInt()

    private val thinLine = Paint().apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = 0.5f
        style = Paint.Style.STROKE
    }
    private val gridLine = Paint().apply {
        color = 0xFFE2E5E9.toInt()
        strokeWidth = 0.5f
        style = Paint.Style.STROKE
    }
    private val strongLine = Paint().apply {
        color = 0xFF333333.toInt()
        strokeWidth = 0.85f
        style = Paint.Style.STROKE
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun write(out: OutputStream, report: PullPdfReport, result: PullResult) {
        write(
            out = out,
            report = report,
            path = result.run.path,
            conduitType = result.run.raceway?.conduitType,
            tradeSize = result.conduit?.tradeSize ?: result.run.raceway?.tradeSize,
        )
    }

    fun write(
        out: OutputStream,
        report: PullPdfReport,
        path: List<PathSegment>,
        conduitType: ConduitType?,
        tradeSize: TradeSize?,
    ) {
        val pages = layoutPages(report, path, conduitType, tradeSize)
        val totalPages = pages.size
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { index, commands ->
                val pageNumber = index + 1
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
                commands.forEach { draw -> draw(page.canvas) }
                drawFooter(page.canvas, report.date, pageNumber, totalPages)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun layoutPages(
        report: PullPdfReport,
        path: List<PathSegment>,
        conduitType: ConduitType?,
        tradeSize: TradeSize?,
    ): List<List<(Canvas) -> Unit>> {
        val pages = PageCollector()
        drawHeader(pages, report)
        drawSummaryCallout(pages, report)
        if (path.isNotEmpty()) {
            drawElevationDiagram(pages, path, conduitType, tradeSize)
        }
        drawRunFacts(pages, report)
        drawCables(pages, report)
        drawLimits(pages, report)
        drawSegmentTable(pages, report)
        drawWarnings(pages, report)
        drawInfoNotes(pages, report)
        return pages.build()
    }

    private fun drawHeader(pages: PageCollector, report: PullPdfReport) {
        val companyPaint = textPaint(8f, color = GRAY)
        report.companyHeader.forEach { line ->
            val layout = staticLayout(line, companyPaint, CONTENT_W)
            val y = pages.y
            pages.add { canvas -> drawLayout(canvas, layout, MARGIN, y) }
            pages.y += layout.height + 1.5f
        }
        if (report.companyHeader.isNotEmpty()) pages.y += 4f

        val titleLayout = staticLayout(report.title, textPaint(15f, bold = true), CONTENT_W)
        val titleY = pages.y
        pages.add { canvas -> drawLayout(canvas, titleLayout, MARGIN, titleY) }
        pages.y += titleLayout.height + 2f

        val subLayout = staticLayout(report.subtitle, textPaint(9f, color = GRAY), CONTENT_W)
        val subY = pages.y
        pages.add { canvas -> drawLayout(canvas, subLayout, MARGIN, subY) }
        pages.y += subLayout.height + 6f

        val ruleY = pages.y
        pages.add { canvas -> canvas.drawLine(MARGIN, ruleY, MARGIN + CONTENT_W, ruleY, strongLine) }
        pages.y += 10f
    }

    private fun drawSummaryCallout(pages: PageCollector, report: PullPdfReport) {
        val padX = 10f
        val padY = 8f
        val stripeW = 3f
        val innerW = CONTENT_W - stripeW - 2 * padX
        val labelPaint = textPaint(7.5f, bold = true, color = ACCENT)
        val summaryLayout = staticLayout(report.summaryLine, textPaint(9.5f, bold = true), innerW)

        val dirRows = report.directionSummary
        val dirHeaderH = if (dirRows.isNotEmpty()) 14f else 0f
        val dirRowH = 16f
        val dirSectionH = if (dirRows.isNotEmpty()) 8f + dirHeaderH + dirRows.size * dirRowH else 0f
        val boxH = padY + 11f + summaryLayout.height + dirSectionH + padY

        pages.ensureSpace(boxH + 10f)
        val top = pages.y
        val bottom = top + boxH
        val bgPaint = fillPaint(CALLOUT_BG)
        val stripePaint = fillPaint(ACCENT)
        val recBgPaint = fillPaint(RECOMMENDED_BG)
        val headPaint = textPaint(7.5f, bold = true, color = GRAY)
        val cellPaint = textPaint(8f)
        val cellBold = textPaint(8f, bold = true)
        val okPaint = textPaint(8f, bold = true, color = OK_GREEN)
        val warnPaint = textPaint(8f, bold = true, color = WARN_RED)

        pages.add { canvas ->
            canvas.drawRect(MARGIN, top, MARGIN + CONTENT_W, bottom, bgPaint)
            canvas.drawRect(MARGIN, top, MARGIN + stripeW, bottom, stripePaint)
            canvas.drawRect(MARGIN, top, MARGIN + CONTENT_W, bottom, thinLine)

            val contentX = MARGIN + stripeW + padX
            var cy = top + padY
            drawSingleLineTop(canvas, "SUMMARY", contentX, cy, labelPaint)
            cy += 11f
            drawLayout(canvas, summaryLayout, contentX, cy)
            cy += summaryLayout.height

            if (dirRows.isNotEmpty()) {
                cy += 6f
                canvas.drawLine(contentX, cy, contentX + innerW, cy, gridLine)
                cy += 2f

                val cDir = 220f
                val cTens = 92f
                val cSwbp = 104f
                val cStat = innerW - cDir - cTens - cSwbp

                drawCellText(canvas, "Direction", contentX, cy, cDir, dirHeaderH, headPaint, alignRight = false)
                drawCellText(canvas, "Peak Tension", contentX + cDir, cy, cTens, dirHeaderH, headPaint, alignRight = true)
                drawCellText(canvas, "Peak SWBP", contentX + cDir + cTens, cy, cSwbp, dirHeaderH, headPaint, alignRight = true)
                drawCellText(canvas, "Status", contentX + cDir + cTens + cSwbp, cy, cStat, dirHeaderH, headPaint, alignRight = true)
                cy += dirHeaderH

                dirRows.forEach { row ->
                    if (row.recommended) {
                        canvas.drawRect(contentX - 2f, cy, contentX + innerW + 2f, cy + dirRowH, recBgPaint)
                    }
                    val dirLabel = if (row.recommended) "${row.direction} (Recommended)" else row.direction
                    val dirPaint = if (row.recommended) cellBold else cellPaint
                    val statusPaint = if (row.status == "Exceeds limits") warnPaint else okPaint
                    drawCellText(canvas, dirLabel, contentX, cy, cDir, dirRowH, dirPaint, alignRight = false)
                    drawCellText(canvas, row.peakTension, contentX + cDir, cy, cTens, dirRowH, dirPaint, alignRight = true)
                    drawCellText(canvas, row.peakPressure, contentX + cDir + cTens, cy, cSwbp, dirRowH, dirPaint, alignRight = true)
                    drawCellText(canvas, row.status, contentX + cDir + cTens + cSwbp, cy, cStat, dirRowH, statusPaint, alignRight = true)
                    cy += dirRowH
                }
            }
        }
        pages.y = bottom + 12f
    }

    private fun drawElevationDiagram(
        pages: PageCollector,
        path: List<PathSegment>,
        conduitType: ConduitType?,
        tradeSize: TradeSize?,
    ) {
        pages.ensureSpace(SECTION_HEADER_H + DIAGRAM_BOX_H + 12f)
        drawSectionHeading(pages, "ELEVATION PROFILE (SIDE VIEW)")

        val boxTop = pages.y
        val boxBottom = boxTop + DIAGRAM_BOX_H
        val profile = PathProfile.of(path, conduitType, tradeSize)
        val bgPaint = fillPaint(DIAGRAM_BG)
        val markerFill = fillPaint(ACCENT)
        val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            strokeWidth = 0.7f
            style = Paint.Style.STROKE
        }
        val markerLabelPaint = textPaint(7f, bold = true)
        val legendPaint = textPaint(6.5f, color = GRAY)

        pages.add { canvas ->
            canvas.drawRect(MARGIN, boxTop, MARGIN + CONTENT_W, boxBottom, bgPaint)
            canvas.drawRect(MARGIN, boxTop, MARGIN + CONTENT_W, boxBottom, thinLine)

            val padX = 28.0
            val padTop = 20.0
            val padBottom = 18.0
            val w = CONTENT_W - 2 * padX
            val h = DIAGRAM_BOX_H - padTop - padBottom
            val spanX = max(profile.maxX - profile.minX, 1e-6)
            val spanY = profile.maxY - profile.minY
            val flat = spanY < 1e-6
            val scale = if (flat) w / spanX else minOf(w / spanX, h / spanY)
            val originX = MARGIN + padX + (w - spanX * scale) / 2.0
            val originY = boxTop + padTop + (h - spanY * scale) / 2.0

            fun mapX(x: Double): Float = (originX + (x - profile.minX) * scale).toFloat()
            fun mapY(y: Double): Float = if (flat) {
                (boxTop + padTop + h / 2.0).toFloat()
            } else {
                (originY + (profile.maxY - y) * scale).toFloat()
            }

            val route = Path()
            profile.points.forEachIndexed { idx, pt ->
                val px = mapX(pt.x)
                val py = mapY(pt.y)
                if (idx == 0) route.moveTo(px, py) else route.lineTo(px, py)
            }
            canvas.drawPath(route, routePaint)

            val placedBoxes = mutableListOf<RectF>()
            val fm = markerLabelPaint.fontMetrics
            val labelH = fm.descent - fm.ascent

            profile.markers.forEach { marker ->
                val ox = mapX(marker.at.x)
                val oy = mapY(marker.at.y)
                if (marker.pullPoint) {
                    val half = 3.5f
                    canvas.drawRect(ox - half, oy - half, ox + half, oy + half, markerFill)
                    canvas.drawRect(ox - half, oy - half, ox + half, oy + half, markerStroke)
                } else {
                    canvas.drawCircle(ox, oy, 3.5f, markerFill)
                    canvas.drawCircle(ox, oy, 3.5f, markerStroke)
                }

                val lw = markerLabelPaint.measureText(marker.label)
                val lx = (ox - lw / 2f).coerceIn(MARGIN + 4f, MARGIN + CONTENT_W - lw - 4f)
                val minY = boxTop + 3f
                val maxY = boxBottom - 14f - labelH
                val candidates = listOf(
                    oy - 6f - labelH,
                    oy + 6f,
                    oy - 8f - 2f * labelH,
                    oy + 8f + labelH,
                ).map { it.coerceIn(minY, maxY) }
                val chosenTop = candidates.firstOrNull { candidateY ->
                    val rect = RectF(lx - 2f, candidateY, lx + lw + 2f, candidateY + labelH)
                    placedBoxes.none { RectF.intersects(it, rect) }
                } ?: candidates.first()
                placedBoxes += RectF(lx - 2f, chosenTop, lx + lw + 2f, chosenTop + labelH)
                canvas.drawText(marker.label, lx, chosenTop - fm.ascent, markerLabelPaint)
            }

            canvas.drawText(
                "Schematic side view (not to scale) · Circle: horizontal bend · Square: pull point",
                MARGIN + 8f,
                boxBottom - 5f,
                legendPaint,
            )
        }
        pages.y = boxBottom + 12f
    }

    private fun drawRunFacts(pages: PageCollector, report: PullPdfReport) {
        if (report.runFacts.isEmpty()) return
        val gutter = 14f
        val colW = (CONTENT_W - gutter) / 2f
        val keyPaint = textPaint(7f, bold = true, color = GRAY)
        val valPaint = textPaint(8.5f)
        val rows = report.runFacts.chunked(2)

        val firstPair = rows.first()
        val firstH = factRowHeight(firstPair, colW, keyPaint, valPaint)
        pages.ensureSpace(SECTION_HEADER_H + firstH)
        drawSectionHeading(pages, "RUN DETAILS")

        rows.forEach { pair ->
            val leftK = staticLayout(pair[0].first.uppercase(), keyPaint, colW)
            val leftV = staticLayout(pair[0].second, valPaint, colW)
            val leftH = leftK.height + 1.5f + leftV.height

            val rightK = pair.getOrNull(1)?.let { staticLayout(it.first.uppercase(), keyPaint, colW) }
            val rightV = pair.getOrNull(1)?.let { staticLayout(it.second, valPaint, colW) }
            val rightH = if (rightK != null && rightV != null) rightK.height + 1.5f + rightV.height else 0f

            val rowH = maxOf(leftH, rightH) + 6f
            pages.ensureSpace(rowH) {
                drawSectionHeading(pages, "RUN DETAILS (CONTINUED)")
            }
            val top = pages.y
            pages.add { canvas ->
                drawLayout(canvas, leftK, MARGIN, top)
                drawLayout(canvas, leftV, MARGIN, top + leftK.height + 1.5f)
                if (rightK != null && rightV != null) {
                    val rx = MARGIN + colW + gutter
                    drawLayout(canvas, rightK, rx, top)
                    drawLayout(canvas, rightV, rx, top + rightK.height + 1.5f)
                }
                val lineY = top + rowH - 2.5f
                canvas.drawLine(MARGIN, lineY, MARGIN + CONTENT_W, lineY, gridLine)
            }
            pages.y = top + rowH
        }
        pages.y += 8f
    }

    private fun factRowHeight(
        pair: List<Pair<String, String>>,
        colW: Float,
        keyPaint: TextPaint,
        valPaint: TextPaint,
    ): Float {
        val lh = staticLayout(pair[0].first.uppercase(), keyPaint, colW).height + 1.5f +
            staticLayout(pair[0].second, valPaint, colW).height
        val rh = pair.getOrNull(1)?.let {
            staticLayout(it.first.uppercase(), keyPaint, colW).height + 1.5f +
                staticLayout(it.second, valPaint, colW).height
        } ?: 0f
        return maxOf(lh, rh) + 6f
    }

    private fun drawCables(pages: PageCollector, report: PullPdfReport) {
        val bodyPaint = textPaint(8.5f)
        val items = report.cables.ifEmpty { listOf("None") }
        val firstLayout = staticLayout(
            if (report.cables.isEmpty()) items.first() else "• ${items.first()}",
            if (report.cables.isEmpty()) textPaint(8.5f, color = GRAY) else bodyPaint,
            CONTENT_W - 6f,
        )
        pages.ensureSpace(SECTION_HEADER_H + firstLayout.height + 6f)
        drawSectionHeading(pages, "CABLES & CONDUCTORS")

        items.forEach { cable ->
            val paint = if (report.cables.isEmpty()) textPaint(8.5f, color = GRAY) else bodyPaint
            val text = if (report.cables.isEmpty()) cable else "• $cable"
            val layout = staticLayout(text, paint, CONTENT_W - 6f)
            val itemH = layout.height + 3.5f
            pages.ensureSpace(itemH) {
                drawSectionHeading(pages, "CABLES & CONDUCTORS (CONTINUED)")
            }
            val top = pages.y
            pages.add { canvas -> drawLayout(canvas, layout, MARGIN + 4f, top) }
            pages.y = top + itemH
        }
        pages.y += 8f
    }

    private fun drawLimits(pages: PageCollector, report: PullPdfReport) {
        if (report.limits.isEmpty() && report.unratedEquipment.isEmpty()) return
        val keyW = 175f
        val gap = 8f
        val valW = CONTENT_W - keyW - gap
        val keyPaint = textPaint(8.5f, bold = true)
        val valPaint = textPaint(8.5f)

        val unratedText = report.unratedEquipment
            .takeIf { it.isNotEmpty() }
            ?.let { "Unrated equipment (not included in tension limit): ${it.joinToString(", ")}" }
        val unratedLayout = unratedText?.let { staticLayout(it, textPaint(8.5f, color = GRAY), CONTENT_W) }

        val firstHeight = if (report.limits.isNotEmpty()) {
            val (k, v) = report.limits.first()
            maxOf(staticLayout(k, keyPaint, keyW).height, staticLayout(v, valPaint, valW).height) + 5f
        } else {
            (unratedLayout?.height ?: 12) + 5f
        }
        pages.ensureSpace(SECTION_HEADER_H + firstHeight)
        drawSectionHeading(pages, "LIMITS & EQUIPMENT RATINGS")

        report.limits.forEach { (label, detail) ->
            val kLayout = staticLayout(label, keyPaint, keyW)
            val vLayout = staticLayout(detail, valPaint, valW)
            val rowH = maxOf(kLayout.height, vLayout.height) + 5f
            pages.ensureSpace(rowH) {
                drawSectionHeading(pages, "LIMITS & EQUIPMENT RATINGS (CONTINUED)")
            }
            val top = pages.y
            pages.add { canvas ->
                drawLayout(canvas, kLayout, MARGIN, top)
                drawLayout(canvas, vLayout, MARGIN + keyW + gap, top)
            }
            pages.y = top + rowH
        }

        if (unratedLayout != null) {
            val itemH = unratedLayout.height + 5f
            pages.ensureSpace(itemH) {
                drawSectionHeading(pages, "LIMITS & EQUIPMENT RATINGS (CONTINUED)")
            }
            val top = pages.y
            pages.add { canvas -> drawLayout(canvas, unratedLayout, MARGIN, top) }
            pages.y = top + itemH
        }
        pages.y += 8f
    }

    private fun drawSegmentTable(pages: PageCollector, report: PullPdfReport) {
        if (report.segments.isEmpty()) return
        val colWidths = floatArrayOf(22f, 182f, 52f, 54f, 62f, 52f, 54f, 62f)
        val headers = listOf("#", "Segment", "Fwd In", "Fwd Out", "Fwd SWBP", "Rev In", "Rev Out", "Rev SWBP")
        val tableHeaderH = 18f
        val headPaint = textPaint(7.5f, bold = true)
        val cellPaint = textPaint(7.5f)
        val cellBold = textPaint(7.5f, bold = true)

        val firstDescLayout = staticLayout(report.segments.first().description, cellPaint, colWidths[1] - 8f)
        val firstRowH = maxOf(16f, firstDescLayout.height + 6f)
        pages.ensureSpace(SECTION_HEADER_H + tableHeaderH + firstRowH)

        drawSectionHeading(pages, "SEGMENT RESULTS")
        drawTableHeader(pages, headers, colWidths, tableHeaderH, headPaint)

        report.segments.forEachIndexed { index, row ->
            val descLayout = if (index == 0) {
                firstDescLayout
            } else {
                staticLayout(row.description, cellPaint, colWidths[1] - 8f)
            }
            val rowH = maxOf(16f, descLayout.height + 6f)
            if (pages.y + rowH > BOTTOM_Y && pages.y > MARGIN + SECTION_HEADER_H + tableHeaderH) {
                val closeY = pages.y
                pages.add { canvas ->
                    canvas.drawLine(MARGIN, closeY, MARGIN + CONTENT_W, closeY, strongLine)
                }
                pages.newPage()
                drawSectionHeading(pages, "SEGMENT RESULTS (CONTINUED)")
                drawTableHeader(pages, headers, colWidths, tableHeaderH, headPaint)
            }
            drawTableRow(pages, row, descLayout, colWidths, rowH, shaded = index % 2 == 1, cellPaint, cellBold)
        }

        val endY = pages.y
        pages.add { canvas ->
            canvas.drawLine(MARGIN, endY, MARGIN + CONTENT_W, endY, strongLine)
        }
        pages.y += 12f
    }

    private fun drawTableHeader(
        pages: PageCollector,
        headers: List<String>,
        colWidths: FloatArray,
        headerH: Float,
        headPaint: TextPaint,
    ) {
        val top = pages.y
        val bottom = top + headerH
        val bgPaint = fillPaint(HEADER_BG)
        pages.add { canvas ->
            canvas.drawRect(MARGIN, top, MARGIN + CONTENT_W, bottom, bgPaint)
            canvas.drawRect(MARGIN, top, MARGIN + CONTENT_W, bottom, strongLine)
            var x = MARGIN
            headers.forEachIndexed { i, title ->
                val w = colWidths[i]
                if (i > 0) canvas.drawLine(x, top, x, bottom, thinLine)
                when (i) {
                    0 -> drawCellCentered(canvas, title, x, top, w, headerH, headPaint)
                    1 -> drawCellText(canvas, title, x, top, w, headerH, headPaint, alignRight = false)
                    else -> drawCellText(canvas, title, x, top, w, headerH, headPaint, alignRight = true)
                }
                x += w
            }
        }
        pages.y = bottom
    }

    private fun drawTableRow(
        pages: PageCollector,
        row: SegmentReportRow,
        descLayout: StaticLayout,
        colWidths: FloatArray,
        rowH: Float,
        shaded: Boolean,
        cellPaint: TextPaint,
        cellBold: TextPaint,
    ) {
        val top = pages.y
        val bottom = top + rowH
        val bgPaint = if (shaded) fillPaint(ALT_ROW_BG) else null
        val values = listOf(
            row.number.toString(),
            row.description,
            row.forwardIn,
            row.forwardOut,
            row.forwardSwbp,
            row.reverseIn,
            row.reverseOut,
            row.reverseSwbp,
        )
        pages.add { canvas ->
            if (bgPaint != null) canvas.drawRect(MARGIN, top, MARGIN + CONTENT_W, bottom, bgPaint)
            canvas.drawLine(MARGIN, bottom, MARGIN + CONTENT_W, bottom, gridLine)
            canvas.drawLine(MARGIN, top, MARGIN, bottom, strongLine)
            canvas.drawLine(MARGIN + CONTENT_W, top, MARGIN + CONTENT_W, bottom, strongLine)

            var x = MARGIN
            values.forEachIndexed { i, value ->
                val w = colWidths[i]
                if (i > 0) canvas.drawLine(x, top, x, bottom, thinLine)
                when (i) {
                    0 -> drawCellCentered(canvas, value, x, top, w, rowH, cellBold)
                    1 -> drawLayout(canvas, descLayout, x + 4f, top + (rowH - descLayout.height) / 2f)
                    else -> drawCellText(canvas, value, x, top, w, rowH, cellPaint, alignRight = true)
                }
                x += w
            }
        }
        pages.y = bottom
    }

    private fun drawWarnings(pages: PageCollector, report: PullPdfReport) {
        if (report.warnings.isEmpty()) return
        val warnPaint = textPaint(8.5f, bold = true, color = WARN_RED)
        val firstLayout = staticLayout("Warning: ${report.warnings.first()}", warnPaint, CONTENT_W - 14f)
        pages.ensureSpace(SECTION_HEADER_H + firstLayout.height + 12f)
        drawSectionHeading(pages, "WARNINGS", color = WARN_RED)

        val bgPaint = fillPaint(WARN_BG)
        val barPaint = fillPaint(WARN_RED)

        report.warnings.forEach { warning ->
            val layout = staticLayout("Warning: $warning", warnPaint, CONTENT_W - 14f)
            val boxH = layout.height + 8f
            pages.ensureSpace(boxH + 4f) {
                drawSectionHeading(pages, "WARNINGS (CONTINUED)", color = WARN_RED)
            }
            val top = pages.y
            pages.add { canvas ->
                canvas.drawRect(MARGIN, top, MARGIN + CONTENT_W, top + boxH, bgPaint)
                canvas.drawRect(MARGIN, top, MARGIN + 2.5f, top + boxH, barPaint)
                drawLayout(canvas, layout, MARGIN + 8f, top + 4f)
            }
            pages.y = top + boxH + 4f
        }
        pages.y += 6f
    }

    private fun drawInfoNotes(pages: PageCollector, report: PullPdfReport) {
        if (report.infoNotes.isEmpty()) return
        val notePaint = textPaint(8.5f, color = GRAY)
        val firstLayout = staticLayout("• ${report.infoNotes.first()}", notePaint, CONTENT_W - 6f)
        pages.ensureSpace(SECTION_HEADER_H + firstLayout.height + 4f)
        drawSectionHeading(pages, "ENGINEERING NOTES & ASSUMPTIONS")

        report.infoNotes.forEach { note ->
            val layout = staticLayout("• $note", notePaint, CONTENT_W - 6f)
            val itemH = layout.height + 3.5f
            pages.ensureSpace(itemH) {
                drawSectionHeading(pages, "ENGINEERING NOTES & ASSUMPTIONS (CONTINUED)")
            }
            val top = pages.y
            pages.add { canvas -> drawLayout(canvas, layout, MARGIN + 4f, top) }
            pages.y = top + itemH
        }
    }

    private fun drawSectionHeading(pages: PageCollector, title: String, color: Int = INK) {
        val paint = textPaint(8.5f, bold = true, color = color)
        val top = pages.y
        pages.add { canvas ->
            drawSingleLineTop(canvas, title, MARGIN, top, paint)
            val ruleY = top + 12f
            canvas.drawLine(MARGIN, ruleY, MARGIN + CONTENT_W, ruleY, strongLine)
        }
        pages.y = top + SECTION_HEADER_H
    }

    private fun drawFooter(canvas: Canvas, date: String, pageNumber: Int, totalPages: Int) {
        val lineY = PAGE_H - MARGIN + 2f
        canvas.drawLine(MARGIN, lineY, MARGIN + CONTENT_W, lineY, thinLine)

        val disclaimerPaint = textPaint(6.5f, color = GRAY)
        val footPaint = textPaint(7f, color = GRAY)
        val baseline = PAGE_H - MARGIN + 14f

        canvas.drawText(
            "Calculated per IEEE 1185 / NEC · Verify limits with cable and equipment manufacturers.",
            MARGIN,
            baseline,
            disclaimerPaint,
        )
        val pageText = "Construction Calc · $date · page $pageNumber of $totalPages"
        canvas.drawText(pageText, MARGIN + CONTENT_W - footPaint.measureText(pageText), baseline, footPaint)
    }

    private fun textPaint(size: Float, bold: Boolean = false, color: Int = INK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        this.color = color
    }

    private fun fillPaint(color: Int) = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun staticLayout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout {
        val w = width.toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawSingleLineTop(canvas: Canvas, text: String, x: Float, top: Float, paint: TextPaint) {
        val fm = paint.fontMetrics
        canvas.drawText(fit(text, paint, CONTENT_W), x, top - fm.ascent, paint)
    }

    private fun drawCellText(
        canvas: Canvas,
        text: String,
        colX: Float,
        rowTop: Float,
        colW: Float,
        rowH: Float,
        paint: TextPaint,
        alignRight: Boolean,
    ) {
        val pad = 4f
        val fitted = fit(text, paint, colW - 2 * pad)
        val fm = paint.fontMetrics
        val baseline = rowTop + (rowH - (fm.ascent + fm.descent)) / 2f
        val x = if (alignRight) colX + colW - pad - paint.measureText(fitted) else colX + pad
        canvas.drawText(fitted, x, baseline, paint)
    }

    private fun drawCellCentered(
        canvas: Canvas,
        text: String,
        colX: Float,
        rowTop: Float,
        colW: Float,
        rowH: Float,
        paint: TextPaint,
    ) {
        val fitted = fit(text, paint, colW - 4f)
        val fm = paint.fontMetrics
        val baseline = rowTop + (rowH - (fm.ascent + fm.descent)) / 2f
        val x = colX + (colW - paint.measureText(fitted)) / 2f
        canvas.drawText(fitted, x, baseline, paint)
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

    private class PageCollector {
        private val pages = mutableListOf<MutableList<(Canvas) -> Unit>>(mutableListOf())
        var y: Float = MARGIN

        fun add(draw: (Canvas) -> Unit) {
            pages.last() += draw
        }

        fun newPage() {
            pages.add(mutableListOf())
            y = MARGIN
        }

        fun ensureSpace(needed: Float, onNewPage: (() -> Unit)? = null) {
            if (y + needed > BOTTOM_Y && y > MARGIN) {
                newPage()
                onNewPage?.invoke()
            }
        }

        fun build(): List<List<(Canvas) -> Unit>> = pages
    }
}
