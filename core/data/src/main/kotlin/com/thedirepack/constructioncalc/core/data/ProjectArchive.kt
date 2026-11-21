package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A project document followed by content-addressed original PDFs. */
internal object ProjectArchive {
    private const val PROJECT = "project.json"
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_PDFS = 200

    private fun pdfName(hash: String) = "drawings/$hash.pdf"

    fun write(out: OutputStream, project: Project, now: Long, fileForHash: (String) -> File) {
        val hashes = project.drawings.map { it.sha256 }.distinct()
        if (hashes.size > MAX_PDFS) throw IOException("This project has too many drawings to export.")
        val entries = hashes.associateWith(fileForHash)
        if (entries.values.any { !it.isFile }) throw IOException("Import the missing original PDFs before exporting this project.")
        if (entries.values.sumOf { it.length() } > MAX_TOTAL_BYTES) throw IOException("This project archive is too large.")
        val json = ProjectCodec.encode(project, exportedAt = now).encodeToByteArray()
        if (json.size > PlanFiles.MAX_JSON_BYTES) throw IOException("This project document is too large.")
        ZipOutputStream(object : FilterOutputStream(out) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        }).use { zip ->
            zip.putNextEntry(ZipEntry(PROJECT))
            zip.write(json)
            zip.closeEntry()
            for ((hash, file) in entries) {
                zip.putNextEntry(ZipEntry(pdfName(hash)))
                file.inputStream().use { it.copyBounded(zip, PlanFiles.MAX_PDF_BYTES) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, storePdf: (InputStream, String) -> Unit): Project = ZipInputStream(input).use { zip ->
        if (zip.nextEntry?.name != PROJECT) throw IOException("This ZIP is not a Construction Calc project.")
        val project = ProjectCodec.decode(zip.readBounded(PlanFiles.MAX_JSON_BYTES).decodeToString())
        zip.closeEntry()
        val expected = project.drawings.map { it.sha256 }.distinct().associateBy(::pdfName).toMutableMap()
        if (expected.size > MAX_PDFS) throw IOException("This project has too many drawings.")
        var remaining = MAX_TOTAL_BYTES
        while (true) {
            val entry = zip.nextEntry ?: break
            val hash = expected.remove(entry.name) ?: throw IOException("Unexpected file in the project ZIP.")
            // A limited reader stops oversized archives before any additional bytes reach storage.
            storePdf(object : InputStream() {
                override fun read(): Int {
                    val next = zip.read()
                    if (next >= 0) consume(1)
                    return next
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    zip.read(bytes, offset, length).also { if (it > 0) consume(it) }

                private fun consume(count: Int) {
                    remaining -= count
                    if (remaining < 0) throw IOException("This project archive is too large.")
                }
            }, hash)
            zip.closeEntry()
        }
        if (expected.isNotEmpty()) throw IOException("The project ZIP is missing one or more original PDFs.")
        project
    }
}
