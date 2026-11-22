package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.LengthStyle
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.UnitSystem
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

    private val metric = AppSettings(unitSystem = UnitSystem.METRIC)

    @Test
    fun formatsInBothSystems() {
        val q = Quantity.inches(Rational.of(60)) // 5 ft = 1524 mm
        assertEquals("5' 0\"", FootInchFormat.primary(q, AppSettings(), null))
        assertEquals("1.524 m", FootInchFormat.primary(q, metric, null))
        assertTrue(FootInchFormat.conversions(q, AppSettings(), null).any { it.value.endsWith(" mm") })
        assertTrue(FootInchFormat.conversions(q, metric, null).any { it.label == "Feet-inches" && it.value == "5' 0\"" })
        val tokens = listOf(Token.Value(q, null), Token.Op(Operator.ADD), Token.Value(Quantity.inches(Rational.of(6)), null))
        assertEquals("5' 0\" + 6\"", FootInchFormat.expression(tokens, AppSettings(), null))
        assertEquals("1.524 m + 152 mm", FootInchFormat.expression(tokens, metric, null))
    }

    @Test
    fun metricHasNoRoundingHintAndItsOwnExample() {
        val third = Quantity.inches(Rational.of(1, 3))
        assertEquals(-1, FootInchFormat.roundingDirection(third, AppSettings().copy(precision = Precision.entries.first { it.denominator == 16 })))
        assertEquals(0, FootInchFormat.roundingDirection(third, metric))
        assertTrue(FootInchFormat.hint(metric, true).contains("cm"))
        assertTrue(FootInchFormat.hint(AppSettings(), true).contains("'"))
    }

    @Test
    fun savedTokensRoundTripParensSqrtAndSquare() {
        val domain = listOf(
            Token.Sqrt,
            Token.OpenParen,
            Token.Literal("3'"),
            Token.Square,
            Token.Op(Operator.ADD),
            Token.Literal("4'"),
            Token.Square,
            Token.CloseParen,
        )
        val inputs = FootInchInputs(
            tokens = domain.map(SavedToken::of),
            answer = Quantity.feet(Rational.of(5)).stored(),
            preferInches = false,
        )
        val decoded = ToolInputs.decode<FootInchInputs>(ToolInputs.encode(inputs))
        assertNotNull(decoded)
        assertEquals(domain, decoded!!.tokens.map { it.toToken() })
    }

    @Test
    fun historyTapeSupportsLabelsDeletionCapAndBackupRoundTrip() {
        val q1 = Quantity.inches(Rational.of(96)).stored()
        val q2 = Quantity.inches(Rational.of(48)).stored()

        // Add assigns unique ids and puts newest first
        var tape = emptyList<HistoryEntry>()
        tape = HistoryTapeOps.add(tape, HistoryEntry(expression = "4' + 4'", result = q1, preferInches = false))
        tape = HistoryTapeOps.add(tape, HistoryEntry(expression = "2' + 2'", result = q2, preferInches = false))
        assertEquals(2, tape.size)
        assertTrue(tape[0].id != tape[1].id)

        // Label an individual entry
        val targetId = tape[0].id
        tape = HistoryTapeOps.setLabel(tape, targetId, "  Panel feeder  ")
        assertEquals("Panel feeder", tape.first { it.id == targetId }.label)

        // Backup round-trip preserves labels and ids
        val backup = FootInchBackup(tape)
        val decoded = ToolInputs.decode<FootInchBackup>(ToolInputs.encode(backup))
        assertNotNull(decoded)
        assertEquals(2, decoded!!.history.size)
        assertEquals("Panel feeder", decoded.history[0].label)
        assertEquals("", decoded.history[1].label)

        // Delete an individual entry
        tape = HistoryTapeOps.remove(tape, targetId)
        assertEquals(1, tape.size)
        assertEquals("4' + 4'", tape.single().expression)

        // Cap at 500 entries
        var large = emptyList<HistoryEntry>()
        repeat(520) { i ->
            large = HistoryTapeOps.add(large, HistoryEntry(expression = "$i\"", result = q2))
        }
        assertEquals(HistoryTapeOps.MAX, large.size)
        assertEquals("519\"", large.first().expression)
        // SavedToken.PrevAnswer serialization round-trip
        val withPrev = FootInchInputs(
            tokens = listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.ADD), SavedToken.Literal("2'")),
            answer = Quantity.feet(Rational.of(7)).stored(),
            preferInches = false,
        )
        val decodedPrev = ToolInputs.decode<FootInchInputs>(ToolInputs.encode(withPrev))
        assertNotNull(decodedPrev)
        assertEquals(SavedToken.PrevAnswer, decodedPrev!!.tokens.first())
        val resolved = decodedPrev.tokens.map { it.toToken(Quantity.feet(Rational.of(5)), false) }
        assertEquals(Quantity.feet(Rational.of(5)), (resolved[0] as Token.Value).quantity)
        assertTrue((resolved[0] as Token.Value).fromPreviousAnswer)
    }

    @Test
    fun footInchBackupRoundTripsGroupsMemoryAndLegacyHistory() {
        val q1 = Quantity.inches(Rational.of(96)).stored()
        val group = CalcGroup(
            id = 1L,
            label = "Header layout",
            favorite = true,
            steps = listOf(
                CalcStep(id = 1L, tokens = listOf(SavedToken.Literal("5'")), result = Quantity.feet(Rational.of(5)).stored(), preferInches = false),
                CalcStep(id = 2L, tokens = listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.ADD), SavedToken.Literal("3'")), result = q1, preferInches = false),
            ),
            updatedAt = 1234L,
        )
        val mem = listOf(
            MemoryEntry(id = 1L, value = q1, preferInches = false, label = "Beam"),
        )
        val backup = FootInchBackup(
            history = listOf(HistoryEntry("5' + 3'", q1, preferInches = false)),
            groups = listOf(group),
            memory = mem,
            activeMemoryId = 1L,
        )
        val decoded = ToolInputs.decode<FootInchBackup>(ToolInputs.encode(backup))
        assertNotNull(decoded)
        val restored = decoded!!.restoredGroups().single()
        assertTrue(restored.favorite)
        assertEquals("Header layout", restored.label)
        assertEquals(2, restored.steps.size)
        assertEquals(1, decoded.memory.size)
        assertEquals("Beam", decoded.memory.first().label)
        assertEquals(1L, decoded.activeMemoryId)
    }

    @Test
    fun emptyGroupedBackupDoesNotRestoreClearedLegacyRows() {
        val legacy = HistoryEntry("5'", Quantity.feet(Rational.of(5)).stored())
        val oldBackup = FootInchBackup(history = listOf(legacy))
        val currentBackup = oldBackup.copy(groups = emptyList())

        assertEquals(1, oldBackup.restoredGroups().size)
        val decoded = ToolInputs.decode<FootInchBackup>(ToolInputs.encode(currentBackup))
        assertTrue(decoded!!.restoredGroups().isEmpty())
    }
}
