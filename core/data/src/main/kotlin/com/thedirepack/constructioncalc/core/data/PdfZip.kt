package com.thedirepack.constructioncalc.core.data

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The one ZIP layout both project and backup archives use: a JSON document named [document]
 * first, then content-addressed original PDFs at `drawings/<sha256>.pdf`. Export and import apply
 * the same limits, so anything this writes it can read back.
 *
 * @property label "project" or "backup", for messages.
 * @property requireAllPdfs a project must carry every drawing; a backup carries the ones available.
 */
internal class PdfZip(
    val document: String,
    private val label: String,
    private val maxTotalBytes: Long,
    private val maxPdfs: Int,
    private val requireAllPdfs: Boolean,
) {
    /** Writes [json] then the PDF for each of [hashes] ([fileForHash]); missing files fail only when [requireAllPdfs]. */
    fun write(out: OutputStream, json: ByteArray, hashes: Collection<String>, fileForHash: (String) -> File) {
        if (json.size > PlanFiles.MAX_JSON_BYTES) throw IOException("This $label document is too large.")
        val files = hashes.distinct().associateWith(fileForHash)
        if (requireAllPdfs && files.values.any { !it.isFile }) throw IOException("Import the missing original PDFs before exporting this $label.")
        val available = files.filterValues { it.isFile }
        if (available.size > maxPdfs) throw IOException("This $label has too many drawings to export.")
        if (available.values.sumOf { it.length() } > maxTotalBytes) throw IOException("This $label archive is too large.")
        // The caller owns [out]: closing the ZIP finishes it without closing the stream underneath.
        ZipOutputStream(object : FilterOutputStream(out) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        }).use { zip ->
            zip.putNextEntry(ZipEntry(document))
            zip.write(json)
            zip.closeEntry()
            for ((hash, file) in available) {
                zip.putNextEntry(ZipEntry(pdfName(hash)))
                file.inputStream().use { it.copyBounded(zip, PlanFiles.MAX_PDF_BYTES) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads the document with [decode] (which returns the value and the drawing hashes it
     * references), then hands each PDF to [storePdf]. Any other entry, a PDF the document doesn't
     * reference, too many PDFs or too many bytes is refused before more bytes reach storage.
     */
    fun <T> read(input: InputStream, decode: (String) -> Pair<T, Collection<String>>, storePdf: (InputStream, String) -> Unit): T =
        ZipInputStream(input).use { zip -> readOpen(zip, zip.nextEntry?.name, decode, storePdf) }

    /** As [read], for a [zip] whose first entry ([firstName]) is already open. */
    fun <T> readOpen(zip: ZipInputStream, firstName: String?, decode: (String) -> Pair<T, Collection<String>>, storePdf: (InputStream, String) -> Unit): T {
        if (firstName != document) throw IOException("This ZIP is not a Construction Calc $label.")
        val (value, hashes) = decode(zip.readBounded(PlanFiles.MAX_JSON_BYTES).decodeToString())
        zip.closeEntry()
        val expected = hashes.distinct().associateBy(::pdfName).toMutableMap()
        var received = 0
        var remaining = maxTotalBytes
        while (true) {
            val entry = zip.nextEntry ?: break
            val hash = expected.remove(entry.name) ?: throw IOException("Unexpected file in the $label ZIP.")
            if (++received > maxPdfs) throw IOException("This $label has too many drawings.")
            storePdf(object : InputStream() {
                override fun read(): Int = zip.read().also { if (it >= 0) consume(1) }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    zip.read(bytes, offset, length).also { if (it > 0) consume(it) }

                private fun consume(count: Int) {
                    remaining -= count
                    if (remaining < 0) throw IOException("This $label archive is too large.")
                }
            }, hash)
            zip.closeEntry()
        }
        if (requireAllPdfs && expected.isNotEmpty()) throw IOException("The $label ZIP is missing one or more original PDFs.")
        return value
    }

    private fun pdfName(hash: String) = "drawings/$hash.pdf"
}
