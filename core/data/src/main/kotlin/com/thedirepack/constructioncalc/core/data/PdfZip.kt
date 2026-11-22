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
    private val maxPhotos: Int = 500,
) {
    fun write(out: OutputStream, json: ByteArray, hashes: Collection<String>, fileForHash: (String) -> File) =
        write(out, json, hashes, fileForHash, emptyList(), { File("") })

    /**
     * Writes [json], each available PDF for [hashes], and any available photos for [photoHashes].
     * Missing PDF files fail only when [requireAllPdfs]; missing photos are silently skipped.
     */
    fun write(
        out: OutputStream,
        json: ByteArray,
        hashes: Collection<String>,
        fileForHash: (String) -> File,
        photoHashes: Collection<String>,
        photoFileForHash: (String) -> File,
        requireAllMedia: Boolean = false,
    ) {
        if (json.size > PlanFiles.MAX_JSON_BYTES) throw IOException("This $label document is too large.")
        val files = hashes.distinct().associateWith(fileForHash)
        if (requireAllPdfs && files.values.any { !it.isFile }) throw IOException("Import the missing original PDFs before exporting this $label.")
        val available = files.filterValues { it.isFile }
        if (available.size > maxPdfs) throw IOException("This $label has too many drawings to export.")
        val availablePhotos = photoHashes.distinct().associateWith(photoFileForHash).filterValues { it.isFile }
        if (requireAllMedia && (available.size != files.size || availablePhotos.size != photoHashes.distinct().size)) {
            throw IOException("Backup media changed during export. Review the missing files and try again.")
        }
        if (availablePhotos.size > maxPhotos) throw IOException("This $label has too many photos to export.")
        val totalMediaBytes = available.values.sumOf { it.length() } + availablePhotos.values.sumOf { it.length() }
        if (totalMediaBytes > maxTotalBytes) throw IOException("This $label archive is too large.")
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
            for ((hash, file) in availablePhotos) {
                zip.putNextEntry(ZipEntry(photoName(hash)))
                file.inputStream().use { it.copyBounded(zip, PlanPhotos.MAX_PHOTO_BYTES) }
                zip.closeEntry()
            }
        }
    }

    fun <T> read(input: InputStream, decode: (String) -> Pair<T, Collection<String>>, storePdf: (InputStream, String) -> Unit): T =
        read(input, decode, storePdf, { emptyList() }) { _, _ -> }

    /**
     * Reads the document with [decode] (which returns the value and the drawing hashes it
     * references), then hands each PDF to [storePdf] and each referenced photo to [storePhoto].
     * Any other entry, an unreferenced attachment, too many attachments, or too many bytes is
     * refused before more bytes reach storage.
     */
    fun <T> read(
        input: InputStream,
        decode: (String) -> Pair<T, Collection<String>>,
        storePdf: (InputStream, String) -> Unit,
        photoHashesOf: (T) -> Collection<String>,
        storePhoto: (InputStream, String) -> Unit,
    ): T =
        ZipInputStream(input).use { zip -> readOpen(zip, zip.nextEntry?.name, decode, storePdf, photoHashesOf, storePhoto) }

    fun <T> readOpen(zip: ZipInputStream, firstName: String?, decode: (String) -> Pair<T, Collection<String>>, storePdf: (InputStream, String) -> Unit): T =
        readOpen(zip, firstName, decode, storePdf, { emptyList() }) { _, _ -> }

    /** As [read], for a [zip] whose first entry ([firstName]) is already open. */
    fun <T> readOpen(
        zip: ZipInputStream,
        firstName: String?,
        decode: (String) -> Pair<T, Collection<String>>,
        storePdf: (InputStream, String) -> Unit,
        photoHashesOf: (T) -> Collection<String>,
        storePhoto: (InputStream, String) -> Unit,
    ): T {
        if (firstName != document) throw IOException("This ZIP is not a Construction Calc $label.")
        val (value, hashes) = decode(zip.readBounded(PlanFiles.MAX_JSON_BYTES).decodeToString())
        zip.closeEntry()
        val expected = hashes.distinct().associateBy(::pdfName).toMutableMap()
        val expectedPhotos = photoHashesOf(value).distinct().associateBy(::photoName).toMutableMap()
        var received = 0
        var receivedPhotos = 0
        var remaining = maxTotalBytes
        while (true) {
            val entry = zip.nextEntry ?: break
            val pdfHash = expected.remove(entry.name)
            val photoHash = if (pdfHash == null) expectedPhotos.remove(entry.name) else null
            when {
                pdfHash != null -> {
                    if (++received > maxPdfs) throw IOException("This $label has too many drawings.")
                    storePdf(boundedEntryStream(zip) { remaining -= it; remaining >= 0 }, pdfHash)
                }
                photoHash != null -> {
                    if (++receivedPhotos > maxPhotos) throw IOException("This $label has too many photos.")
                    var photoRemaining = PlanPhotos.MAX_PHOTO_BYTES
                    storePhoto(
                        boundedEntryStream(zip) {
                            remaining -= it
                            photoRemaining -= it
                            remaining >= 0 && photoRemaining >= 0
                        },
                        photoHash,
                    )
                }
                else -> throw IOException("Unexpected file in the $label ZIP.")
            }
            zip.closeEntry()
        }
        if (requireAllPdfs && expected.isNotEmpty()) throw IOException("The $label ZIP is missing one or more original PDFs.")
        return value
    }

    private fun boundedEntryStream(zip: ZipInputStream, consumeOk: (Int) -> Boolean): InputStream =
        object : InputStream() {
            override fun read(): Int = zip.read().also {
                if (it >= 0 && !consumeOk(1)) throw IOException("This $label archive is too large.")
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                zip.read(bytes, offset, length).also {
                    if (it > 0 && !consumeOk(it)) throw IOException("This $label archive is too large.")
                }
        }

    private fun pdfName(hash: String) = "drawings/$hash.pdf"
    private fun photoName(hash: String) = "photos/$hash.jpg"
}
