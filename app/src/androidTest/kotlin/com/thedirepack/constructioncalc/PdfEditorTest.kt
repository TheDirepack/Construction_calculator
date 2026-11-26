package com.thedirepack.constructioncalc

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import com.thedirepack.constructioncalc.core.data.PlanFiles
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class PdfEditorTest : AppUiTest() {
    @Test
    fun importsTwoPagesAndNavigatesInBothDirections() {
        importPlan()
        icon("Previous page").assertIsNotEnabled()
        icon("Next page").activate()
        awaitPage(2)
        icon("Next page").assertIsNotEnabled()
        icon("Previous page").activate()
        awaitPage(1)
        assertEquals(1, savedProject().drawings.size)
    }

    @Test
    fun markupUndoRedoPersistsAndRoundTripsThroughBothExports() {
        val source = importPlan()
        selectMarkup("More tools", "Text")
        tapPage(1)
        await(hasText("Place text"))
        compose.onNode(hasSetTextAction()).performTextReplacement("VERIFY FIELD ROUTE")
        click("Save")
        assertEquals("VERIFY FIELD ROUTE", savedProject().drawings.single().marks.single().text)

        icon("Next page").activate()
        awaitPage(2)
        selectMarkup("Text", "Rectangle")
        icon("Drawing page 2.", substring = true).performTouchInput {
            val halfSpan = Offset(width * 0.14f, height * 0.08f)
            swipe(center - halfSpan, center + halfSpan)
        }
        val marks = savedProject().drawings.single().marks
        assertEquals(listOf(PlanMarkKind.TEXT, PlanMarkKind.RECTANGLE), marks.map { it.kind })
        assertEquals(listOf(0, 1), marks.map { it.page })
        icon("Undo").activate()
        assertEquals(marks.take(1), savedProject().drawings.single().marks)
        icon("Redo").activate()
        assertEquals(marks, savedProject().drawings.single().marks)

        relaunch()
        openProject()
        click("Drawings · 1", scroll = true)
        click("Drawing.pdf")
        awaitPage(2)
        icon("Previous page").activate()
        awaitPage(1)
        assertEquals(marks, savedProject().drawings.single().marks)
        icon("More drawing options").activate()
        click("Page measurements and markups")
        compose.onNodeWithText("VERIFY FIELD ROUTE").assertIsDisplayed()
        click("Done")

        val annotated = export("Export whole PDF (searchable)", "annotated.pdf", "Annotated PDF saved.")
        PDFBoxResourceLoader.init(context)
        PDDocument.load(annotated).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            for ((index, expected) in listOf("FIELD PLAN NORTH", "FIELD PLAN EAST").withIndex()) {
                val page = pdf.getPage(index)
                assertEquals(index * 90, page.rotation)
                val text = PDFTextStripper().apply {
                    startPage = index + 1
                    endPage = index + 1
                }.getText(pdf)
                // PDFBox can insert line breaks between glyphs on a rotated page.
                assertTrue("Missing source text on page ${index + 1}: $text",
                    expected.filterNot(Char::isWhitespace) in text.filterNot(Char::isWhitespace))
                val resources = page.resources
                assertTrue("Missing annotation form on page ${index + 1}",
                    resources.xObjectNames.any { resources.getXObject(it) is PDFormXObject })
            }
        }
        val drawing = savedProject().drawings.single()
        assertTrue(source.readBytes().contentEquals(PlanFiles(context).file(drawing.sha256).readBytes()))

        val editable = export("Export drawing JSON", "editable.json", "Drawing JSON saved.")
        back()
        withDocumentResult(Intent.ACTION_OPEN_DOCUMENT, editable) { click("Import drawing JSON") }
        awaitPage(1)
        val copies = savedProject().drawings
        assertEquals(2, copies.size)
        assertEquals(drawing, copies.last().copy(id = drawing.id))
    }

    @Test
    fun measuresAgainstAnArchitecturalScalePreset() {
        importPlan()
        icon("Scale status").activate()
        click("¼″ = 1′", scroll = true)
        click("Done")
        icon("Measure").performScrollTo().activate()
        placeSpan(1)

        compose.onNodeWithText(" · Use", substring = true).assertIsDisplayed()
        val drawing = savedProject().drawings.single()
        val measurement = drawing.measurements.single()
        // A quarter inch on paper represents 12 inches on site. A paper inch is 72 points.
        val points = hypot(measurement.second.x - measurement.first.x, measurement.second.y - measurement.first.y)
        assertTrue(points > 10.0)
        val measuredInches = checkNotNull(drawing.length(measurement)).value.toDouble()
        assertEquals(points * 2 / 3, measuredInches, 0.01)
    }

    @Test
    fun badPdfAndBrokenDrawingJsonLeaveTheProjectEmpty() {
        openEmptyDrawings()
        val broken = File(fixtures, "broken.pdf").apply { writeText("Plain text, never a PDF") }
        withDocumentResult(Intent.ACTION_OPEN_DOCUMENT, broken) { click("Import PDF") }
        await(hasText("isn't a PDF", substring = true))
        compose.onNodeWithText("No PDFs in this project.").assertIsDisplayed()
        val malformed = File(fixtures, "broken.json").apply { writeText("{}") }
        withDocumentResult(Intent.ACTION_OPEN_DOCUMENT, malformed) { click("Import drawing JSON") }
        // The list stays empty and the picker becomes available again after the failed import.
        await(hasText("This is not a supported drawing JSON file."))
        compose.onNodeWithText("No PDFs in this project.").assertIsDisplayed()
        assertTrue(savedProject().drawings.isEmpty())
    }

    private fun openEmptyDrawings() {
        createProject()
        click("Drawings · 0", scroll = true)
        compose.onNodeWithText("No PDFs in this project.").assertIsDisplayed()
    }

    private fun importPlan(): File {
        openEmptyDrawings()
        val source = File(fixtures, "field-plan.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(source.name).use { input -> source.outputStream().use { input.copyTo(it) } }
        withDocumentResult(Intent.ACTION_OPEN_DOCUMENT, source) { click("Import PDF") }
        awaitPage(1)
        return source
    }

    private fun export(menuEntry: String, name: String, message: String): File {
        val file = File(fixtures, name).apply { createNewFile() }
        icon("More drawing options").activate()
        withDocumentResult(Intent.ACTION_CREATE_DOCUMENT, file) { click(menuEntry) }
        await(hasText(message))
        return file
    }

    private fun awaitPage(page: Int) = await(hasContentDescription("Drawing page $page.", substring = true))

    private fun selectMarkup(menu: String, tool: String) {
        icon(menu).performScrollTo().activate()
        click(tool)
    }

    private fun tapPage(page: Int) {
        icon("Drawing page $page.", substring = true).performTouchInput { click(center) }
    }

    private fun placeSpan(page: Int) {
        val canvas = icon("Drawing page $page.", substring = true)
        canvas.performTouchInput { click(center - Offset(width * 0.14f, 0f)) }
        canvas.performTouchInput { click(center + Offset(width * 0.14f, 0f)) }
    }
}
