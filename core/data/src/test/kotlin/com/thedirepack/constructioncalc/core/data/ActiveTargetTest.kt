package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveTargetTest {
    private val project = Project.create("Job", 1).let { it.addRun(Run(name = "Second"), 2) }

    @Test fun archivedProjectsAreNeverTheTarget() {
        val archived = project.archive(3)
        assertNull(AppSettings(activeProjectId = archived.id).activeTarget(listOf(archived)))
    }

    @Test fun aStaleRunFallsBackToTheLastRun() {
        val t = AppSettings(activeProjectId = project.id, activeRunId = "gone").activeTarget(listOf(project))!!
        assertEquals("Second", t.run!!.name)
        val first = project.runs.first()
        assertEquals(first, AppSettings(activeProjectId = project.id, activeRunId = first.id).activeTarget(listOf(project))!!.run)
    }

    @Test fun noActiveProject() {
        assertNull(AppSettings().activeTarget(listOf(project)))
    }
}
