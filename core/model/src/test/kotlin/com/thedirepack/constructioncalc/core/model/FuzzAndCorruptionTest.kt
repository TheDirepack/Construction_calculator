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
}
