package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import com.thedirepack.constructioncalc.core.model.SheetRevision
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

class PlanExportsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun registerStandardFont(page: PDPage, name: String, baseFont: String) {
        val resources = page.resources ?: PDResources().also { page.resources = it }
        val resDict = resources.cosObject
        val fonts = (resDict.getDictionaryObject(COSName.FONT) as? COSDictionary)
            ?: COSDictionary().also { resDict.setItem(COSName.FONT, it) }
        fonts.setItem(
            COSName.getPDFName(name),
            COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.TYPE1)
                setName(COSName.BASE_FONT, baseFont)
                setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun appendAscii(stream: PDPageContentStream, commands: String) {
        stream.appendRawCommands(commands)
    }

    private fun escapePdfText(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '\\', '(', ')' -> append('\\').append(ch)
                else -> {
                    val code = ch.code
                    if (code in 32..126) append(ch)
                    else append('\\').append((code and 0xff).toString(8).padStart(3, '0'))
                }
            }
        }
    }

    private fun createSourcePdf(
        name: String = "plan.pdf",
        pageCount: Int = 3,
        mediaBox: PDRectangle = PDRectangle(0f, 0f, 600f, 400f),
        cropBox: PDRectangle = mediaBox,
        rotations: List<Int> = List(pageCount) { 0 },
    ): File {
        val file = temp.newFile(name)
        PDDocument().use { doc ->
            for (index in 0 until pageCount) {
                val page = PDPage(mediaBox)
                page.rotation = rotations[index]
                page.cropBox = cropBox
                registerStandardFont(page, "F1", "Helvetica")
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    // Native vector linework
                    stream.setLineWidth(2f)
                    stream.moveTo(cropBox.lowerLeftX + 20f, cropBox.lowerLeftY + 20f)
                    stream.lineTo(cropBox.upperRightX - 20f, cropBox.upperRightY - 20f)
                    stream.stroke()
                    // Native searchable text
                    appendAscii(
                        stream,
                        String.format(
                            Locale.US,
                            "BT /F1 12 Tf %.2f %.2f Td (%s) Tj ET\n",
                            cropBox.lowerLeftX + 40f,
                            cropBox.lowerLeftY + 40f,
                            escapePdfText("PANEL LP-${index + 1}"),
                        ),
                    )
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun writeVectorTestOverlay(
        overlay: File,
        width: Int,
        height: Int,
        markerText: String,
    ): List<Pair<Int, Int>> {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(width.toFloat(), height.toFloat()))
            registerStandardFont(page, "F1", "Helvetica-Bold")
            doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.setLineWidth(1.5f)
                stream.moveTo(10f, height - 10f)
                stream.lineTo(100f, height - 50f)
                stream.stroke()
                appendAscii(
                    stream,
                    String.format(
                        Locale.US,
                        "BT /F1 10 Tf 12 %.2f Td (%s) Tj ET\n",
                        height - 24f,
                        escapePdfText(markerText),
                    ),
                )
            }
            doc.save(overlay)
        }
        return listOf(width to height)
    }

    private data class ParsedPageContent(
        val textStrings: List<String>,
        val operators: List<String>,
        val formTransforms: List<Pair<String, Matrix>>,
        val rectsInUserSpace: List<PDRectangle>,
    )

    private fun parsePageContent(page: PDPage): ParsedPageContent {
        val texts = mutableListOf<String>()
        val ops = mutableListOf<String>()
        val formTransforms = mutableListOf<Pair<String, Matrix>>()
        val rects = mutableListOf<PDRectangle>()
        val stack = ArrayDeque<Matrix>()
        var ctm = Matrix()

        fun processStream(parser: PDFStreamParser, resources: PDResources?) {
            val operands = mutableListOf<Any>()
            var token = parser.parseNextToken()
            while (token != null) {
                if (token is Operator) {
                    val op = token.name
                    ops += op
                    when (op) {
                        "q" -> stack.addLast(ctm.clone())
                        "Q" -> if (stack.isNotEmpty()) ctm = stack.removeLast()
                        "cm" -> if (operands.size >= 6) {
                            val nums = operands.takeLast(6).map { (it as COSNumber).floatValue() }
                            val m = Matrix(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
                            ctm = m.multiply(ctm)
                        }
                        "re" -> if (operands.size >= 4) {
                            val x = (operands[operands.size - 4] as COSNumber).floatValue()
                            val y = (operands[operands.size - 3] as COSNumber).floatValue()
                            val w = (operands[operands.size - 2] as COSNumber).floatValue()
                            val h = (operands[operands.size - 1] as COSNumber).floatValue()
                            val corners = listOf(0f to 0f, w to 0f, 0f to h, w to h).map { (dx, dy) ->
                                val px = x + dx
                                val py = y + dy
                                val tx = ctm.scaleX * px + ctm.shearX * py + ctm.translateX
                                val ty = ctm.shearY * px + ctm.scaleY * py + ctm.translateY
                                tx to ty
                            }
                            val minX = corners.minOf { it.first }
                            val minY = corners.minOf { it.second }
                            val maxX = corners.maxOf { it.first }
                            val maxY = corners.maxOf { it.second }
                            rects += PDRectangle(minX, minY, maxX - minX, maxY - minY)
                        }
                        "Tj" -> {
                            val str = operands.lastOrNull() as? COSString
                            if (str != null) texts += str.string
                        }
                        "TJ" -> {
                            val arr = operands.lastOrNull() as? COSArray
                            if (arr != null) {
                                val combined = buildString {
                                    for (item in arr) {
                                        if (item is COSString) append(item.string)
                                    }
                                }
                                texts += combined
                            }
                        }
                        "Do" -> {
                            val name = (operands.lastOrNull() as? COSName)
                            if (name != null) {
                                formTransforms += name.name to ctm.clone()
                                val xobj = resources?.getXObject(name) as? PDFormXObject
                                if (xobj != null) {
                                    val saved = ctm.clone()
                                    val formMatrix = xobj.matrix
                                    ctm = formMatrix.multiply(ctm)
                                    processStream(PDFStreamParser(xobj), xobj.resources)
                                    ctm = saved
                                }
                            }
                        }
                    }
                    operands.clear()
                } else {
                    operands += token
                }
                token = parser.parseNextToken()
            }
        }

        page.contents.use { stream ->
            processStream(PDFStreamParser(stream.readBytes()), page.resources)
        }
        return ParsedPageContent(texts, ops, formTransforms, rects)
    }

    @Test
    fun cropBoxTransformMapsDisplayedTopLeftIntoPdfUserSpaceAcrossAllRotations() {
        val box = PDRectangle(100f, 200f, 600f, 400f) // ll=(100,200), ur=(700,600)
        val crop = PlanExportCrop(10f, 20f, 110f, 170f) // w=100, h=150

        val r0 = PlanExports.cropBoxInUserSpace(box, 0, crop)
        assertEquals(110f, r0.lowerLeftX, 1e-3f)
        assertEquals(430f, r0.lowerLeftY, 1e-3f)
        assertEquals(210f, r0.upperRightX, 1e-3f)
        assertEquals(580f, r0.upperRightY, 1e-3f)

        val r90 = PlanExports.cropBoxInUserSpace(box, 90, crop) // disp: 400 x 600
        assertEquals(120f, r90.lowerLeftX, 1e-3f)
        assertEquals(210f, r90.lowerLeftY, 1e-3f)
        assertEquals(270f, r90.upperRightX, 1e-3f)
        assertEquals(310f, r90.upperRightY, 1e-3f)

        val r180 = PlanExports.cropBoxInUserSpace(box, 180, crop)
        assertEquals(590f, r180.lowerLeftX, 1e-3f)
        assertEquals(220f, r180.lowerLeftY, 1e-3f)
        assertEquals(690f, r180.upperRightX, 1e-3f)
        assertEquals(370f, r180.upperRightY, 1e-3f)

        val r270 = PlanExports.cropBoxInUserSpace(box, 270, crop) // disp: 400 x 600
        assertEquals(530f, r270.lowerLeftX, 1e-3f)
        assertEquals(490f, r270.lowerLeftY, 1e-3f)
        assertEquals(680f, r270.upperRightX, 1e-3f)
        assertEquals(590f, r270.upperRightY, 1e-3f)

        // Normalized negative / >360 rotations
        assertEquals(r270.lowerLeftX, PlanExports.cropBoxInUserSpace(box, -90, crop).lowerLeftX, 1e-3f)
        assertEquals(r90.lowerLeftX, PlanExports.cropBoxInUserSpace(box, 450, crop).lowerLeftX, 1e-3f)

        assertThrows(IllegalArgumentException::class.java) {
            PlanExports.cropBoxInUserSpace(box, 0, PlanExportCrop(0f, 0f, 650f, 100f))
        }
    }

    @Test
    fun vectorSheetExportImportsSinglePagePreservesTextVectorsAndCropsAcrossRotations() {
        val mediaBox = PDRectangle(0f, 0f, 800f, 700f)
        val cropBox = PDRectangle(50f, 80f, 600f, 400f)
        val rotations = listOf(0, 90, 180, 270)
        val pdfFile = createSourcePdf(
            name = "rotated.pdf",
            pageCount = 4,
            mediaBox = mediaBox,
            cropBox = cropBox,
            rotations = rotations,
        )
        val hash = sha256(pdfFile)
        val cacheDir = temp.newFolder("cache")

        rotations.forEachIndexed { pageIndex, rotation ->
            val dispW = if (rotation == 90 || rotation == 270) 400 else 600
            val dispH = if (rotation == 90 || rotation == 270) 600 else 400
            val route = PlanRunRoute(
                runId = "deleted-run",
                page = pageIndex,
                points = listOf(PlanPoint(30.0, 40.0), PlanPoint(120.0, 90.0)),
                circuitTag = "Ckt A",
            )
            val drawing = PlanDrawing(
                name = "Rotated.pdf",
                sha256 = hash,
                routes = listOf(route),
                sheetRevisions = mapOf(pageIndex to SheetRevision("E-10${pageIndex + 1}", "2", 1)),
            )
            val source = PlanSheetSource(drawing.id, hash, pageIndex, "E-10${pageIndex + 1}", "2")
            val crop = PlanExportCrop(20f, 30f, 220f, 280f)
            val out = ByteArrayOutputStream()
            var capturedRouteLabel = ""

            PlanExports.writeSheetPdf(
                sourceFile = pdfFile,
                cacheDir = cacheDir,
                drawing = drawing,
                source = source,
                crop = crop,
                out = out,
                format = { it.toString() },
                routeLabel = { "Run (deleted)" },
                vector = true,
                overlayWriter = { _, overlay, drw, pages, _, rLabel ->
                    assertEquals(listOf(pageIndex), pages)
                    val label = PlanExports.routeAnnotationLabel(drw.routes.first { it.page == pageIndex }, rLabel)
                    capturedRouteLabel = label
                    writeVectorTestOverlay(overlay, dispW, dispH, label)
                },
            )

            assertEquals("Run (deleted) · Ckt A", capturedRouteLabel)
            PDDocument.load(out.toByteArray()).use { exported ->
                assertEquals(1, exported.numberOfPages)
                val page = exported.getPage(0)
                assertEquals(rotation, page.rotation)

                val expectedCrop = PlanExports.cropBoxInUserSpace(cropBox, rotation, crop)
                assertEquals(expectedCrop.lowerLeftX, page.cropBox.lowerLeftX, 1e-3f)
                assertEquals(expectedCrop.lowerLeftY, page.cropBox.lowerLeftY, 1e-3f)
                assertEquals(expectedCrop.upperRightX, page.cropBox.upperRightX, 1e-3f)
                assertEquals(expectedCrop.upperRightY, page.cropBox.upperRightY, 1e-3f)

                // No raster images anywhere; vector Form XObject present.
                val resources = page.resources
                val xObjectNames = resources.xObjectNames.toList()
                assertFalse(xObjectNames.any { resources.getXObject(it) is PDImageXObject })
                assertTrue(xObjectNames.any { resources.getXObject(it) is PDFormXObject })

                // Inspect parsed content stream & overlay Form XObject.
                val parsed = parsePageContent(page)
                assertTrue("Expected native vector line ops in rotation $rotation", "m" in parsed.operators && "l" in parsed.operators && "S" in parsed.operators)
                assertTrue("Expected form stamp Do op in rotation $rotation", "Do" in parsed.operators)

                // Verify searchable text from ONLY source.page + overlay + vector header.
                val allText = parsed.textStrings.joinToString("\n")
                assertTrue("Expected source page text in rotation $rotation: $allText", allText.contains("PANEL LP-${pageIndex + 1}"))
                for (other in rotations.indices) {
                    if (other != pageIndex) {
                        assertFalse("Unexpected other page text in rotation $rotation", allText.contains("PANEL LP-${other + 1}"))
                    }
                }
                assertTrue("Expected header sheet id in rotation $rotation: $allText", allText.contains("Sheet E-10${pageIndex + 1}"))
                assertTrue("Expected header revision in rotation $rotation: $allText", allText.contains("Revision 2"))
                assertTrue("Expected route label in rotation $rotation: $allText", allText.contains("Run (deleted)"))

                // Verify the stamped header badge rect lies completely inside the cropped page box (zero clipping).
                assertTrue("Expected header rect in rotation $rotation", parsed.rectsInUserSpace.isNotEmpty())
                for (rect in parsed.rectsInUserSpace) {
                    assertTrue(rect.lowerLeftX >= expectedCrop.lowerLeftX - 0.5f)
                    assertTrue(rect.lowerLeftY >= expectedCrop.lowerLeftY - 0.5f)
                    assertTrue(rect.upperRightX <= expectedCrop.upperRightX + 0.5f)
                    assertTrue(rect.upperRightY <= expectedCrop.upperRightY + 0.5f)
                }

                // Verify the overlay Form XObject transform matches the full uncropped source page orientation.
                assertEquals(1, parsed.formTransforms.size)
                val formCtm = parsed.formTransforms.single().second
                val expectedOverlay = Matrix().apply {
                    when (rotation) {
                        90 -> {
                            setValue(0, 0, 0f); setValue(0, 1, cropBox.height / dispW)
                            setValue(1, 0, -cropBox.width / dispH); setValue(1, 1, 0f)
                            setValue(2, 0, cropBox.lowerLeftX + cropBox.width); setValue(2, 1, cropBox.lowerLeftY)
                        }
                        180 -> {
                            setValue(0, 0, -cropBox.width / dispW); setValue(0, 1, 0f)
                            setValue(1, 0, 0f); setValue(1, 1, -cropBox.height / dispH)
                            setValue(2, 0, cropBox.lowerLeftX + cropBox.width); setValue(2, 1, cropBox.lowerLeftY + cropBox.height)
                        }
                        270 -> {
                            setValue(0, 0, 0f); setValue(0, 1, -cropBox.height / dispW)
                            setValue(1, 0, cropBox.width / dispH); setValue(1, 1, 0f)
                            setValue(2, 0, cropBox.lowerLeftX); setValue(2, 1, cropBox.lowerLeftY + cropBox.height)
                        }
                        else -> {
                            setValue(0, 0, cropBox.width / dispW); setValue(0, 1, 0f)
                            setValue(1, 0, 0f); setValue(1, 1, cropBox.height / dispH)
                            setValue(2, 0, cropBox.lowerLeftX); setValue(2, 1, cropBox.lowerLeftY)
                        }
                    }
                }
                assertEquals(expectedOverlay.scaleX, formCtm.scaleX, 1e-3f)
                assertEquals(expectedOverlay.shearY, formCtm.shearY, 1e-3f)
                assertEquals(expectedOverlay.shearX, formCtm.shearX, 1e-3f)
                assertEquals(expectedOverlay.scaleY, formCtm.scaleY, 1e-3f)
                assertEquals(expectedOverlay.translateX, formCtm.translateX, 1e-3f)
                assertEquals(expectedOverlay.translateY, formCtm.translateY, 1e-3f)
            }
        }
    }

    @Test
    fun wholeSheetVectorExportWithoutAnnotationsSkipsOverlayAndPreservesSourceCropBox() {
        val mediaBox = PDRectangle(0f, 0f, 800f, 700f)
        val cropBox = PDRectangle(40f, 60f, 500f, 360f)
        val pdfFile = createSourcePdf(
            name = "whole-sheet.pdf",
            pageCount = 2,
            mediaBox = mediaBox,
            cropBox = cropBox,
            rotations = listOf(0, 270),
        )
        val hash = sha256(pdfFile)
        val cacheDir = temp.newFolder("cache-whole")
        val drawing = PlanDrawing(name = "WholeSheet.pdf", sha256 = hash)
        val source = PlanSheetSource(drawing.id, hash, 1, "E(1)\\A", "Rev · 3")
        val out = ByteArrayOutputStream()
        var overlayCalled = false

        PlanExports.writeSheetPdf(
            sourceFile = pdfFile,
            cacheDir = cacheDir,
            drawing = drawing,
            source = source,
            crop = null,
            out = out,
            format = { it.toString() },
            overlayWriter = { _, _, _, _, _, _ ->
                overlayCalled = true
                emptyList()
            },
        )

        assertFalse(overlayCalled)
        PDDocument.load(out.toByteArray()).use { exported ->
            assertEquals(1, exported.numberOfPages)
            val page = exported.getPage(0)
            assertEquals(270, page.rotation)
            assertEquals(cropBox.lowerLeftX, page.cropBox.lowerLeftX, 1e-3f)
            assertEquals(cropBox.lowerLeftY, page.cropBox.lowerLeftY, 1e-3f)
            assertEquals(cropBox.upperRightX, page.cropBox.upperRightX, 1e-3f)
            assertEquals(cropBox.upperRightY, page.cropBox.upperRightY, 1e-3f)

            val parsed = parsePageContent(page)
            val allText = parsed.textStrings.joinToString("\n")
            assertTrue(allText.contains("PANEL LP-2"))
            assertFalse(allText.contains("PANEL LP-1"))
            assertTrue(allText.contains("Sheet E(1)\\A"))
            assertTrue(allText.contains("Revision Rev · 3"))
        }
    }

    @Test
    fun vectorSheetExportHandlesUnassignedRevisionNarrowCropAndInvalidInputs() {
        val pdfFile = createSourcePdf("single.pdf", pageCount = 1, mediaBox = PDRectangle(0f, 0f, 500f, 400f))
        val hash = sha256(pdfFile)
        val cacheDir = temp.newFolder("cache2")
        val drawing = PlanDrawing(
            name = "Single.pdf",
            sha256 = hash,
            marks = listOf(PlanMark(page = 0, kind = PlanMarkKind.LINE, points = listOf(PlanPoint(10.0, 10.0), PlanPoint(80.0, 80.0)))),
        )
        val source = PlanSheetSource(drawing.id, hash, 0, "A-01-EXTRALONG-SHEET-ID", revision = null)
        val narrowCrop = PlanExportCrop(10f, 10f, 90f, 70f) // 80 x 60 pt narrow crop
        val out = ByteArrayOutputStream()

        PlanExports.writeSheetPdf(
            sourceFile = pdfFile,
            cacheDir = cacheDir,
            drawing = drawing,
            source = source,
            crop = narrowCrop,
            out = out,
            format = { it.toString() },
            overlayWriter = { _, overlay, _, _, _, _ -> writeVectorTestOverlay(overlay, 500, 400, "Overlay") },
        )

        PDDocument.load(out.toByteArray()).use { exported ->
            assertEquals(1, exported.numberOfPages)
            val page = exported.getPage(0)
            assertEquals(80f, page.cropBox.width, 1e-3f)
            assertEquals(60f, page.cropBox.height, 1e-3f)
            val parsed = parsePageContent(page)
            val allText = parsed.textStrings.joinToString("\n")
            assertTrue(allText.contains("Sheet A-01-EXTRALONG-SHEET-ID"))
            assertTrue(allText.contains("Revision unassigned"))
            // Header badge stays strictly inside narrow 80 x 60 cropBox
            for (rect in parsed.rectsInUserSpace) {
                assertTrue(rect.lowerLeftX >= page.cropBox.lowerLeftX - 0.5f)
                assertTrue(rect.lowerLeftY >= page.cropBox.lowerLeftY - 0.5f)
                assertTrue(rect.upperRightX <= page.cropBox.upperRightX + 0.5f)
                assertTrue(rect.upperRightY <= page.cropBox.upperRightY + 0.5f)
            }
        }

        assertThrows(IOException::class.java) {
            PlanExports.writeSheetPdf(
                sourceFile = pdfFile,
                cacheDir = cacheDir,
                drawing = drawing,
                source = source.copy(page = 3),
                crop = null,
                out = ByteArrayOutputStream(),
                format = { it.toString() },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanExports.writeSheetPdf(
                sourceFile = pdfFile,
                cacheDir = cacheDir,
                drawing = drawing,
                source = source.copy(drawingId = "other"),
                crop = null,
                out = ByteArrayOutputStream(),
                format = { it.toString() },
            )
        }
    }

    @Test
    fun wholeDocumentAndSheetExportsHonorRouteLabelAndRasterFallback() {
        val pdfFile = createSourcePdf("routes.pdf", pageCount = 2)
        val hash = sha256(pdfFile)
        val cacheDir = temp.newFolder("cache3")

        // Unmarked drawing copies source PDF verbatim
        val unmarkedOut = ByteArrayOutputStream()
        PlanExports.writePdf(
            source = pdfFile,
            cacheDir = cacheDir,
            drawing = PlanDrawing(name = "Unmarked.pdf", sha256 = hash),
            out = unmarkedOut,
            format = { it.toString() },
        )
        assertArrayEquals(pdfFile.readBytes(), unmarkedOut.toByteArray())

        val route = PlanRunRoute(
            runId = "gone-run",
            page = 1,
            points = listOf(PlanPoint(20.0, 20.0), PlanPoint(120.0, 80.0)),
            circuitTag = "L1-3",
        )
        val drawing = PlanDrawing(name = "Routes.pdf", sha256 = hash, routes = listOf(route))
        val source = PlanSheetSource(drawing.id, hash, 1, "E-2", "B")

        val wholeOut = ByteArrayOutputStream()
        var wholeCaptured = ""
        PlanExports.writePdf(
            source = pdfFile,
            cacheDir = cacheDir,
            drawing = drawing,
            out = wholeOut,
            format = { it.toString() },
            routeLabel = { "Run (deleted)" },
            overlayWriter = { _, overlay, drw, pages, _, rLabel ->
                assertEquals(listOf(1), pages)
                val label = PlanExports.routeAnnotationLabel(drw.routes.single(), rLabel)
                wholeCaptured = label
                writeVectorTestOverlay(overlay, 600, 400, label)
            },
        )
        assertEquals("Run (deleted) · L1-3", wholeCaptured)
        PDDocument.load(wholeOut.toByteArray()).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val page0Text = parsePageContent(doc.getPage(0)).textStrings.joinToString("\n")
            val page1Text = parsePageContent(doc.getPage(1)).textStrings.joinToString("\n")
            assertTrue(page0Text.contains("PANEL LP-1"))
            assertTrue(page1Text.contains("PANEL LP-2"))
            assertTrue(page1Text.contains("Run (deleted)"))
        }

        var rasterCalled = false
        val rasterOut = ByteArrayOutputStream()
        PlanExports.writeSheetPdf(
            sourceFile = pdfFile,
            cacheDir = cacheDir,
            drawing = drawing,
            source = source,
            crop = null,
            out = rasterOut,
            format = { it.toString() },
            routeLabel = { "Run (deleted)" },
            vector = false,
            rasterWriter = { _, drw, src, _, outStream, _, rLabel ->
                rasterCalled = true
                assertEquals("Run (deleted) · L1-3", PlanExports.routeAnnotationLabel(drw.routes.first { it.page == src.page }, rLabel))
                outStream.write("%PDF-RASTER".encodeToByteArray())
            },
        )
        assertTrue(rasterCalled)
        assertEquals("%PDF-RASTER", rasterOut.toByteArray().decodeToString())
    }
}
