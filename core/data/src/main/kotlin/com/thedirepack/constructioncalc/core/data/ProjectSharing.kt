package com.thedirepack.constructioncalc.core.data

import java.util.zip.ZipInputStream
import java.io.InputStream
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
import java.time.ZoneId
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

    fun backupFileName(now: Long, label: String = "Construction Calc backup", zone: ZoneId = ZoneId.systemDefault()): String =
        AppFileCodec.backupZipFileName(now, label, zone)

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

    /** Shares a backup archive (`.backup.zip`, including available drawing PDFs). */
    fun shareBackupIntent(context: Context, backup: BackupDocument, fileName: String, toolTitle: (String) -> String): Intent {
        val body = buildString {
            appendLine("Construction Calc backup")
            backup.describe(toolTitle).forEach { appendLine("• $it") }
            appendLine()
            append("Open the attached file in Construction Calc (Settings › Data › Import) to restore it.")
        }
        return shareFileIntent(context, fileName, ZIP_MIME_TYPE, fileName.substringBefore('.'), body) {
            writeBackup(context, it, backup)
        }
    }

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
        else ProjectArchive.write(out, project, now, PlanFiles(context)::file, PlanPhotos(context)::file)
    }

    /** Writes a full backup archive (`.backup.zip`) to a document picked with ACTION_CREATE_DOCUMENT. */
    @Throws(IOException::class)
    fun writeBackup(context: Context, uri: Uri, backup: BackupDocument) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to that file")
        out.use { writeBackup(context, it, backup) }
    }

    private fun writeBackup(context: Context, out: java.io.OutputStream, backup: BackupDocument) {
        BackupArchive.write(out, backup, PlanFiles(context)::file, PlanPhotos(context)::file)
    }

    /** Reads a project or backup file (`.backup.zip`, `.project.zip`, `.backup.json`, `.project.json`, or compact text). */
    @Throws(IOException::class, ProjectFormatException::class)
    fun readAppFile(context: Context, uri: Uri): AppFile {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open that file")
        return PushbackInputStream(input, 4).use { stream ->
            // A ZIP starts with the local file header signature PK\u0003\u0004.
            val header = ByteArray(4)
            var count = 0
            while (count < header.size) {
                val chunk = stream.read(header, count, header.size - count)
                if (chunk < 0) break
                count += chunk
            }
            stream.unread(header, 0, count)
            val isZip = count >= 4 &&
                header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
                header[2] == 3.toByte() && header[3] == 4.toByte()
            if (isZip) {
                val files = PlanFiles(context)
                val photos = PlanPhotos(context)
                val staged = mutableListOf<PlanFiles.StagedPdf>()
                val stagedPhotos = mutableListOf<PlanPhotos.StagedPhoto>()
                val stage = { pdf: InputStream, hash: String -> staged += files.stage(pdf, expectedHash = hash, validatePdf = true); Unit }
                val stagePhoto = { photo: InputStream, hash: String -> stagedPhotos += photos.stage(photo, expectedHash = hash, checkHeader = true); Unit }
                try {
                    // The first entry names the kind of archive: backup.json or project.json.
                    val result = ZipInputStream(stream).use { zip ->
                        val first = zip.nextEntry?.name
                        if (first == BackupArchive.zip.document) AppFile.Backup(BackupArchive.readOpen(zip, first, stage, stagePhoto))
                        else AppFile.SingleProject(ProjectArchive.readOpen(zip, first, stage, stagePhoto))
                    }
                    staged.forEach(files::commit)
                    stagedPhotos.forEach(photos::commit)
                    result
                } catch (error: Throwable) {
                    staged.forEach { it.temp.delete() }
                    stagedPhotos.forEach { it.temp.delete() }
                    throw error
                }
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
