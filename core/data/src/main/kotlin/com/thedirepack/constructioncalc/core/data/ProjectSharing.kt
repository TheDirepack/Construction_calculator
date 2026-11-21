package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.measure.MeasureFormat

import com.thedirepack.constructioncalc.core.measure.LengthFormat

import android.content.ClipData
import android.content.ComponentName
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
import java.io.PushbackInputStream
import java.util.UUID

/**
 * Moving projects in and out of the app with no permissions:
 * - share: a `.project.json` attachment plus a readable text summary (email, messages, Drive…)
 * - save/open: the system file picker (Storage Access Framework) grants access to one file at a time
 */
object ProjectSharing {

    /** MIME types accepted when opening a file; the content is validated by [ProjectCodec]. */
    val OPEN_MIME_TYPES = arrayOf(ProjectCodec.MIME_TYPE, ZIP_MIME_TYPE, "application/octet-stream", "text/plain", "*/*")

    const val ZIP_MIME_TYPE = "application/zip"

    fun mimeType(project: Project): String = if (project.drawings.isEmpty()) ProjectCodec.MIME_TYPE else ZIP_MIME_TYPE

    fun fileName(project: Project): String =
        if (project.drawings.isEmpty()) ProjectCodec.fileName(project)
        else ProjectCodec.fileName(project).removeSuffix(".json") + ".zip"

    /** [header]: company lines for the text body (`AppSettings.company.headerLines()`). */
    fun shareIntent(
        context: Context,
        project: Project,
        now: Long,
        toolTitle: (String) -> String,
        settings: AppSettings,
    ): Intent {
        // The message text follows the user's units; the attached file always holds base units.
        val format = LengthFormat(settings.unitSystem, settings.precision, settings.lengthDisplay.resolve(null), settings.metricDisplay, settings.metricResolution, settings.feetInchStyle)
        return shareFileIntent(
            context,
            fileName = fileName(project),
            mimeType = mimeType(project),
            subject = project.name,
            body = ProjectText.render(
                project, toolTitle, header = settings.company.headerLines(),
                length = { MeasureFormat.primary(it, format) }, units = settings.unitSystem,
            ),
        ) { writeProject(context, it, project, now) }
    }

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
        val shared = File(context.cacheDir, "shared")
        val expired = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        shared.listFiles()?.filter { it.lastModified() < expired }?.forEach { it.deleteRecursively() }
        val dir = File(shared, UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(dir, fileName)
        try {
            file.outputStream().use(write)
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share $subject").apply {
            val ownTargets = context.packageManager.queryIntentActivities(send, 0)
                .mapNotNull { it.activityInfo }
                .filter { it.packageName == context.packageName }
                .map { ComponentName(it.packageName, it.name) }
                .toTypedArray()
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownTargets)
        }
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
        out.use { writeProject(context, it, project, now) }
    }

    private fun writeProject(context: Context, out: java.io.OutputStream, project: Project, now: Long) {
        if (project.drawings.isEmpty()) out.write(ProjectCodec.encode(project, exportedAt = now).encodeToByteArray())
        else ProjectArchive.write(out, project, now, PlanFiles(context)::file)
    }

    /** Reads a project or backup file from a picked or received file. */
    @Throws(IOException::class, ProjectFormatException::class)
    fun readAppFile(context: Context, uri: Uri): AppFile {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open that file")
        return PushbackInputStream(input, 4).use { stream ->
            val signature = ByteArray(4)
            var count = 0
            while (count < signature.size) {
                val chunk = stream.read(signature, count, signature.size - count)
                if (chunk < 0) break
                count += chunk
            }
            stream.unread(signature, 0, count)
            if (signature.contentEquals(byteArrayOf(0x50, 0x4b, 3, 4))) {
                val files = PlanFiles(context)
                AppFile.SingleProject(ProjectArchive.read(stream) { pdf, hash ->
                    files.store(pdf, expectedHash = hash, validatePdf = true)
                })
            } else {
                AppFileCodec.decode(stream.readBounded(PlanFiles.MAX_JSON_BYTES).decodeToString())
            }
        }
    }

    /** Reads a project from a picked or received file. */
    @Throws(IOException::class, ProjectFormatException::class)
    fun read(context: Context, uri: Uri): Project =
        (readAppFile(context, uri) as? AppFile.SingleProject)?.project
            ?: throw ProjectFormatException("This isn't a Construction Calc project file.")
}
