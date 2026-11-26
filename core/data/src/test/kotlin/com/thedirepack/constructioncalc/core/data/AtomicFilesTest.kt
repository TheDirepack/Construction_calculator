package com.thedirepack.constructioncalc.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AtomicFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun replacesAnExistingFileAndLeavesNoTempFiles() {
        val dir = temp.newFolder("atomic")
        val target = dir.resolve("data.json").apply { writeText("old") }

        AtomicFiles.write(target, "new".encodeToByteArray())

        assertEquals("new", target.readText())
        assertEquals(listOf("data.json"), dir.list()!!.toList())
    }

    @Test fun aFailedWriteKeepsTheOldFileAndCleansUp() {
        val dir = temp.newFolder("atomic-fail")
        val target = dir.resolve("data.json").apply { writeText("old") }

        assertThrows(IOException::class.java) {
            AtomicFiles.write(target) { out ->
                out.write("half".encodeToByteArray())
                throw IOException("Disk full")
            }
        }

        assertEquals("old", target.readText())
        assertEquals(listOf("data.json"), dir.list()!!.toList())
    }

    @Test fun writersThatCloseTheirStreamStillGetSynced() {
        val dir = temp.newFolder("atomic-zip")
        val target = dir.resolve("journal.zip")

        AtomicFiles.write(target) { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("a.json"))
                zip.write("{}".encodeToByteArray())
                zip.closeEntry()
            }
        }

        java.util.zip.ZipFile(target).use { assertEquals(listOf("a.json"), it.entries().toList().map { e -> e.name }) }
    }

    @Test fun planSymbolsOverwriteGoesThroughTheAtomicHelper() {
        val dir = temp.newFolder("symbols")
        val symbols = PlanSymbols(directory = dir, validateSvg = {})
        val id = UUID.randomUUID().toString()
        symbols.writeSymbol(PlanSymbol(id, "First", "<svg/>"))
        symbols.writeSymbol(PlanSymbol(id, "Second", "<svg/>"))

        assertEquals(listOf("Second"), symbols.list().map { it.name })
        assertEquals(listOf("$id.json"), dir.list()!!.toList())
    }
}
