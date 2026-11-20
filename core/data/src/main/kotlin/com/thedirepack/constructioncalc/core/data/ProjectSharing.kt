package com.thedirepack.constructioncalc.core.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.thedirepack.constructioncalc.core.model.AppFile
import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.ProjectFormatException
import com.thedirepack.constructioncalc.core.model.ProjectText
import java.io.File
import java.io.IOException

/**
 * Moving projects in and out of the app with no permissions:
 * - share: a `.project.json` attachment plus a readable text summary (email, messages, Drive…)
 * - save/open: the system file picker (Storage Access Framework) grants access to one file at a time
 */
object ProjectSharing {

    /** MIME types accepted when opening a file; the content is validated by [ProjectCodec]. */
    val OPEN_MIME_TYPES = arrayOf(ProjectCodec.MIME_TYPE, "application/octet-stream", "text/plain", "*/*")

    /** [header]: company lines for the text body (`AppSettings.company.headerLines()`). */
    fun shareIntent(
        context: Context,
        project: Project,
        now: Long,
        toolTitle: (String) -> String,
        header: List<String> = emptyList(),
    ): Intent =
        fileShareIntent(
            context,
            fileName = ProjectCodec.fileName(project),
            content = ProjectCodec.encode(project, exportedAt = now),
            subject = project.name,
            body = ProjectText.render(project, toolTitle, header = header),
        )

    /** Shares a backup file (everything, or a subset such as all projects). */
    fun shareBackupIntent(context: Context, backup: BackupDocument, fileName: String, toolTitle: (String) -> String): Intent {
        val body = buildString {
            appendLine("Construction Calc backup")
            backup.describe(toolTitle).forEach { appendLine("• $it") }
            appendLine()
            append("Open the attached file in Construction Calc (Settings › Data › Import) to restore it.")
        }
        return fileShareIntent(context, fileName, AppFileCodec.encodeBackup(backup), fileName.substringBefore('.'), body)
    }

    private fun fileShareIntent(context: Context, fileName: String, content: String, subject: String, body: String): Intent =
        shareFileIntent(context, fileName, ProjectCodec.MIME_TYPE, subject, body) { it.write(content.toByteArray()) }

    /**
     * Shares any generated file (PDF schedule, CSV…) with no permissions: [write] fills a cache
     * file that is offered through the FileProvider.
     */
    fun shareFileIntent(
        context: Context,
        fileName: String,
        mimeType: String,
        subject: String,
        body: String,
        write: (java.io.OutputStream) -> Unit,
    ): Intent {
        val dir = File(context.cacheDir, "shared").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // only keep the file being shared
        }
        val file = File(dir, fileName)
        file.outputStream().use(write)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share $subject")
    }

    /** Writes any text (a project or backup file) to a document picked with ACTION_CREATE_DOCUMENT. */
    @Throws(IOException::class)
    fun writeText(context: Context, uri: Uri, text: String) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to that file")
        out.use { it.write(text.toByteArray()) }
    }

    fun shareTextIntent(subject: String, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(send, "Share $subject")
    }

    /** Writes to a document the user picked with ACTION_CREATE_DOCUMENT. */
    @Throws(IOException::class)
    fun write(context: Context, uri: Uri, project: Project, now: Long) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to that file")
        out.use { it.write(ProjectCodec.encode(project, exportedAt = now).toByteArray()) }
    }

    /** Reads a project or backup file from a picked or received file. */
    @Throws(IOException::class, ProjectFormatException::class)
    fun readAppFile(context: Context, uri: Uri): AppFile = AppFileCodec.decode(readText(context, uri))

    /** Reads a project from a picked or received file. */
    @Throws(IOException::class, ProjectFormatException::class)
    fun read(context: Context, uri: Uri): Project = ProjectCodec.decode(readText(context, uri))

    private fun readText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open that file")
        val text = input.use { stream ->
            val bytes = stream.readNBytesCompat(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) throw ProjectFormatException("That file is too large to be a project.")
            bytes.decodeToString()
        }
        return text
    }

    private const val MAX_FILE_BYTES = 20 * 1024 * 1024

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (buffer.size() < limit) {
            val n = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (n < 0) break
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
