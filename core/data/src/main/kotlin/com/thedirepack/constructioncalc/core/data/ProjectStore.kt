package com.thedirepack.constructioncalc.core.data

import android.content.Context
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores project metadata. Original drawing PDFs are kept separately by [PlanFiles]. */
interface ProjectStore {
    fun loadAll(): List<Project>
    fun save(project: Project)
    fun delete(projectId: String)

    /** Files the last [loadAll] couldn't read. They are kept aside (never deleted) so nothing is lost silently. */
    fun damaged(): List<String> = emptyList()

    companion object {
        fun files(context: Context): ProjectStore = FileProjectStore(File(context.filesDir, "projects"))
    }
}

class FileProjectStore(private val dir: File) : ProjectStore {

    private var damaged: List<String> = emptyList()

    /** Where unreadable project files are moved, so they stop loading but can still be recovered. */
    val damagedDir: File get() = File(dir, "damaged")

    override fun loadAll(): List<Project> {
        dir.mkdirs()
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
        dir.mkdirs()
        // Write then atomically move, so a crash mid-write never corrupts the saved project.
        val target = fileFor(project.id)
        val temp = File(dir, "${project.id}.tmp")
        require(temp.canonicalFile.parentFile == dir.canonicalFile) { "Invalid project id." }
        try {
            temp.writeText(ProjectCodec.encode(project))
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    override fun delete(projectId: String) {
        fileFor(projectId).delete()
    }

    private fun fileFor(id: String): File {
        require(id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "Invalid project id."
        }
        val file = File(dir, "$id.json")
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "Invalid project path." }
        return file
    }
}
