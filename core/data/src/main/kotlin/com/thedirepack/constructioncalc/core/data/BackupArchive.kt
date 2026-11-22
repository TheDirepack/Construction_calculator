package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.AppFile
import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupDocument
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** A full backup document followed by the original PDFs that are available on this device. */
internal object BackupArchive {
    val zip = PdfZip("backup.json", "backup", maxTotalBytes = 1024L * 1024 * 1024, maxPdfs = 500, requireAllPdfs = false)

    fun write(out: OutputStream, backup: BackupDocument, fileForHash: (String) -> File) =
        write(out, backup, fileForHash) { File("") }

    fun write(
        out: OutputStream,
        backup: BackupDocument,
        fileForHash: (String) -> File,
        photoFileForHash: (String) -> File,
    ) {
        val pdfs = hashes(backup).distinct().associateWith(fileForHash).filterValues { it.isFile }
        val photos = photoHashes(backup).distinct().associateWith(photoFileForHash).filterValues { it.isFile }
        val missing = missingMedia(backup, pdfs::containsKey, photos::containsKey)
        if (missing.isNotEmpty() && !backup.incompleteMedia) {
            throw IOException("Backup media is missing. Review the missing files and try again.")
        }
        zip.write(
            out = out,
            json = AppFileCodec.encodeBackup(backup.copy(incompleteMedia = missing.isNotEmpty())).encodeToByteArray(),
            hashes = pdfs.keys,
            fileForHash = pdfs::getValue,
            photoHashes = photos.keys,
            photoFileForHash = photos::getValue,
            requireAllMedia = true,
        )
    }

    fun missingMedia(
        backup: BackupDocument,
        hasPdf: (String) -> Boolean,
        hasPhoto: (String) -> Boolean,
    ): List<String> = buildList {
        for (project in backup.projects) {
            for (drawing in project.drawings) {
                val title = "${project.name} / ${drawing.name}"
                if (!hasPdf(drawing.sha256)) add("$title (original PDF)")
                for (pin in drawing.marks) {
                    val hash = pin.photoSha256 ?: continue
                    if (!hasPhoto(hash)) {
                        val caption = pin.text.trim().take(80).ifEmpty { "Photo pin" }
                        add("$title (page ${pin.page.toLong() + 1}, $caption)")
                    }
                }
            }
        }
    }

    fun read(input: InputStream, storePdf: (InputStream, String) -> Unit): BackupDocument =
        read(input, storePdf) { _, _ -> }

    fun read(
        input: InputStream,
        storePdf: (InputStream, String) -> Unit,
        storePhoto: (InputStream, String) -> Unit,
    ): BackupDocument = zip.read(input, ::decode, storePdf, ::photoHashes, storePhoto)

    internal fun readOpen(stream: ZipInputStream, firstName: String?, storePdf: (InputStream, String) -> Unit): BackupDocument =
        readOpen(stream, firstName, storePdf) { _, _ -> }

    internal fun readOpen(
        stream: ZipInputStream,
        firstName: String?,
        storePdf: (InputStream, String) -> Unit,
        storePhoto: (InputStream, String) -> Unit,
    ): BackupDocument = zip.readOpen(stream, firstName, ::decode, storePdf, ::photoHashes, storePhoto)

    private fun hashes(backup: BackupDocument) = backup.projects.flatMap { it.drawings }.map { it.sha256 }
    private fun photoHashes(backup: BackupDocument) = backup.projects.flatMap { p -> p.drawings.flatMap { it.referencedPhotoHashes() } }

    private fun decode(json: String): Pair<BackupDocument, List<String>> {
        val backup = (AppFileCodec.decode(json) as? AppFile.Backup)?.backup
            ?: throw IOException("This ZIP is not a Construction Calc backup.")
        return backup to hashes(backup)
    }
}
