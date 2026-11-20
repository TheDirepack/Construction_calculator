package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ProjectCodecTest {
    @Serializable
    data class DemoInputs(val length: StoredQuantity, val note: String = "")

    private fun sample(): Project {
        val length = Quantity.inches(Rational.of(191, 2))
        val item = ProjectItem(
            toolId = "foot-inch",
            title = "Wall",
            createdAt = 1_000,
            inputs = ToolInputs.encode(DemoInputs(length.stored(), "north")),
            results = listOf(ResultValue.of("result", "Result", length, "7' 11 1/2\"")),
            summary = "5' 3 1/2\" + 2' 8\" = 7' 11 1/2\"",
        )
        return Project.create("Smith Residence", now = 1_000).let { p -> p.addItem(p.runs.first().id, item, 2_000) }
    }

    @Test fun roundTrips() {
        val project = sample()
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project, exportedAt = 3_000)))
    }

    @Test fun toolInputsRoundTripAndResultsKeepExactValues() {
        val decoded = ProjectCodec.decode(ProjectCodec.encode(sample()))
        val item = decoded.runs.first().items.first()
        val inputs = ToolInputs.decode<DemoInputs>(item.inputs)!!
        assertEquals(Rational.of(191, 2), inputs.length.toQuantity().value)
        assertEquals(Quantity.inches(Rational.of(191, 2)), item.results.first().toQuantity())
    }

    @Test fun ignoresUnknownFieldsFromNewerVersions() {
        val text = ProjectCodec.encode(sample())
            .replace("\"format\"", "\"future_field\": {\"x\": 1},\n  \"format\"")
            .replace("\"version\": 1", "\"version\": 7")
        assertEquals("Smith Residence", ProjectCodec.decode(text).name)
    }

    @Test fun rejectsOtherFiles() {
        listOf("{}", "not json", """{"format":"something-else","project":{"name":"x","createdAt":1}}""").forEach {
            try {
                ProjectCodec.decode(it)
                throw AssertionError("Expected failure for $it")
            } catch (_: ProjectFormatException) {
            }
        }
    }

    @Test fun undecodableInputsReturnNull() {
        val item = sample().runs.first().items.first()
        assertNull(ToolInputs.decode<Map<String, Int>>(item.inputs))
    }

    @Test fun fileNameIsSafe() {
        assertEquals("A B C.project.json", ProjectCodec.fileName(Project.create("A/B:C", 0)))
    }

    @Test fun editsAndMoves() {
        var p = sample()
        val itemId = p.runs.first().items.first().id
        val run2 = Run(name = "Panel A")
        p = p.addRun(run2, 5).moveItem(itemId, run2.id, 0, 6)
        assertTrue(p.runs.first().items.isEmpty())
        assertEquals(itemId, p.run(run2.id)!!.items.single().id)
        assertEquals(6, p.updatedAt)
        val copy = p.duplicated(now = 7)
        assertNotEquals(p.id, copy.id)
        assertNotEquals(itemId, copy.runs[1].items.single().id)
    }

    @Test fun rendersText() {
        val text = ProjectText.render(sample(), toolTitle = { "Foot-Inch" }, zone = ZoneOffset.UTC)
        assertTrue(text, text.contains("■ General"))
        assertTrue(text, text.contains("• Wall  [Foot-Inch]"))
        assertTrue(text, text.contains("5' 3 1/2\" + 2' 8\" = 7' 11 1/2\""))
    }
}
