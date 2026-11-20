package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Evaluator
import com.thedirepack.constructioncalc.core.measure.MeasureLiteral
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Fuzzing, corruption tolerance, and boundary testing across model codec and math expressions.
 */
class FuzzAndCorruptionTest {

    private val random = Random(42)

    @Test
    fun corruptedJsonNeverCrashesProjectCodec() {
        val validProject = Project.create("Test", 1000L)
        val validJson = ProjectCodec.encode(validProject)

        // Generate fuzzed variants of valid JSON
        val corruptedPayloads = mutableListOf<String>()

        // 1. Truncated at various points
        for (i in 1..minOf(50, validJson.length)) {
            corruptedPayloads += validJson.substring(0, i)
        }

        // 2. Random bit flips / character replacements
        repeat(50) {
            val chars = validJson.toCharArray()
            val mutations = random.nextInt(5) + 1
            repeat(mutations) {
                val idx = random.nextInt(chars.size)
                chars[idx] = (random.nextInt(126 - 32) + 32).toChar()
            }
            corruptedPayloads += String(chars)
        }

        // 3. Completely bogus inputs
        corruptedPayloads += listOf(
            "",
            "   ",
            "\u0000\u0001\u0002",
            "{\"format\": null}",
            "{\"format\": 123}",
            "{\"format\": \"construction-calc/project\"}", // missing project object
            "{\"format\": \"construction-calc/project\", \"project\": null}",
            "{\"format\": \"construction-calc/project\", \"project\": []}",
            "{\"format\": \"construction-calc/project\", \"project\": {\"id\": null}}",
            "{\"format\": \"construction-calc/project\", \"project\": {\"name\": 123}}",
            "{\"format\": \"construction-calc/project\", \"project\": {\"createdAt\": \"invalid\"}}",
            "[1, 2, 3]",
            "null",
            "true",
            "\"just a string\"",
            "<html><body>404 Not Found</body></html>",
        )

        for (payload in corruptedPayloads) {
            try {
                // Must either succeed (if somehow still valid JSON for project) or throw ProjectFormatException.
                // It must NEVER throw raw NullPointerException, ClassCastException, IndexOutOfBoundsException, etc.
                val result = ProjectCodec.decode(payload)
                assertNotNull(result)
            } catch (e: ProjectFormatException) {
                // Expected graceful rejection
                assertTrue(e.message?.isNotEmpty() == true)
            }
        }
    }

    @Test
    fun corruptedJsonNeverCrashesAppFileCodec() {
        val corruptedPayloads = listOf(
            "",
            "{",
            "{\"format\":\"unknown\"}",
            "{\"format\":\"construction-calc/backup\",\"projects\":[null]}",
            "{\"format\":\"construction-calc/backup\",\"version\":\"bad\"}",
            "{\"format\":\"construction-calc/backup\",\"settings\":\"not-an-object\"}",
            "{\"format\":\"construction-calc/backup\",\"tool_settings\":[]}",
            "{\"format\":\"construction-calc/backup\",\"recents\":\"invalid\"}",
            "[]",
            "\u0000",
            "{\"format\": 999}",
        )

        for (payload in corruptedPayloads) {
            try {
                val result = AppFileCodec.decode(payload)
                assertNotNull(result)
            } catch (e: ProjectFormatException) {
                // Expected graceful rejection
                assertTrue(e.message?.isNotEmpty() == true)
            }
        }
    }

    @Test
    fun corruptedToolInputsDecodeSafely() {
        // ToolInputs.decode must safely catch all errors and return null
        val corruptedJson = ProjectCodec.json.parseToJsonElement("{\"length\": {\"value\": \"12/0\", \"dimension\": \"LENGTH\"}}")
        // If length has invalid syntax, decode should not crash
        val res = ToolInputs.decode<ProjectCodecTest.DemoInputs>(corruptedJson as kotlinx.serialization.json.JsonObject)
        // Either null or decoded safely without uncaught exception
    }

    @Test
    fun measureLiteralFuzzingNeverThrowsUncaughtException() {
        val randomChars = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '/', '\'', '"', ' ', '-', '+', 'a', '#')

