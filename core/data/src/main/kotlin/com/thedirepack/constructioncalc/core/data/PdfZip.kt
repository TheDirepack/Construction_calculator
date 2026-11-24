package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupManifest
import com.thedirepack.constructioncalc.core.model.ProjectFormatException
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The one ZIP layout both project and backup archives use: a JSON document named [document]
 * first, then content-addressed original PDFs at `drawings/<sha256>.pdf`, photos at
 * `photos/<sha256>.jpg`, and an integrity `manifest.json`. Export and import apply the same limits,
 * so anything this writes it can read back.
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
     * Writes [json], each available PDF for [hashes], any available photos for [photoHashes],
     * and `manifest.json` after the primary document.
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
        createdAt: Long = System.currentTimeMillis(),
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
        val mediaSha256 = linkedMapOf<String, String>()
        // The caller owns [out]: closing the ZIP finishes it without closing the stream underneath.
        ZipOutputStream(object : FilterOutputStream(out) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        }).use { zip ->
            zip.putNextEntry(ZipEntry(document))
            zip.write(json)
            zip.closeEntry()
            for ((hash, file) in available) {
                val entryName = pdfName(hash)
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyBounded(zip, PlanFiles.MAX_PDF_BYTES, digest) }
                zip.closeEntry()
                mediaSha256[entryName] = sha256Hex(digest.digest())
            }
            for ((hash, file) in availablePhotos) {
                val entryName = photoName(hash)
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyBounded(zip, PlanPhotos.MAX_PHOTO_BYTES, digest) }
                zip.closeEntry()
                mediaSha256[entryName] = sha256Hex(digest.digest())
            }
            val manifest = BackupManifest(
                kind = label,
                createdAt = createdAt,
                documentSha256 = sha256Hex(json),
                mediaSha256 = mediaSha256,
            )
            zip.putNextEntry(ZipEntry(AppFileCodec.MANIFEST_FILE))
            zip.write(AppFileCodec.encodeManifest(manifest).encodeToByteArray())
            zip.closeEntry()
        }
    }

    fun <T> read(input: InputStream, decode: (String) -> Pair<T, Collection<String>>, storePdf: (InputStream, String) -> Unit): T =
        read(input, decode, storePdf, { emptyList() }) { _, _ -> }

    /**
     * Reads the document with [decode] (which returns the value and the drawing hashes it
     * references), then hands each PDF to [storePdf] and each referenced photo to [storePhoto].
     * Verifies `manifest.json` checksums when present while still accepting legacy archives without
     * a manifest. Any unexpected entry, unreferenced attachment, too many attachments, or too many
     * bytes is refused before more bytes reach storage.
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
        val rawDocumentBytes = zip.readBounded(PlanFiles.MAX_JSON_BYTES)
        val actualDocSha256 = sha256Hex(rawDocumentBytes)
        val (value, hashes) = decode(rawDocumentBytes.decodeToString())
        zip.closeEntry()
        val expected = hashes.distinct().associateBy(::pdfName).toMutableMap()
        val expectedPhotos = photoHashesOf(value).distinct().associateBy(::photoName).toMutableMap()
        var received = 0
        var receivedPhotos = 0
        var remaining = maxTotalBytes
        var manifest: BackupManifest? = null
        val actualMediaSha256 = linkedMapOf<String, String>()
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == AppFileCodec.MANIFEST_FILE) {
                if (manifest != null) throw IOException("Duplicate manifest in the $label ZIP.")
                val manifestText = zip.readBounded(PlanFiles.MAX_JSON_BYTES).decodeToString()
                val parsed = try {
                    AppFileCodec.decodeManifest(manifestText)
                } catch (e: ProjectFormatException) {
                    throw IOException(e.message ?: "Invalid archive manifest.", e)
                }
                if (parsed.kind.isNotBlank() && parsed.kind != label) {
                    throw IOException("Archive manifest kind does not match $label.")
                }
                if (!parsed.documentSha256.equals(actualDocSha256, ignoreCase = true)) {
                    throw IOException("Archive integrity check failed: $document checksum mismatch.")
                }
                manifest = parsed
                zip.closeEntry()
                continue
            }
            val pdfHash = expected.remove(entry.name)
            val photoHash = if (pdfHash == null) expectedPhotos.remove(entry.name) else null
            when {
                pdfHash != null -> {
                    if (++received > maxPdfs) throw IOException("This $label has too many drawings.")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bounded = boundedEntryStream(zip, digest) { remaining -= it; remaining >= 0 }
                    storePdf(bounded, pdfHash)
                    drain(bounded)
                    actualMediaSha256[entry.name] = sha256Hex(digest.digest())
                }
                photoHash != null -> {
                    if (++receivedPhotos > maxPhotos) throw IOException("This $label has too many photos.")
                    var photoRemaining = PlanPhotos.MAX_PHOTO_BYTES
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bounded = boundedEntryStream(zip, digest) {
                        remaining -= it
                        photoRemaining -= it
                        remaining >= 0 && photoRemaining >= 0
                    }
                    storePhoto(bounded, photoHash)
                    drain(bounded)
                    actualMediaSha256[entry.name] = sha256Hex(digest.digest())
                }
                else -> throw IOException("Unexpected file in the $label ZIP.")
            }
            zip.closeEntry()
        }
        if (requireAllPdfs && expected.isNotEmpty()) throw IOException("The $label ZIP is missing one or more original PDFs.")
        manifest?.let { verifyManifestMedia(it, actualMediaSha256) }
        return value
    }

    private fun verifyManifestMedia(manifest: BackupManifest, actualMediaSha256: Map<String, String>) {
        for ((entryName, expectedHash) in manifest.mediaSha256) {
            val actualHash = actualMediaSha256[entryName]
                ?: throw IOException("Archive integrity check failed: missing $entryName.")
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw IOException("Archive integrity check failed: $entryName checksum mismatch.")
            }
        }
        if (actualMediaSha256.keys != manifest.mediaSha256.keys) {
            throw IOException("Archive integrity check failed: media entries do not match manifest.")
        }
    }

    private fun drain(stream: InputStream) {
        val buf = ByteArray(8 * 1024)
        while (stream.read(buf) >= 0) {
            // Consuming remaining entry bytes guarantees bounded size and complete SHA-256 verification.
        }
    }

    private fun boundedEntryStream(
        zip: ZipInputStream,
        digest: MessageDigest,
        consumeOk: (Int) -> Boolean,
    ): InputStream =
        object : InputStream() {
            override fun read(): Int = zip.read().also {
                if (it >= 0) {
                    if (!consumeOk(1)) throw IOException("This $label archive is too large.")
                    digest.update(it.toByte())
                }
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                zip.read(bytes, offset, length).also {
                    if (it > 0) {
                        if (!consumeOk(it)) throw IOException("This $label archive is too large.")
                        digest.update(bytes, offset, it)
                    }
                }
        }

    private fun sha256Hex(bytes: ByteArray): String =
        if (bytes.size == 32) bytes.joinToString("") { "%02x".format(it) }
        else MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pdfName(hash: String) = "drawings/$hash.pdf"
    private fun photoName(hash: String) = "photos/$hash.jpg"
}
