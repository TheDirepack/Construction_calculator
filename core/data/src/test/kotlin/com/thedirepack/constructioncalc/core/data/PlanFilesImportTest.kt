package com.thedirepack.constructioncalc.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

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
}
