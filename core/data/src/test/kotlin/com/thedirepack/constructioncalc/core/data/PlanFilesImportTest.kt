package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPath
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.PlanScaleMode
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.SheetRevision
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException

class PlanFilesImportTest {
    @Test fun acceptsAPdfHeaderAtTheStartOrAfterLeadingJunk() {
        assertNull(PlanFiles.pdfHeaderProblem("%PDF-1.7\n%âãÏÓ".encodeToByteArray()))
        // Some generators put a few bytes (a BOM, a mail header) before the marker.
        assertNull(PlanFiles.pdfHeaderProblem(("﻿junk\r\n" + "%PDF-1.4").encodeToByteArray()))
        val late = ByteArray(1000) { ' '.code.toByte() } + "%PDF-1.5".encodeToByteArray()
        assertNull(PlanFiles.pdfHeaderProblem(late))
    }

    @Test fun rejectsEmptyFilesAndOtherFormats() {
        assertEquals("This file is empty.", PlanFiles.pdfHeaderProblem(ByteArray(0)))
        assertEquals("This file isn't a PDF.", PlanFiles.pdfHeaderProblem("<!doctype html><html>".encodeToByteArray()))
        assertEquals("This file isn't a PDF.", PlanFiles.pdfHeaderProblem(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        // A marker past the first 1024 bytes doesn't count.
        val tooLate = ByteArray(1100) { ' '.code.toByte() } + "%PDF-1.5".encodeToByteArray()
        assertEquals("This file isn't a PDF.", PlanFiles.pdfHeaderProblem(tooLate))
        // Shorter than the marker.
        assertEquals("This file isn't a PDF.", PlanFiles.pdfHeaderProblem("%PD".encodeToByteArray()))
    }

    @Test fun readsTheHeadAcrossShortReads() {
        val data = ByteArray(3000) { (it % 251).toByte() }
        // A provider stream that hands back a few bytes at a time.
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(data)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 7))
        }
        assertArrayEquals(data.copyOf(1024), trickle.readNBytesCompat(1024))
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readNBytesCompat(1024))
    }

    @Test fun drawingJsonRejectsMissingPagesInTheMatchingPdf() {
        val drawing = PlanDrawing(name = "Plans", sha256 = "a".repeat(64))
        val first = PlanPoint(0.0, 0.0)
        val second = PlanPoint(1.0, 1.0)
        val points = listOf(first, second)
        val mark = PlanMark(page = 1, kind = PlanMarkKind.DETAIL_LINK, points = listOf(first), targetPage = 1)
        val zone = PlanZone(name = "Detail", page = 1, first = first, second = second)
        val reference = PlanReference(first, second, Quantity.inches(Rational.ONE).stored(), page = 2)
        val valid = drawing.copy(marks = listOf(mark))
        DrawingJson.validatePages(DrawingJson.decode(DrawingJson.encode(valid)), 2)
        DrawingJson.validatePages(drawing.copy(marks = listOf(mark.copy(targetPage = 5, targetDrawingId = "another"))), 2)

        val invalid = listOf(
            drawing.copy(marks = listOf(mark.copy(page = 2))),
            drawing.copy(marks = listOf(mark.copy(targetPage = 2, targetDrawingId = drawing.id))),
            drawing.copy(measurements = listOf(PlanMeasurement(page = 2, first = first, second = second))),
            drawing.copy(routes = listOf(PlanRunRoute(runId = "run", page = 2, points = points))),
            drawing.copy(paths = listOf(PlanPath(page = 2, points = points))),
            drawing.copy(zones = listOf(zone.copy(page = 2))),
            drawing.copy(zones = listOf(zone.copy(references = listOf(reference)))),
            drawing.copy(calibration = drawing.calibration.copy(references = listOf(reference))),
            drawing.copy(calibration = drawing.calibration.copy(pageModes = mapOf(2 to PlanScaleMode.UNIFORM))),
            drawing.copy(sheetRevisions = mapOf(2 to SheetRevision("A1", "1", 1))),
        )
        for (candidate in invalid) {
            val error = assertThrows(IOException::class.java) { DrawingJson.validatePages(candidate, 2) }
            assertTrue(error.message.orEmpty().endsWith("missing PDF page 3."))
        }
    }
}
