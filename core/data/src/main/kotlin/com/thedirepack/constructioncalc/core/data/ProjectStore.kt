package com.thedirepack.constructioncalc.core.data

import android.content.Context
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import java.io.File

/** Where projects are kept. Each project is one JSON file in the same format that is shared. */
interface ProjectStore {
    fun loadAll(): List<Project>
    fun save(project: Project)
    fun delete(projectId: String)

    companion object {
        fun files(context: Context): ProjectStore = FileProjectStore(File(context.filesDir, "projects"))
    }
}

class FileProjectStore(private val dir: File) : ProjectStore {

    override fun loadAll(): List<Project> {
        dir.mkdirs()
        return dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file -> runCatching { ProjectCodec.decode(file.readText()) }.getOrNull() }
    }

    override fun save(project: Project) {
        dir.mkdirs()
        // Write then rename, so a crash mid-write never corrupts the saved project.
        val target = fileFor(project.id)
        val temp = File(dir, "${project.id}.tmp")
        temp.writeText(ProjectCodec.encode(project))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    override fun delete(projectId: String) {
        fileFor(projectId).delete()
    }

    private fun fileFor(id: String) = File(dir, "$id.json")
}
