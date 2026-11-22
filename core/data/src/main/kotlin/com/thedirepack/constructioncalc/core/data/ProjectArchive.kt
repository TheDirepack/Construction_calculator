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
        zip.write(out, ProjectCodec.encode(project, exportedAt = now).encodeToByteArray(), project.drawings.map { it.sha256 }, fileForHash)

    fun read(input: InputStream, storePdf: (InputStream, String) -> Unit): Project = zip.read(input, ::decode, storePdf)

    internal fun readOpen(stream: ZipInputStream, firstName: String?, storePdf: (InputStream, String) -> Unit): Project =
        zip.readOpen(stream, firstName, ::decode, storePdf)

    private fun decode(json: String): Pair<Project, List<String>> =
        ProjectCodec.decode(json).let { it to it.drawings.map { d -> d.sha256 } }
}
