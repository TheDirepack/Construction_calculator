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
        zip.write(out, AppFileCodec.encodeBackup(backup).encodeToByteArray(), hashes(backup), fileForHash)

    fun read(input: InputStream, storePdf: (InputStream, String) -> Unit): BackupDocument = zip.read(input, ::decode, storePdf)

    internal fun readOpen(stream: ZipInputStream, firstName: String?, storePdf: (InputStream, String) -> Unit): BackupDocument =
        zip.readOpen(stream, firstName, ::decode, storePdf)

    private fun hashes(backup: BackupDocument) = backup.projects.flatMap { it.drawings }.map { it.sha256 }

    private fun decode(json: String): Pair<BackupDocument, List<String>> {
        val backup = (AppFileCodec.decode(json) as? AppFile.Backup)?.backup
            ?: throw IOException("This ZIP is not a Construction Calc backup.")
        return backup to hashes(backup)
    }
}