        repeat(500) {
            val len = random.nextInt(15) + 1
            val text = (1..len).map { randomChars[random.nextInt(randomChars.size)] }.joinToString("")

            // isValidPrefix must always return boolean, never throw
            val validPrefix = MeasureLiteral.isValidPrefix(text)

            // parse must either return a valid Quantity or throw CalcException
            try {
                val qty = MeasureLiteral.parse(text)
                assertNotNull(qty)
                assertTrue(qty.value.denominator.signum() != 0)
            } catch (e: com.thedirepack.constructioncalc.core.measure.CalcException) {
                // Expected parsing exception
            } catch (e: ArithmeticException) {
                // e.g. division by zero inside fraction
            }
        }
    }

    @Test
    fun evaluatorHandlesExtremesAndDivisionByZero() {
        // Test dividing by zero
        assertThrows(com.thedirepack.constructioncalc.core.measure.CalcException.DivideByZero::class.java) {
            Evaluator.evaluate(listOf(Token.Literal("5'"), Token.Op(com.thedirepack.constructioncalc.core.measure.Operator.DIVIDE), Token.Literal("0")))
        }

        // Extremely large values
        val hugeVal = Quantity.feet(Rational.of(Long.MAX_VALUE))
        val mult = Evaluator.apply(com.thedirepack.constructioncalc.core.measure.Operator.MULTIPLY, hugeVal, Quantity.number(Rational.of(2)))
        assertEquals(Dimension.LENGTH, mult.dimension)
        assertTrue(mult.value.numerator > java.math.BigInteger.valueOf(Long.MAX_VALUE))
    }

    @Test
    fun malformedStoredLengthsMakeTheFileDamagedInsteadOfCrashingLater() {
        val good = Project.create("Job", 1L).let { p ->
            p.updateRun(p.runs.first().id, 2L) {
                it.copy(raceway = Raceway(length = Quantity.inches(Rational.of(120)).stored()))
            }
        }
        val json = ProjectCodec.encode(good)
        assertEquals(good, ProjectCodec.decode(json))
        for (bad in listOf("abc", "1/0", "", "1/2/3", "1e10020", "1e-10020")) {
            val broken = json.replace("\"exact\": \"120\"", "\"exact\": \"$bad\"")
            assertTrue("payload should change for $bad", broken != json)
            assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(broken) }
            // A saved tool input with a bad length simply doesn't restore.
            assertEquals(null, ToolInputs.decode<StoredQuantity>(kotlinx.serialization.json.buildJsonObject {
                put("exact", kotlinx.serialization.json.JsonPrimitive(bad))
                put("dimension", kotlinx.serialization.json.JsonPrimitive("LENGTH"))
            }))
        }
    }

    @Test
    fun impossiblePanelGeometryIsRejectedByBothImportFormats() {
        val project = Project.create("Job", 1L).copy(
            panels = listOf(Panel(name = "Lighting", spaces = 120, circuits = listOf(Circuit(slot = 118, poles = 2)))),
        )

        val exportedFiles = listOf(ProjectCodec.encode(project), AppFileCodec.encodeBackup(BackupDocument(projects = listOf(project))))
        for ((field, valid, invalid) in listOf(
            Triple("spaces", 120, Int.MIN_VALUE),
            Triple("spaces", 120, Int.MAX_VALUE),
            Triple("slot", 118, -3),
            Triple("slot", 118, Int.MAX_VALUE),
            Triple("poles", 2, 0),
            Triple("poles", 2, Int.MAX_VALUE),
        )) {
            for (encoded in exportedFiles) {
                val broken = encoded.replace("\"$field\": $valid", "\"$field\": $invalid")
                assertTrue("$field should change", broken != encoded)
                assertThrows("$field = $invalid", ProjectFormatException::class.java) { AppFileCodec.decode(broken) }
            }
        }
    }

    @Test
    fun panelThatWasResizedCanStillBeImportedForRepairs() {
        val panel = Panel(name = "Lighting", spaces = 4, circuits = listOf(Circuit(slot = 5, poles = 3)))
        val project = Project.create("Job", 1L).copy(panels = listOf(panel))
        assertEquals(AppFile.SingleProject(project), AppFileCodec.decode(ProjectCodec.encode(project)))
    }

    @Test
    fun truncatedDeepJsonIsReportedAsAFormatError() {
        val nested = "{\"nested\":[".repeat(4096)
        val json = "{\"format\":\"construction-calc/backup\",\"tool_data\":$nested"
        assertThrows(ProjectFormatException::class.java) { AppFileCodec.decode(json) }
    }

    @Test
    fun everyTruncatedPrefixOfAnExportIsRejected() {
        val project = Project.create("Café ⚡", 1L).withNote("Quote: \" and newline:\n", 2L)
        val backup = BackupDocument(projects = listOf(project))
        val exports = mapOf(
            ProjectCodec.encode(project) to AppFile.SingleProject(project),
            AppFileCodec.encodeBackup(backup) to AppFile.Backup(backup),
        )
        for ((export, expected) in exports) {
            assertEquals(expected, AppFileCodec.decode(export))
            for (length in export.indices) {
                assertThrows("truncated after $length characters", ProjectFormatException::class.java) {
                    AppFileCodec.decode(export.take(length))
                }
            }
        }
    }
}
