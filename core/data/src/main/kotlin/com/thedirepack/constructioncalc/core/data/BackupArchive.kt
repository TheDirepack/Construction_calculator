package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.AppFile
import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupDocument
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A full backup document followed by all available content-addressed original PDFs. */
internal object BackupArchive {
    internal const val BACKUP = "backup.json"
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val MAX_PDFS = 500

    private fun pdfName(hash: String) = "drawings/$hash.pdf"

    fun write(out: OutputStream, backup: BackupDocument, fileForHash: (String) -> File) {
        val hashes = backup.projects
            .flatMap { it.drawings }
            .map { it.sha256 }
            .distinct()
        val available = hashes.associateWith(fileForHash).filterValues { it.isFile }
        if (available.size > MAX_PDFS) throw IOException("This backup has too many drawings to export.")
        if (available.values.sumOf { it.length() } > MAX_TOTAL_BYTES) throw IOException("This backup archive is too large.")
        val json = AppFileCodec.encodeBackup(backup).encodeToByteArray()
        if (json.size > PlanFiles.MAX_JSON_BYTES) throw IOException("This backup document is too large.")
        ZipOutputStream(object : FilterOutputStream(out) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        }).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP))
            zip.write(json)
            zip.closeEntry()
            for ((hash, file) in available) {
                zip.putNextEntry(ZipEntry(pdfName(hash)))
                file.inputStream().use { it.copyBounded(zip, PlanFiles.MAX_PDF_BYTES) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, storePdf: (InputStream, String) -> Unit): BackupDocument = ZipInputStream(input).use { zip ->
        if (zip.nextEntry?.name != BACKUP) throw IOException("This ZIP is not a Construction Calc backup.")
        val decoded = AppFileCodec.decode(zip.readBounded(PlanFiles.MAX_JSON_BYTES).decodeToString())
        val backup = (decoded as? AppFile.Backup)?.backup
            ?: throw IOException("This ZIP is not a Construction Calc backup.")
        zip.closeEntry()
        val expected = backup.projects
            .flatMap { it.drawings }
            .map { it.sha256 }
            .distinct()
            .associateBy(::pdfName)
            .toMutableMap()
        if (expected.size > MAX_PDFS) throw IOException("This backup has too many drawings.")
        var remaining = MAX_TOTAL_BYTES
        while (true) {
            val entry = zip.nextEntry ?: break
            val hash = expected.remove(entry.name) ?: throw IOException("Unexpected file in the backup ZIP.")
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
                    if (remaining < 0) throw IOException("This backup archive is too large.")
                }
            }, hash)
            zip.closeEntry()
        }
        backup
    }
}
