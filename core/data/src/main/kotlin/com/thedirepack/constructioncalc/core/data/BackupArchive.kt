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
    ) = zip.write(
        out = out,
        json = AppFileCodec.encodeBackup(backup).encodeToByteArray(),
        hashes = hashes(backup),
        fileForHash = fileForHash,
        photoHashes = photoHashes(backup),
        photoFileForHash = photoFileForHash,
    )

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
