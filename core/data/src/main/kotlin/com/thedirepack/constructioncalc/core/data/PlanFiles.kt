package com.thedirepack.constructioncalc.core.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class PlanFiles(private val context: Context) {
    private val directory = File(context.filesDir, "plan-pdfs")

    fun file(sha256: String): File {
        require(isValidHash(sha256))
        return File(directory, "$sha256.pdf")
    }

    /**
     * Deletes stored drawing PDFs whose SHA-256 hash is not in [referencedHashes].
     * Leaves in-flight `.tmp` files and non-hash entries untouched. Returns the number deleted.
     */
    fun garbageCollect(referencedHashes: Set<String>): Int = garbageCollect(directory, referencedHashes)

    /**
     * Copies a picked PDF into app storage (so no persistable URI permission is needed and the plan
     * works offline), checks that it's a readable PDF, and returns a drawing for it. Fails with a
     * message for the user on files that aren't PDFs, are empty, too large, damaged or password
     * protected, or that the provider won't hand over (a cloud file that isn't downloaded).
     */
    suspend fun import(uri: Uri): PlanDrawing = withContext(Dispatchers.IO) {
        val name = runCatching { displayName(context, uri, "Drawing.pdf") }.getOrDefault("Drawing.pdf")
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: java.io.FileNotFoundException) {
            throw IOException("Couldn't open the PDF. If it's in cloud storage, make it available offline and try again.", error)
        } catch (error: SecurityException) {
            throw IOException("Construction Calc isn't allowed to read that file. Pick it again from the file picker.", error)
        } ?: throw IOException("Couldn't open the PDF.")
        val sha256 = input.use { store(it, validatePdf = true) }
        PlanDrawing(name = name, sha256 = sha256)
    }

    suspend fun importDrawingJson(uri: Uri): PlanDrawing = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open drawing JSON.")
        val drawing = input.use { DrawingJson.decode(it.readBounded(MAX_JSON_BYTES).decodeToString()) }
        val source = file(drawing.sha256)
        if (!source.isFile) {
            throw IOException("Import the matching original PDF before opening this drawing JSON.")
        }
        DrawingJson.validatePages(drawing, validate(source))
        drawing
    }

    internal class StagedPdf(val sha256: String, internal val temp: File)

    internal fun stage(input: InputStream, expectedHash: String? = null, validatePdf: Boolean = false): StagedPdf {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Couldn't create drawing storage.")
        }
        val temp = File.createTempFile("drawing-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { output ->
                input.copyBounded(output, MAX_PDF_BYTES, digest)
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedHash != null && sha != expectedHash) throw IOException("A PDF does not match its project checksum.")
            if (validatePdf) validate(temp)
            return StagedPdf(sha, temp)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    internal fun commit(staged: StagedPdf) {
        val destination = file(staged.sha256)
        try {
            if (!destination.isFile && !staged.temp.renameTo(destination) && !destination.isFile) {
                throw IOException("Couldn't save the drawing.")
            }
        } finally {
            staged.temp.delete()
        }
    }

    internal fun store(input: InputStream, expectedHash: String? = null, validatePdf: Boolean = false): String {
        val staged = stage(input, expectedHash, validatePdf)
        commit(staged)
        return staged.sha256
    }

    private fun validate(file: File): Int {
        val head = file.inputStream().use { it.readNBytesCompat(PDF_HEADER_WINDOW) }
        pdfHeaderProblem(head)?.let { throw IOException(it) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: SecurityException) {
            descriptor.close()
            throw IOException("This PDF is password protected. Save a copy without the password and import that.", error)
        } catch (error: Exception) {
            descriptor.close()
            throw IOException("Couldn't read this PDF. It may be damaged or incomplete.", error)
        }
        return renderer.use {
            if (it.pageCount !in 1..MAX_PAGES) throw IOException("This PDF must have between 1 and $MAX_PAGES pages.")
            // Some damaged files open but fail on their first page; catch that now, not in the editor.
            try {
                it.openPage(0).close()
            } catch (error: Exception) {
                throw IOException("Couldn't read the first page of this PDF. It may be damaged.", error)
            }
            it.pageCount
        }
    }

    companion object {
        internal const val MAX_JSON_BYTES = 20 * 1024 * 1024
        internal const val MAX_PDF_BYTES = 150L * 1024 * 1024
        internal const val MAX_PAGES = 1_000

        /** PDF readers accept the `%PDF-` marker anywhere in the first 1024 bytes. */
        internal const val PDF_HEADER_WINDOW = 1024

        /** Why [head] (a file's first bytes) can't be a PDF, or null when it looks like one. */
        internal fun pdfHeaderProblem(head: ByteArray): String? {
            if (head.isEmpty()) return "This file is empty."
            val marker = "%PDF-".encodeToByteArray()
            val limit = minOf(head.size, PDF_HEADER_WINDOW) - marker.size
            for (start in 0..limit) {
                if ((marker.indices).all { head[start + it] == marker[it] }) return null
            }
            return "This file isn't a PDF."
        }

        internal fun isValidHash(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        internal fun garbageCollect(directory: File, referencedHashes: Set<String>): Int {
            if (!directory.isDirectory) return 0
            var deleted = 0
            directory.listFiles().orEmpty().forEach { entry ->
                if (!entry.isFile || !entry.name.endsWith(".pdf")) return@forEach
                val hash = entry.name.removeSuffix(".pdf")
                if (!isValidHash(hash)) return@forEach
                if (hash !in referencedHashes && entry.delete()) deleted++
            }
            return deleted
        }
    }
}

