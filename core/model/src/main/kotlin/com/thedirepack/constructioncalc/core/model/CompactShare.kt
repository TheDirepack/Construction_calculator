package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Compact, SMS-friendly text encoding for projects (without PDFs) and backups:
 * minified JSON -> GZIP -> URL-safe unpadded Base64 with a recognizable `CC1:` prefix.
 *
 * Payloads can be pasted alone or embedded inside a short text message; [decode] extracts
 * the `CC1:` token cleanly and enforces strict size bounds against compressed-bomb inputs.
 */
object CompactShare {
    const val PREFIX = "CC1:"
    private const val MAX_ENCODED_CHARS = 512 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 10 * 1024 * 1024

    // Minified JSON with defaults omitted to minimize SMS size, while keeping explicitNulls = true
    // so deliberately cleared nullable fields with non-null defaults still round-trip faithfully.
    // Built fresh (not from fileJson): a builder copying a pretty-print indent rejects prettyPrint = false.
    private val compactJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
        explicitNulls = true
    }

    /** Defaults are omitted, but `format`/`version` must stay so [AppFileCodec.decodeJson] can tell files apart. */
    private fun withHeader(body: JsonElement, format: String, version: Int): String =
        JsonObject(
            buildMap {
                put("format", JsonPrimitive(format))
                put("version", JsonPrimitive(version))
                putAll(body.jsonObject)
            },
        ).toString()

    private val tokenRegex = Regex("""CC1:[A-Za-z0-9_-]+""")

    /** Encodes a single [Project] as a compact `CC1:…` payload. */
    fun encode(project: Project, exportedAt: Long? = null): String {
        val doc = ProjectDocument(exportedAt = exportedAt, project = project)
        val body = compactJson.encodeToJsonElement(ProjectDocument.serializer(), doc)
        return compress(withHeader(body, doc.format, doc.version))
    }

    /** Encodes a [BackupDocument] as a compact `CC1:…` payload. */
    fun encodeBackup(backup: BackupDocument): String {
        val body = compactJson.encodeToJsonElement(BackupDocument.serializer(), backup)
        return compress(withHeader(body, backup.format, backup.version))
    }

    /** A short SMS-friendly message containing the project name and its compact payload. */
    fun shareMessage(project: Project, exportedAt: Long? = null): String =
        "${project.name} (Construction Calc)\n${encode(project, exportedAt)}"

    /** True when [text] contains a `CC1:…` compact share payload. */
    fun isCompactShare(text: String): Boolean = extractPayload(text) != null

    /** Decodes a `CC1:…` compact share payload (standalone or inside a message) into an [AppFile]. */
    fun decode(text: String): AppFile {
        val payload = extractPayload(text)
            ?: throw ProjectFormatException("This isn't a Construction Calc compact share.")
        if (payload.length > MAX_ENCODED_CHARS) {
            throw ProjectFormatException("This compact share payload is too large.")
        }
        val compressed = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("This compact share payload is damaged.", e)
        }
        val json = try {
            decompressBounded(compressed)
        } catch (e: IOException) {
            throw ProjectFormatException("This compact share payload is damaged.", e)
        }
        return AppFileCodec.decodeJson(json)
    }

    /** Decodes a compact share payload and requires it to be a single [Project]. */
    fun decodeProject(text: String): Project =
        (decode(text) as? AppFile.SingleProject)?.project
            ?: throw ProjectFormatException("This compact share does not contain a single project.")

    internal fun extractPayload(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith(PREFIX)) {
            val rest = trimmed.removePrefix(PREFIX).filterNot { it.isWhitespace() }
            if (rest.isNotEmpty() && rest.all(::isBase64UrlChar)) return rest
        }
        val match = tokenRegex.find(text) ?: return null
        val payload = match.value.removePrefix(PREFIX)
        return payload.takeIf { it.isNotEmpty() }
    }

    private fun isBase64UrlChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    /** Compresses [json]; refuses anything [decode] would refuse, so every share can be read back. */
    private fun compress(json: String): String {
        val bytes = json.encodeToByteArray()
        if (bytes.size > MAX_DECOMPRESSED_BYTES) throw ProjectFormatException(TOO_LARGE)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip -> gzip.write(bytes) }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        if (encoded.length > MAX_ENCODED_CHARS) throw ProjectFormatException(TOO_LARGE)
        return PREFIX + encoded
    }

    private const val TOO_LARGE = "This is too large to share as compact text. Save it to a file instead."

    private fun decompressBounded(compressed: ByteArray): String {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = gzip.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw IOException("Compact share exceeds decompressed size limit.")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray().decodeToString()
    }
}
