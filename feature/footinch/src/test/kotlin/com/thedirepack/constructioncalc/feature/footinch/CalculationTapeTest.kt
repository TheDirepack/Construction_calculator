package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculationTapeTest {

    private val settings = AppSettings()

    private fun qIn(inches: Int) = Quantity.inches(Rational.of(inches.toLong()))
    private fun qFt(feet: Int) = Quantity.feet(Rational.of(feet.toLong()))
    private fun qNum(n: Int) = Quantity.number(Rational.of(n.toLong()))

    @Test
    fun decomposeSplitsSamePrecedenceAndPreservesMixedPrecedence() {
        // 5' + 3' + 2' splits into 3 linked atomic steps: 5', +3', +2'
        val addChain = listOf(
            Token.Literal("5'"),
            Token.Op(Operator.ADD),
            Token.Literal("3'"),
            Token.Op(Operator.ADD),
            Token.Literal("2'"),
        )
        val decomposed = CalculationTapeOps.decompose(addChain)
        assertEquals(3, decomposed.size)
        assertEquals(listOf(SavedToken.Literal("5'")), decomposed[0])
        assertEquals(listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.ADD), SavedToken.Literal("3'")), decomposed[1])
        assertEquals(listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.ADD), SavedToken.Literal("2'")), decomposed[2])

        // Mixed precedence 5' + 3' × 2 stays intact as a single step so PEMDAS holds
        val mixed = listOf(
            Token.Literal("5'"),
            Token.Op(Operator.ADD),
            Token.Literal("3'"),
            Token.Op(Operator.MULTIPLY),
            Token.Literal("2"),
        )
        val mixedDecomposed = CalculationTapeOps.decompose(mixed)
        assertEquals(1, mixedDecomposed.size)
        assertEquals(5, mixedDecomposed[0].size)

        // Continuation from previous answer skips dummy first step
        val cont = listOf(
            Token.Value(qFt(5), false, fromPreviousAnswer = true),
            Token.Op(Operator.MULTIPLY),
            Token.Literal("2"),
            Token.Op(Operator.DIVIDE),
            Token.Literal("4"),
        )
        val contDecomposed = CalculationTapeOps.decompose(cont)
        assertEquals(2, contDecomposed.size)
        assertEquals(listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.MULTIPLY), SavedToken.Literal("2")), contDecomposed[0])
        assertEquals(listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.DIVIDE), SavedToken.Literal("4")), contDecomposed[1])
    }

    @Test
    fun chainedCalculationsGroupTogetherAndFreshStartsSeparate() {
        // Group 1: 5' + 3' = 8', then + 2' = 10', then × 2 = 20'
        val (after1, group1) = CalculationTapeOps.startNewGroup(
            emptyList(),
            listOf(Token.Literal("5'"), Token.Op(Operator.ADD), Token.Literal("3'")),
            nowMillis = 100L,
        )
        assertEquals(1, after1.size)
        assertEquals(2, group1.steps.size)
        assertEquals(qFt(8), group1.finalResult!!.toQuantity())

        val append1 = CalculationTapeOps.appendToGroup(
            after1,
            group1.id,
            listOf(Token.Value(qFt(8), false, true), Token.Op(Operator.ADD), Token.Literal("2'")),
            nowMillis = 200L,
        )!!
        val append2 = CalculationTapeOps.appendToGroup(
            append1.groups,
            group1.id,
            listOf(Token.Value(qFt(10), false, true), Token.Op(Operator.MULTIPLY), Token.Literal("2")),
            nowMillis = 300L,
        )!!
        assertEquals(1, append2.groups.size)
        assertEquals(4, append2.groups.single().steps.size)
        assertEquals(qFt(20), append2.groups.single().finalResult!!.toQuantity())

        // Group 2 starts from scratch -> separate group at top
        val (withTwoGroups, group2) = CalculationTapeOps.startNewGroup(
            append2.groups,
            listOf(Token.Literal("10'"), Token.Op(Operator.SUBTRACT), Token.Literal("4'")),
            nowMillis = 400L,
        )
        assertEquals(2, withTwoGroups.size)
        assertEquals(group2.id, withTwoGroups[0].id)
        assertEquals(group1.id, withTwoGroups[1].id)
        assertEquals(qFt(6), withTwoGroups[0].finalResult!!.toQuantity())
        assertEquals(qFt(20), withTwoGroups[1].finalResult!!.toQuantity())
    }

    @Test
    fun displayedAnswerWithoutAnActiveGroupBecomesAnExactOpeningInput() {
        val seed = Token.Value(qIn(17), preferInches = true, fromPreviousAnswer = true)
        val expression = listOf(seed, Token.Op(Operator.MULTIPLY), Token.Literal("2"))
        val (groups, group) = CalculationTapeOps.startNewGroup(emptyList(), expression)
        assertEquals(listOf(SavedToken.Value(qIn(17).stored(), true)), group.steps.first().tokens)
        assertEquals(qIn(34), group.finalResult?.toQuantity())

        val correction = CalculationTapeOps.replaceLastSteps(
            groups, group.id, group.steps.size,
            listOf(seed, Token.Op(Operator.MULTIPLY), Token.Literal("3")),
        )
        assertEquals(qIn(51), correction?.groups?.single()?.finalResult?.toQuantity())
    }

    @Test
    fun editingEarlierStepCascadesThroughAllFutureStepsAndPreferInches() {
        // Group starts in inches: 96" + 4" + 20" * 2
        val (g0, grp) = CalculationTapeOps.startNewGroup(
            emptyList(),
            listOf(Token.Literal("96\""), Token.Op(Operator.ADD), Token.Literal("4\"")),
        )
        val g1 = CalculationTapeOps.appendToGroup(
            g0,
            grp.id,
            listOf(Token.Value(qIn(100), true, true), Token.Op(Operator.MULTIPLY), Token.Literal("2")),
        )!!.groups
        assertEquals(true, g1.single().finalPreferInches)
        assertEquals(qIn(200), g1.single().finalResult!!.toQuantity())

        // Edit Step 0 from 96" -> 8' (switches from inches-only to ft-in and cascades: 8' + 4" = 8'4", * 2 = 16'8")
        val step0Id = g1.single().steps[0].id
        val edited = CalculationTapeOps.editStep(g1, grp.id, step0Id, listOf(SavedToken.Literal("10'")))
        assertTrue(edited is TapeEditResult.Updated)
        val updatedGroup = (edited as TapeEditResult.Updated).group!!
        assertEquals(qFt(10), updatedGroup.steps[0].result.toQuantity())
        assertEquals(false, updatedGroup.steps[0].preferInches)
        assertEquals(qIn(124), updatedGroup.steps[1].result.toQuantity())
        assertEquals(false, updatedGroup.steps[1].preferInches)
        assertEquals(qIn(248), updatedGroup.steps[2].result.toQuantity())
        assertEquals(false, updatedGroup.steps[2].preferInches)

        // Edit intermediate step 1 operator + -> − (10' - 4" = 9'8", * 2 = 19'4")
        val step1Id = updatedGroup.steps[1].id
        val editedStep1 = CalculationTapeOps.editStep(
            edited.groups,
            grp.id,
            step1Id,
            listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.SUBTRACT), SavedToken.Literal("4\"")),
        ) as TapeEditResult.Updated
        assertEquals(qIn(116), editedStep1.group!!.steps[1].result.toQuantity())
        assertEquals(qIn(232), editedStep1.group.steps[2].result.toQuantity())
    }

    @Test
    fun editCompoundStepCascadesDownstream() {
        // Step 0: 5' + 3' × 2 = 11' ; Step 1: + 4' = 15'
        val (g0, grp) = CalculationTapeOps.startNewGroup(
            emptyList(),
            listOf(Token.Literal("5'"), Token.Op(Operator.ADD), Token.Literal("3'"), Token.Op(Operator.MULTIPLY), Token.Literal("2")),
        )
        val g1 = CalculationTapeOps.appendToGroup(
            g0,
            grp.id,
            listOf(Token.Value(qFt(11), false, true), Token.Op(Operator.ADD), Token.Literal("4'")),
        )!!.groups

        val compoundStep = g1.single().steps[0]
        val valueIndices = CalculationTapeOps.editableValueIndices(compoundStep.tokens)
        assertEquals(listOf(0, 2, 4), valueIndices)

        // Replace second operand 3' (index 2) -> 4' => 5' + 4' × 2 = 13' => Step 1 = 17'
        val nextTokens = CalculationTapeOps.replaceToken(compoundStep.tokens, 2, listOf(SavedToken.Literal("4'")))
        val edited = CalculationTapeOps.editStep(g1, grp.id, compoundStep.id, nextTokens) as TapeEditResult.Updated
        assertEquals(qFt(13), edited.group!!.steps[0].result.toQuantity())
        assertEquals(qFt(17), edited.group.steps[1].result.toQuantity())
    }

    @Test
    fun invalidEditBlockedWithoutCorruptingGroup() {
        // Step 0: 6' , Step 1: ÷ 2 (= 3'), Step 2: × 2 (= 6')
        val (g0, grp) = CalculationTapeOps.startNewGroup(
            emptyList(),
            listOf(Token.Literal("6'"), Token.Op(Operator.DIVIDE), Token.Literal("2")),
        )
        val g1 = CalculationTapeOps.appendToGroup(
            g0,
            grp.id,
            listOf(Token.Value(qFt(3), false, true), Token.Op(Operator.ADD), Token.Literal("2'")),
        )!!.groups

        // Divide by zero in Step 1 is rejected
        val divStepId = g1.single().steps[1].id
        val divZero = CalculationTapeOps.editStep(
            g1,
            grp.id,
            divStepId,
            listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.DIVIDE), SavedToken.Literal("0")),
        )
        assertTrue(divZero is TapeEditResult.Invalid)
        assertEquals(1, (divZero as TapeEditResult.Invalid).stepIndex)
        assertTrue(divZero.message.contains("Step 2"))

        // Edit Step 1 from `÷ 2` -> `× 2'` makes area; downstream Step 2 (`+ 2'` length) fails dimension check!
        val mismatch = CalculationTapeOps.editStep(
            g1,
            grp.id,
            divStepId,
            listOf(SavedToken.PrevAnswer, SavedToken.Op(Operator.MULTIPLY), SavedToken.Literal("2'")),
        )
        assertTrue(mismatch is TapeEditResult.Invalid)
        assertEquals(2, (mismatch as TapeEditResult.Invalid).stepIndex)
        assertTrue(mismatch.message.contains("Step 3"))
    }

    @Test
    fun deleteStepCascadesAndAdaptsOpeningStep() {
        val (g0, grp) = CalculationTapeOps.startNewGroup(
            emptyList(),
            listOf(Token.Literal("5'"), Token.Op(Operator.ADD), Token.Literal("3'")),
        )
        val g1 = CalculationTapeOps.appendToGroup(
            g0,
            grp.id,
            listOf(Token.Value(qFt(8), false, true), Token.Op(Operator.MULTIPLY), Token.Literal("2")),
        )!!.groups

        // Delete Step 1 (+ 3') -> Step 0 (5') * Step 2 (* 2) = 10'
        val delMid = CalculationTapeOps.deleteStep(g1, grp.id, g1.single().steps[1].id) as TapeEditResult.Updated
        assertEquals(2, delMid.group!!.steps.size)
        assertEquals(qFt(10), delMid.group.finalResult!!.toQuantity())

        // Delete Step 0 before (+ 3') -> Step 1 becomes 3', and * 2 becomes 6'
        val delFirst = CalculationTapeOps.deleteStep(g1, grp.id, g1.single().steps[0].id) as TapeEditResult.Updated
        assertEquals(2, delFirst.group!!.steps.size)
        assertEquals(qFt(3), delFirst.group.steps[0].result.toQuantity())
        assertEquals(qFt(6), delFirst.group.finalResult!!.toQuantity())

        // Deleting opening step right before × 2 (from delMid) is blocked cleanly
        val badDelete = CalculationTapeOps.deleteStep(delMid.groups, grp.id, delMid.group.steps[0].id)
        assertTrue(badDelete is TapeEditResult.Invalid)

        // Deleting last remaining step removes the whole group
        val singleStepOnly = CalculationTapeOps.deleteStep(delFirst.groups, grp.id, delFirst.group.steps[1].id) as TapeEditResult.Updated
        val emptyRemoved = CalculationTapeOps.deleteStep(singleStepOnly.groups, grp.id, singleStepOnly.group!!.steps[0].id) as TapeEditResult.Updated
        assertNull(emptyRemoved.group)
        assertTrue(emptyRemoved.groups.isEmpty())
    }

    @Test
    fun favoritesSurviveClearRecentAndRetentionCap() {
        var groups = emptyList<CalcGroup>()
        val (withFav, favGroup) = CalculationTapeOps.startNewGroup(groups, listOf(Token.Literal("1'")))
        groups = CalculationTapeOps.toggleFavorite(withFav, favGroup.id)
        groups = CalculationTapeOps.setGroupLabel(groups, favGroup.id, "Pinned")

        // Push 110 recent non-favorite groups after the favorite
        repeat(110) { i ->
            groups = CalculationTapeOps.startNewGroup(groups, listOf(Token.Literal("${i + 2}'"))).first
        }

        // Favorite survives past MAX_RECENT_GROUPS = 100 while recent entries are capped at 100
        assertEquals(CalculationTapeOps.MAX_RECENT_GROUPS + 1, groups.size)
        assertTrue(groups.any { it.id == favGroup.id && it.favorite && it.label == "Pinned" })

        // Clear recent preserves favorites only
        val clearedRecent = CalculationTapeOps.clearRecent(groups)
        assertEquals(1, clearedRecent.size)
        assertEquals(favGroup.id, clearedRecent.single().id)
    }

    @Test
    fun legacyMigrationAndFormattingWorkCleanly() {
        val legacy = listOf(
            HistoryEntry(expression = "4' + 4'", result = qFt(8).stored(), preferInches = false, label = "Wall", id = 7L),
        )
        val migrated = CalculationTapeOps.migrateLegacyHistory(legacy)
        assertEquals(1, migrated.size)
        assertEquals("Wall", migrated.single().label)
        assertEquals("4' + 4'", CalculationTapeOps.formatStepInput(migrated.single().steps.single(), settings, null))

        // Appending onto a migrated legacy group still works seamlessly
        val appended = CalculationTapeOps.appendToGroup(
            migrated,
            migrated.single().id,
            listOf(Token.Value(qFt(8), false, true), Token.Op(Operator.ADD), Token.Literal("2'")),
        )!!
        assertEquals(qFt(10), appended.groups.single().finalResult!!.toQuantity())
        assertEquals("+ 2'", CalculationTapeOps.formatStepInput(appended.groups.single().steps[1], settings, false))
    }

    @Test
    fun multiSlotMemorySupportsStoreRecallAddSubMulDivAndValidation() {
        var mem = emptyList<MemoryEntry>()
        var activeId: Long? = null

        // MS stores multiple slots (newest active)
        val (m1, id1) = MemoryOps.store(mem, qFt(5), false, label = "Header")
        mem = m1; activeId = id1
        val (m2, id2) = MemoryOps.store(mem, qIn(30), true)
        mem = m2; activeId = id2
        assertEquals(2, mem.size)
        assertEquals(id2, activeId)

        // M+ on slot 2 (30" + 6" = 36")
        val addRes = MemoryOps.applyOperator(mem, activeId, id2, Operator.ADD, qIn(6), true) as MemoryOpResult.Success
        mem = addRes.entries; activeId = addRes.activeId
        assertEquals(qIn(36), MemoryOps.activeEntry(mem, activeId)!!.value.toQuantity())

        // M× on active slot (36" * 2 = 72")
        val mulRes = MemoryOps.applyOperator(mem, activeId, null, Operator.MULTIPLY, qNum(2), null) as MemoryOpResult.Success
        mem = mulRes.entries; activeId = mulRes.activeId
        assertEquals(qIn(72), MemoryOps.activeEntry(mem, activeId)!!.value.toQuantity())

        // M÷ on active slot by length (72" ÷ 12" = number 6)
        val divRes = MemoryOps.applyOperator(mem, activeId, null, Operator.DIVIDE, qIn(12), true) as MemoryOpResult.Success
        mem = divRes.entries; activeId = divRes.activeId
        assertEquals(qNum(6), MemoryOps.activeEntry(mem, activeId)!!.value.toQuantity())

        // Incompatible unit operation (number 6 + 1 sq ft area) returns Error and keeps memory unchanged
        val area = Quantity(Rational.of(144L), Dimension.AREA)
        val badOp = MemoryOps.applyOperator(mem, activeId, null, Operator.ADD, area, false)
        assertTrue(badOp is MemoryOpResult.Error)
        assertEquals(qNum(6), MemoryOps.activeEntry(mem, activeId)!!.value.toQuantity())

        // Divide by zero returns Error
        val divZero = MemoryOps.applyOperator(mem, activeId, null, Operator.DIVIDE, qNum(0), null)
        assertTrue(divZero is MemoryOpResult.Error)

        // M− to 0 on unlabeled slot removes that slot and falls back active to Header
        val clearToZero = MemoryOps.applyOperator(mem, activeId, id2, Operator.SUBTRACT, qNum(6), null) as MemoryOpResult.Success
        mem = clearToZero.entries; activeId = clearToZero.activeId
        assertEquals(1, mem.size)
        assertEquals(id1, activeId)

        // Labeled slot reaching 0 via M− is kept rather than deleted
        val labeledZero = MemoryOps.applyOperator(mem, activeId, id1, Operator.SUBTRACT, qFt(5), false) as MemoryOpResult.Success
        mem = labeledZero.entries
        assertEquals(1, mem.size)
        assertEquals(Dimension.LENGTH, mem.single().value.dimension)
        assertTrue(mem.single().value.toQuantity().value.isZero)

        // Explicit remove clears labeled slot and activeId becomes null
        val (emptyMem, noActive) = MemoryOps.remove(mem, activeId, id1)
        assertTrue(emptyMem.isEmpty())
        assertNull(noActive)
    }
}
