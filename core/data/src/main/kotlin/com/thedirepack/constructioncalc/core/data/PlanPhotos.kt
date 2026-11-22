package com.thedirepack.constructioncalc.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Content-addressed field photo store (`plan-photos/<sha256>.jpg`), keeping photos offline inside app storage. */
class PlanPhotos internal constructor(
    private val directory: File,
    private val context: Context? = null,
    private val validateHeader: (ByteArray) -> String? = ::imageHeaderProblem,
) {
    constructor(context: Context) : this(File(context.filesDir, "plan-photos"), context.applicationContext)

    fun file(sha256: String): File {
        require(PlanFiles.isValidHash(sha256))
        return File(directory, "$sha256.jpg")
    }

    fun garbageCollect(referencedHashes: Set<String>): Int = garbageCollect(directory, referencedHashes)

    /**
     * Imports a photo from [uri], scales the longest edge down to [MAX_IMAGE_EDGE] pixels, and
     * re-encodes as a clean JPEG (stripping EXIF/GPS metadata) keyed by its SHA-256 hash.
     */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val appContext = requireNotNull(context) { "Context is required to import a photo." }
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (error: FileNotFoundException) {
            throw IOException("Couldn't open that photo. If it is stored online, download it first.", error)
        } catch (error: SecurityException) {
            throw IOException("Construction Calc isn't allowed to read that photo. Pick it again from the picker.", error)
        } ?: throw IOException("Couldn't open that photo.")

        val rawTemp = File.createTempFile("photo-raw-", ".tmp", appContext.cacheDir)
        try {
            input.use { stream ->
                rawTemp.outputStream().use { out -> stream.copyBounded(out, MAX_PHOTO_BYTES * 2) }
            }
            val head = rawTemp.inputStream().use { it.readNBytesCompat(HEADER_PEEK_BYTES) }
            if (head.isEmpty()) throw IOException("This image file is empty.")
            if (!looksLikeSupportedDecodeFormat(head)) {
                throw IOException("This file isn't a supported photo image.")
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(rawTemp.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                throw IOException("Couldn't read this image. It may be damaged or an unsupported format.")
            }

            val maxSide = max(width, height)
            var sampleSize = 1
            while (maxSide / (sampleSize * 2) >= MAX_IMAGE_EDGE) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeFile(rawTemp.absolutePath, decodeOpts)
                ?: throw IOException("Couldn't decode this image.")
            var scaled: Bitmap? = null
            val encodedBytes = try {
                val curMax = max(decoded.width, decoded.height)
                val finalBitmap = if (curMax > MAX_IMAGE_EDGE) {
                    val ratio = MAX_IMAGE_EDGE.toFloat() / curMax
                    val targetW = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
                    val targetH = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also { scaled = it }
                } else {
                    decoded
                }
                val out = ByteArrayOutputStream()
                if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IOException("Couldn't encode this photo as JPEG.")
                }
                out.toByteArray()
            } finally {
                if (scaled != null && scaled !== decoded && !scaled!!.isRecycled) scaled!!.recycle()
                if (!decoded.isRecycled) decoded.recycle()
            }
            val staged = stage(encodedBytes.inputStream(), expectedHash = null, checkHeader = true)
            commit(staged)
            staged.sha256
        } finally {
            rawTemp.delete()
        }
    }

    internal class StagedPhoto(val sha256: String, internal val temp: File)

    internal fun stage(
        input: InputStream,
        expectedHash: String? = null,
        checkHeader: Boolean = true,
    ): StagedPhoto {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Couldn't create photo storage.")
        }
        val temp = File.createTempFile("photo-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { output ->
                input.copyBounded(output, MAX_PHOTO_BYTES, digest)
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedHash != null && sha != expectedHash) {
                throw IOException("A photo does not match its project checksum.")
            }
            if (checkHeader) {
                val head = temp.inputStream().use { it.readNBytesCompat(HEADER_PEEK_BYTES) }
                validateHeader(head)?.let { throw IOException(it) }
            }
            return StagedPhoto(sha, temp)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    internal fun commit(staged: StagedPhoto) {
        val destination = file(staged.sha256)
        try {
            if (!destination.isFile && !staged.temp.renameTo(destination) && !destination.isFile) {
                throw IOException("Couldn't save the photo.")
            }
        } finally {
            staged.temp.delete()
        }
    }

    companion object {
        internal const val MAX_PHOTO_BYTES = 15L * 1024 * 1024
        internal const val MAX_IMAGE_EDGE = 2048
        private const val JPEG_QUALITY = 85
        private const val HEADER_PEEK_BYTES = 16

        /** Why [head] cannot be a supported stored photo (JPEG or PNG), or null when valid. */
        internal fun imageHeaderProblem(head: ByteArray): String? {
            if (head.isEmpty()) return "This photo file is empty."
            if (isJpeg(head) || isPng(head)) return null
            return "This file isn't a JPEG or PNG image."
        }

        private fun looksLikeSupportedDecodeFormat(head: ByteArray): Boolean =
            isJpeg(head) || isPng(head) || isWebp(head)

        private fun isJpeg(head: ByteArray): Boolean =
            head.size >= 3 &&
                head[0] == 0xFF.toByte() &&
                head[1] == 0xD8.toByte() &&
                head[2] == 0xFF.toByte()

        private fun isPng(head: ByteArray): Boolean =
            head.size >= 4 &&
                head[0] == 0x89.toByte() &&
                head[1] == 'P'.code.toByte() &&
                head[2] == 'N'.code.toByte() &&
                head[3] == 'G'.code.toByte()

        private fun isWebp(head: ByteArray): Boolean =
            head.size >= 12 &&
                head[0] == 'R'.code.toByte() &&
                head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte() &&
                head[3] == 'F'.code.toByte() &&
                head[8] == 'W'.code.toByte() &&
                head[9] == 'E'.code.toByte() &&
                head[10] == 'B'.code.toByte() &&
                head[11] == 'P'.code.toByte()

        internal fun garbageCollect(directory: File, referencedHashes: Set<String>): Int {
            if (!directory.isDirectory) return 0
            var deleted = 0
            directory.listFiles().orEmpty().forEach { entry ->
                if (!entry.isFile || !entry.name.endsWith(".jpg")) return@forEach
                val hash = entry.name.removeSuffix(".jpg")
                if (!PlanFiles.isValidHash(hash)) return@forEach
                if (hash !in referencedHashes && entry.delete()) deleted++
            }
            return deleted
        }
    }
}
