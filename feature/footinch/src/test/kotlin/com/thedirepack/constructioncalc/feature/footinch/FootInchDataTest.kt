package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.LengthStyle
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootInchDataTest {

    @Test
    fun defaultInputsRoundTripAndDecodeEmptyJson() {
        val inputs = FootInchInputs()
        val encoded = ToolInputs.encode(inputs)
        val decoded = ToolInputs.decode<FootInchInputs>(encoded)
        assertEquals(inputs, decoded)

        val emptyJson = ToolInputs.decode<FootInchInputs>(JsonObject(emptyMap()))
        assertEquals(FootInchInputs(), emptyJson)
    }

    @Test
    fun savedTokensRoundTripWithExactFractions() {
        val halfInch = Quantity.inches(Rational.of(1, 2))
        val threeFeet = Quantity.feet(Rational.of(3))
        val tokens = listOf(
            SavedToken.Literal("5'3\"1/2"),
            SavedToken.Op(Operator.ADD),
            SavedToken.Value(halfInch.stored(), preferInches = true),
            SavedToken.Op(Operator.SUBTRACT),
            SavedToken.Value(threeFeet.stored(), preferInches = false),
        )

        val inputs = FootInchInputs(
            tokens = tokens,
            answer = Quantity.inches(Rational.of(61, 2)).stored(),
            preferInches = false,
        )

        val decoded = ToolInputs.decode<FootInchInputs>(ToolInputs.encode(inputs))
        assertNotNull(decoded)
        assertEquals(tokens.size, decoded!!.tokens.size)
        assertEquals(inputs.answer, decoded.answer)
        assertEquals(inputs.preferInches, decoded.preferInches)

        // Convert back to domain tokens
        val domainTokens = decoded.tokens.map { it.toToken() }
        assertEquals(Token.Literal("5'3\"1/2"), domainTokens[0])
        assertEquals(Token.Op(Operator.ADD), domainTokens[1])
        assertEquals(Token.Value(halfInch, preferInches = true), domainTokens[2])
    }

    @Test
    fun calcInputKeypadSequenceComputesCorrectFootInchMath() {
        // Typing: 5 ' 3 1/2 + 2 ' 8 1/2 = -> 8' 0" (96 inches)
        var state = CalcState()
        val keys = listOf(
            Key.Char('5'), Key.Char('\''), Key.Char('3'), Key.Fraction(1, 2),
            Key.Op(Operator.ADD),
            Key.Char('2'), Key.Char('\''), Key.Char('8'), Key.Fraction(1, 2),
            Key.Equals,
        )

        var completedResult: Quantity? = null
        for (key in keys) {
            val outcome = CalcInput.reduce(state, key)
            state = outcome.state
            if (outcome.completed != null) {
                completedResult = outcome.completed!!.result
            }
        }

        assertNotNull(completedResult)
        assertEquals(Quantity.inches(Rational.of(96)), completedResult)
        assertEquals(Dimension.LENGTH, completedResult!!.dimension)
    }

    @Test
    fun footInchHistoryBackupSerialization() {
        val entry = HistoryEntry(
            expression = "5' 3 1/2\" + 2' 8 1/2\"",
            result = Quantity.inches(Rational.of(96)).stored(),
            preferInches = false,
        )
        val backup = FootInchBackup(listOf(entry))
        val encoded = ToolInputs.encode(backup)
        val decoded = ToolInputs.decode<FootInchBackup>(encoded)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.history.size)
        assertEquals(entry.expression, decoded.history.first().expression)
        assertEquals(entry.result, decoded.history.first().result)
    }

    @Test
    fun draftItemCreatesExportableProjectItem() {
        val answer = Quantity.inches(Rational.of(95, 2)) // 47.5 inches = 3' 11 1/2"
        val settings = AppSettings()
        val tokens = listOf(Token.Literal("5'"), Token.Op(Operator.SUBTRACT), Token.Literal("4\"1/2"))
        val savedTokens = tokens.map(SavedToken::of)

        val inputs = FootInchInputs(tokens = savedTokens, answer = answer.stored(), preferInches = false)
        val draftInputs = ToolInputs.encode(inputs)

        val item = ProjectItem(
            toolId = FootInchTool.ID,
            title = "Measurement",
            createdAt = 1000L,
            inputs = draftInputs,
            results = listOf(ResultValue.of("result", "Result", answer, "3' 11 1/2\"")),
            summary = "5' − 4 1/2\" = 3' 11 1/2\"",
        )

        assertEquals("foot-inch", item.toolId)
        val restoredInputs = ToolInputs.decode<FootInchInputs>(item.inputs)
        assertNotNull(restoredInputs)
        assertEquals(3, restoredInputs!!.tokens.size)
        assertEquals(answer.stored(), restoredInputs.answer)
    }
}
