package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasEditingTest {
    @Test fun selectsTopMarkupAndHitsInkAlongItsPath() {
        val line = PlanMark(page = 0, kind = PlanMarkKind.INK, points = listOf(PlanPoint(0.0, 0.0), PlanPoint(80.0, 0.0)))
        val overlap = line.copy(id = "top")
        assertEquals(overlap, markAt(listOf(line, overlap), PlanPoint(40.0, 3.0), 3.0))
        assertNull(markAt(listOf(line), PlanPoint(40.0, 15.0), 3.0))
    }

    @Test fun draggingAnAlternateRectangleCornerKeepsTheOppositeCorner() {
        val first = PlanPoint(10.0, 20.0)
        val end = PlanPoint(50.0, 60.0)
        val mark = PlanMark(page = 0, kind = PlanMarkKind.RECTANGLE, points = listOf(first, end))
        assertEquals(PlanPoint(10.0, 60.0), mark.editHandles()[2])
        assertEquals(
            listOf(PlanPoint(5.0, 20.0), PlanPoint(50.0, 75.0)),
            mark.resize(2, PlanPoint(5.0, 75.0)).points,
        )
    }

    @Test fun movingCalloutKeepsLeaderTipOnTheDevice() {
        val anchor = PlanPoint(10.0, 20.0)
        val box = PlanPoint(50.0, 60.0)
        val mark = PlanMark(page = 0, kind = PlanMarkKind.CALLOUT, points = listOf(anchor, box), text = "5R4-B-36")
        assertEquals(listOf(anchor, PlanPoint(55.0, 56.0)), mark.move(5.0, -4.0).points)
        assertEquals(listOf(PlanPoint(3.0, 4.0), box), mark.resize(0, PlanPoint(3.0, 4.0)).points)
    }

    @Test fun findsSheetIdentifierAndTitleInPdfText() {
        val label = titleBlockLabel("ISSUED 09/08/25\nSHEET TITLE:\nPOWER\nPLAN\nSHEET NUMBER:\nEP101\n")
        assertEquals(SheetLabel("EP101", "POWER PLAN"), label)
        assertEquals(true, label?.matches("power"))
        assertNull(titleBlockLabel("No title block"))
    }

    @Test fun repeatedPdfPageLabelsDoNotHidePrintedSheetNumbers() {
        val staleLabel = "A-601 - DOOR - WINDOW & FINISH SCHEDULE"
        val title = titleBlockLabel("SHEET TITLE:\nPOWER\nPLAN\nSHEET NUMBER:\nEP101\n")
        val repeatedLabels = setOf(staleLabel)

        val ep101 = resolveSheetLabel(staleLabel, repeatedLabels, title)
        assertEquals(SheetLabel("EP101", "POWER PLAN"), ep101)
        assertEquals(true, ep101?.matches("EP101"))
        assertEquals(true, ep101?.matches("POWER PLAN"))
        assertNull(resolveSheetLabel(staleLabel, repeatedLabels, null))
        assertEquals(
            SheetLabel("A-102 - DIMENSION PLANS", ""),
            resolveSheetLabel("A-102 - DIMENSION PLANS", repeatedLabels, SheetLabel("A-102", "DIMENSION PLANS")),
        )
        assertEquals(
            SheetLabel("A-102", "DIMENSION PLANS"),
            resolveSheetLabel("A-102", repeatedLabels, SheetLabel("A-102", "DIMENSION PLANS")),
        )
    }

    @Test fun sheetLabelsAndPdfTextDiskCachesRoundTripAndRejectCorruptInput() {
        val labels = mapOf(
            0 to SheetLabel("G-001", "COVER\tSHEET\\A"),
            15 to SheetLabel("A-102", "DIMENSION\nPLANS"),
        )
        val encodedLabels = encodeSheetLabelsCache(labels)
        assertEquals(labels, decodeSheetLabelsCache(encodedLabels))
        assertNull(decodeSheetLabelsCache("WRONG-HEADER\n0\tA\tB"))
        assertNull(decodeSheetLabelsCache("CC-SHEET-LABELS-V1\nbad"))

        val textData = CachedPdfText(
            hasText = true,
            pages = mapOf(
                0 to "GENERAL\tNOTES\\REV1\nLINE2",
                30 to "EP101 POWER PLAN GARAGE DOOR OPENER",
            ),
        )
        val encodedText = encodePdfTextCache(textData)
        assertEquals(textData, decodePdfTextCache(encodedText))

        val noText = CachedPdfText(hasText = false, pages = emptyMap())
        assertEquals(noText, decodePdfTextCache(encodePdfTextCache(noText)))
        assertNull(decodePdfTextCache("CC-PDF-TEXT-V1\t2\n"))
        assertNull(decodePdfTextCache("CC-PDF-TEXT-V1\t1\n-1\tbad"))
    }

    @Test fun partialEraserSplitsAndTrimsInkPreservingStyleAndPressures() {
        val ink = PlanMark(
            id = "ink-1",
            page = 0,
            kind = PlanMarkKind.INK,
            points = listOf(PlanPoint(0.0, 0.0), PlanPoint(100.0, 0.0)),
            pressures = listOf(0.4f, 0.8f),
            color = 0xFFC62828L,
            opacity = 0.7f,
        )
        // Erasing far away keeps the exact stroke intact.
        val untouched = eraseStrokePartially(ink, listOf(PlanPoint(50.0, 50.0)), 4.0)
        assertEquals(listOf(ink), untouched)

        // Swiping through the middle splits the stroke into two fragments with valid pressures.
        val split = eraseStrokePartially(ink, listOf(PlanPoint(50.0, -10.0), PlanPoint(50.0, 10.0)), 5.0)
        assertEquals(2, split.size)
        assertNotEquals("ink-1", split[0].id)
        assertEquals(0xFFC62828L, split[0].color)
        assertEquals(0.7f, split[0].opacity, 1e-6f)
        assertTrue(split[0].points.last().x < 46.0)
        assertTrue(split[1].points.first().x > 54.0)
        assertEquals(split[0].points.size, split[0].pressures.size)
        assertEquals(split[1].points.size, split[1].pressures.size)

        // Single dot erased returns empty.
        val dot = PlanMark(page = 0, kind = PlanMarkKind.HIGHLIGHT, points = listOf(PlanPoint(10.0, 10.0)), width = 8.0)
        assertTrue(eraseStrokePartially(dot, listOf(PlanPoint(11.0, 11.0)), 3.0).isEmpty())

        // Non-stroke marks are never modified by partial eraser.
        val box = PlanMark(page = 0, kind = PlanMarkKind.RECTANGLE, points = listOf(PlanPoint(0.0, 0.0), PlanPoint(20.0, 20.0)))
        assertEquals(listOf(box), eraseStrokePartially(box, listOf(PlanPoint(10.0, 10.0)), 10.0))
    }

    @Test fun cloudAndCloudCalloutSupportHandlesInsideCloudAndHitTests() {
        val cloud = PlanMark(page = 0, kind = PlanMarkKind.CLOUD, points = listOf(PlanPoint(10.0, 20.0), PlanPoint(60.0, 70.0)))
        assertEquals(4, cloud.editHandles().size)
        assertEquals(
            listOf(PlanPoint(5.0, 20.0), PlanPoint(60.0, 80.0)),
            cloud.resize(2, PlanPoint(5.0, 80.0)).points,
        )
        assertNotNull(markAt(listOf(cloud), PlanPoint(30.0, 40.0), 4.0))

        val cc = PlanMark(
            page = 0,
            kind = PlanMarkKind.CLOUD_CALLOUT,
            points = listOf(PlanPoint(10.0, 20.0), PlanPoint(60.0, 70.0)),
            text = "RFI-4",
            width = 13.0,
        )
        val handles = cc.editHandles()
        assertEquals(4, handles.size)
        assertEquals(
            listOf(PlanPoint(10.0, 20.0), PlanPoint(90.0, 85.0)),
            cc.resize(1, PlanPoint(90.0, 85.0)).points,
        )
        assertEquals(
            listOf(PlanPoint(15.0, 16.0), PlanPoint(65.0, 66.0)),
            cc.move(5.0, -4.0).points,
        )
        assertNotNull(markAt(listOf(cc), PlanPoint(35.0, 45.0), 4.0))
        // Hits inside the cloud only (no external leader box outside).
        assertNull(markAt(listOf(cc), PlanPoint(95.0, 30.0), 4.0))

        // Legacy 3-point cloud callout stays safe for handles & hit testing.
        val legacyCc = cc.copy(points = listOf(PlanPoint(10.0, 20.0), PlanPoint(60.0, 70.0), PlanPoint(90.0, 25.0)))
        assertEquals(4, legacyCc.editHandles().size)
        assertNotNull(markAt(listOf(legacyCc), PlanPoint(30.0, 40.0), 4.0))
    }

    @Test fun dimensionPhotoAndDetailLinkEditHitTestAndTransparentStyles() {
        val dim = PlanMark(page = 0, kind = PlanMarkKind.DIMENSION, points = listOf(PlanPoint(0.0, 0.0), PlanPoint(50.0, 0.0)))
        assertEquals(2, dim.editHandles().size)
        assertEquals(listOf(PlanPoint(0.0, 0.0), PlanPoint(75.0, 0.0)), dim.resize(1, PlanPoint(75.0, 0.0)).points)
        assertNotNull(markAt(listOf(dim), PlanPoint(25.0, 3.0), 4.0))

        val photo = PlanMark(
            page = 0,
            kind = PlanMarkKind.PHOTO,
            points = listOf(PlanPoint(40.0, 40.0)),
            text = "Panel LP-2",
            width = 24.0,
            photoSha256 = "a".repeat(64),
        )
        assertEquals(emptyList<PlanPoint>(), photo.editHandles())
        assertEquals(listOf(PlanPoint(50.0, 35.0)), photo.move(10.0, -5.0).points)
        assertNotNull(markAt(listOf(photo), PlanPoint(43.0, 42.0), 4.0))
        // Tapping over the caption to the right of the badge also hits the photo mark.
        assertNotNull(markAt(listOf(photo), PlanPoint(85.0, 40.0), 4.0))
        assertNull(markAt(listOf(photo), PlanPoint(200.0, 40.0), 4.0))

        val detail = PlanMark(page = 0, kind = PlanMarkKind.DETAIL_LINK, points = listOf(PlanPoint(80.0, 80.0)), targetPage = 3)
        assertNotNull(markAt(listOf(detail), PlanPoint(84.0, 80.0), 4.0))
        assertEquals(listOf(PlanPoint(90.0, 90.0)), detail.move(10.0, 10.0).points)

        for (mode in listOf(DrawingMode.Callout, DrawingMode.CloudCallout, DrawingMode.Text, DrawingMode.Dimension)) {
            val style = MarkupToolStyle.defaultFor(mode)
            assertNull("$mode should default to transparent fillColor", style.fillColor)
            assertEquals(0f, style.fillOpacity, 1e-6f)
        }
        val plainCallout = PlanMark(page = 0, kind = PlanMarkKind.CALLOUT, points = listOf(PlanPoint(0.0, 0.0), PlanPoint(20.0, 20.0)), text = "Note")
        val extracted = MarkupToolStyle.fromMark(plainCallout)
        assertNull(extracted.fillColor)
        assertEquals(0f, extracted.fillOpacity, 1e-6f)
        assertFalse(plainCallout.fillOpacity > 0f)
    }
}
