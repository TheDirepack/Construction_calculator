package com.thedirepack.constructioncalc.core.data

import android.content.Context
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Stores project metadata. Original drawing PDFs are kept separately by [PlanFiles]. */
interface ProjectStore {
    fun loadAll(): List<Project>
    fun save(project: Project)
    fun delete(projectId: String)
    /** Commits the entire batch, or retains the prior files when the batch fails. */
    fun writeBatch(upserts: List<Project>, removals: Set<String>)

    /** Files the last [loadAll] couldn't read. They are kept aside (never deleted) so nothing is lost silently. */
    fun damaged(): List<String> = emptyList()

    companion object {
        fun files(context: Context): ProjectStore = FileProjectStore(File(context.filesDir, "projects"))
    }
}

class FileProjectStore(private val dir: File) : ProjectStore {

    private var damaged: List<String> = emptyList()
    private val restoreJournal = File(dir, ".restore-undo.zip")

    /** Where unreadable project files are moved, so they stop loading but can still be recovered. */
    val damagedDir: File get() = File(dir, "damaged")

    override fun loadAll(): List<Project> {
        dir.mkdirs()
        recoverInterruptedRestore()
        val bad = mutableListOf<String>()
        val projects = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            runCatching { ProjectCodec.decode(file.readText()) }.getOrElse {
                damagedDir.mkdirs()
                val aside = File(damagedDir, file.name)
                if (!file.renameTo(aside)) {
                    file.copyTo(aside, overwrite = true)
                    file.delete()
                }
                bad += file.name
                null
            }
        }
        damaged = bad
        return projects
    }

    override fun damaged(): List<String> = damaged

    override fun save(project: Project) {
        recoverInterruptedRestore()
        writeProject(project)
    }

    override fun delete(projectId: String) {
        recoverInterruptedRestore()
        Files.deleteIfExists(fileFor(projectId).toPath())
    }

    override fun writeBatch(upserts: List<Project>, removals: Set<String>) {
        ensureDirectory()
        recoverInterruptedRestore()
        val writtenIds = upserts.mapTo(mutableSetOf()) { it.id }
        val changes = writtenIds + removals
        if (changes.isEmpty()) return

        // Publish the undo journal before changing any project files. Removing it commits the batch.
        // A fresh process can replay the journal if the app stops before that point.
        writeAtomically(restoreJournal) { raw ->
            ZipOutputStream(raw).use { zip ->
                for (id in changes) {
                    val file = fileFor(id)
                    val exists = file.exists()
                    if (exists && !file.isFile) throw IOException("Could not read project $id.")
                    zip.putNextEntry(ZipEntry(if (exists) "$id.json" else "$id.absent"))
                    if (exists) file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.finish()
            }
        }
        try {
            upserts.forEach { writeProject(it) }
            (removals - writtenIds).forEach { Files.deleteIfExists(fileFor(it).toPath()) }
            Files.delete(restoreJournal.toPath())
        } catch (error: Exception) {
            try {
                recoverInterruptedRestore()
            } catch (recoveryError: Exception) {
                error.addSuppressed(recoveryError)
            }
            throw IOException("Could not restore projects: ${error.message ?: "a storage write failed"}", error)
        }
    }

    private fun recoverInterruptedRestore() {
        if (!restoreJournal.exists()) return
        ZipFile(restoreJournal).use { journal ->
            // Remove files created by the failed restore first to make space for the originals.
            val (added, originals) = journal.entries().asSequence().partition { it.name.endsWith(".absent") }
            if (originals.any { !it.name.endsWith(".json") }) throw IOException("Invalid project restore journal.")
            for (entry in added) {
                Files.deleteIfExists(fileFor(entry.name.removeSuffix(".absent")).toPath())
            }
            for (entry in originals) {
                val destination = fileFor(entry.name.removeSuffix(".json"))
                writeAtomically(destination) { out -> journal.getInputStream(entry).use { it.copyTo(out) } }
            }
        }
        Files.delete(restoreJournal.toPath())
    }

    private fun writeProject(project: Project) {
        ensureDirectory()
        writeAtomically(fileFor(project.id)) { it.write(ProjectCodec.encode(project).encodeToByteArray()) }
    }

    private fun ensureDirectory() {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Could not open project storage.")
    }

    private fun writeAtomically(target: File, write: (OutputStream) -> Unit) =
        AtomicFiles.write(target, ".project-", write)

    private fun fileFor(id: String): File {
        require(id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "Invalid project id."
        }
        val file = File(dir, "$id.json")
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "Invalid project path." }
        return file
    }
}
