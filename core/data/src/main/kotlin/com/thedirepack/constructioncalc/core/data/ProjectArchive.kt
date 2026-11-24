package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** A project document followed by every one of its original PDFs. */
internal object ProjectArchive {
    val zip = PdfZip("project.json", "project", maxTotalBytes = 512L * 1024 * 1024, maxPdfs = 200, requireAllPdfs = true)

    fun write(out: OutputStream, project: Project, now: Long, fileForHash: (String) -> File) =
        write(out, project, now, fileForHash) { File("") }

    fun write(
        out: OutputStream,
        project: Project,
        now: Long,
        fileForHash: (String) -> File,
        photoFileForHash: (String) -> File,
    ) = zip.write(
        out = out,
        json = ProjectCodec.encode(project, exportedAt = now).encodeToByteArray(),
        hashes = project.drawings.map { it.sha256 },
        fileForHash = fileForHash,
        photoHashes = photoHashes(project),
        photoFileForHash = photoFileForHash,
        createdAt = now,
    )

    fun read(input: InputStream, storePdf: (InputStream, String) -> Unit): Project =
        read(input, storePdf) { _, _ -> }

    fun read(
        input: InputStream,
        storePdf: (InputStream, String) -> Unit,
        storePhoto: (InputStream, String) -> Unit,
    ): Project = zip.read(input, ::decode, storePdf, ::photoHashes, storePhoto)

    internal fun readOpen(stream: ZipInputStream, firstName: String?, storePdf: (InputStream, String) -> Unit): Project =
        readOpen(stream, firstName, storePdf) { _, _ -> }

    internal fun readOpen(
        stream: ZipInputStream,
        firstName: String?,
        storePdf: (InputStream, String) -> Unit,
        storePhoto: (InputStream, String) -> Unit,
    ): Project = zip.readOpen(stream, firstName, ::decode, storePdf, ::photoHashes, storePhoto)

    private fun photoHashes(project: Project) = project.drawings.flatMap { it.referencedPhotoHashes() }

    private fun decode(json: String): Pair<Project, List<String>> =
        ProjectCodec.decode(json).let { it to it.drawings.map { d -> d.sha256 } }
}