internal object DrawingJson {
    private const val FORMAT = "construction-calc/drawing"

    fun encode(drawing: PlanDrawing): String =
        ProjectCodec.json.encodeToString(buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("version", JsonPrimitive(1))
            put("drawing", ProjectCodec.json.encodeToJsonElement(PlanDrawing.serializer(), drawing))
        })

    /** Detail links to other drawings refer to those PDFs, not the current document. */
    fun validatePages(drawing: PlanDrawing, pageCount: Int) {
        fun check(page: Int, label: String) {
            if (page !in 0 until pageCount) throw IOException("$label refers to missing PDF page ${page.toLong() + 1}.")
        }

        drawing.marks.forEach { mark ->
            check(mark.page, "A markup")
            if (mark.targetDrawingId == null || mark.targetDrawingId == drawing.id) {
                mark.targetPage?.let { check(it, "A detail link") }
            }
        }
        drawing.measurements.forEach { check(it.page, "A measurement") }
        drawing.routes.forEach { check(it.page, "A run route") }
        drawing.paths.forEach { check(it.page, "A path") }
        drawing.zones.forEach { zone ->
            check(zone.page, "A scale zone")
            zone.references.forEach { check(it.page, "A scale zone reference") }
        }
        drawing.calibration.references.forEach { check(it.page, "A calibration reference") }
        drawing.calibration.pageModes.keys.forEach { check(it, "A calibration mode") }
        drawing.sheetRevisions.keys.forEach { check(it, "A sheet revision") }
    }

    fun decode(content: String): PlanDrawing {
        val parts = Json.parseToJsonElement(content).jsonObject
        if (parts["format"] != JsonPrimitive(FORMAT) || parts["version"] != JsonPrimitive(1)) {
            throw IOException("This is not a supported drawing JSON file.")
        }
        val drawing = parts["drawing"] ?: throw IOException("Drawing data is missing.")
        return ProjectCodec.json.decodeFromJsonElement(PlanDrawing.serializer(), drawing)
    }
}

internal fun displayName(context: Context, uri: Uri, fallback: String): String {
    val supplied = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    return supplied?.trim()?.take(240)?.ifBlank { null } ?: fallback
}

/** Up to [count] bytes from the start of the stream (API 26 has no `readNBytes`). */
internal fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

internal fun InputStream.readBounded(limit: Int): ByteArray = java.io.ByteArrayOutputStream().use {
    copyBounded(it, limit.toLong())
    it.toByteArray()
}

internal fun InputStream.copyBounded(output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return total
        total += count
        if (total > limit) throw IOException("This file exceeds the supported size limit.")
        digest?.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
}
