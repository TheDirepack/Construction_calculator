package com.thedirepack.constructioncalc.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlanFilesGarbageCollectTest {
    @get:Rule val temp = TemporaryFolder()

    private val keptHash = "a".repeat(64)
    private val orphanHash = "b".repeat(64)

    @Test fun deletesOnlyUnreferencedValidHashPdfs() {
        val dir = temp.newFolder("plan-pdfs")
        val kept = File(dir, "$keptHash.pdf").apply { writeText("kept") }
        val orphan = File(dir, "$orphanHash.pdf").apply { writeText("orphan") }
        val inFlightTemp = File(dir, "drawing-12345.tmp").apply { writeText("staging") }
        val unrelated = File(dir, "notes.pdf").apply { writeText("keep non-hash") }
        val subdir = File(dir, "nested").apply { mkdirs() }

        val deleted = PlanFiles.garbageCollect(dir, referencedHashes = setOf(keptHash))

        assertEquals(1, deleted)
        assertTrue(kept.isFile)
        assertFalse(orphan.exists())
        assertTrue(inFlightTemp.isFile)
        assertTrue(unrelated.isFile)
        assertTrue(subdir.isDirectory)
    }

    @Test fun missingDirectoryReturnsZero() {
        val missing = File(temp.root, "does-not-exist")
        assertEquals(0, PlanFiles.garbageCollect(missing, emptySet()))
    }
}
