package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** A project file (`Name.project.json`): plain pretty-printed JSON wrapping one [Project]. */
@Serializable
data class ProjectDocument(
    val format: String = ProjectCodec.FORMAT,
    val version: Int = ProjectCodec.VERSION,
    @SerialName("exported_at") val exportedAt: Long? = null,
    val app: String = "Construction Calc",
    val project: Project,
)

class ProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ProjectCodec {
    const val FORMAT = "construction-calc/project"
    const val VERSION = 1
    /** Files are ordinary JSON; the suffix just makes a project file recognizable by name. */
    const val FILE_SUFFIX = ".project.json"
    const val MIME_TYPE = "application/json"

    /** Lenient, so files from newer app versions (extra fields) still open. */
    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun encode(project: Project, exportedAt: Long? = null): String =
        json.encodeToString(ProjectDocument.serializer(), ProjectDocument(exportedAt = exportedAt, project = project))

    fun decode(text: String): Project {
        val document = try {
            json.decodeFromString(ProjectDocument.serializer(), text)
        } catch (e: SerializationException) {
            throw ProjectFormatException("This isn't a Construction Calc project file.", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("This project file is damaged.", e)
        }
        if (document.format != FORMAT) throw ProjectFormatException("This isn't a Construction Calc project file.")
        // Files from newer versions (document.version > VERSION) are read best-effort: unknown fields are ignored.
        return document.project
    }

    /** A safe file name like `Smith Residence.project.json`. */
    fun fileName(project: Project): String {
        val base = project.name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().ifEmpty { "Project" }
        return "${base.take(60)}$FILE_SUFFIX"
    }
}

/** Encodes and decodes a tool's own input class to the [ProjectItem.inputs] object. */
object ToolInputs {
    val json = ProjectCodec.json

    inline fun <reified T> encode(value: T): JsonObject = json.encodeToJsonElement(value).jsonObject

    /** Null when the inputs can't be read (e.g. a file from an incompatible version). */
    inline fun <reified T> decode(inputs: JsonObject): T? =
        runCatching { json.decodeFromJsonElement<T>(inputs) }.getOrNull()
}
