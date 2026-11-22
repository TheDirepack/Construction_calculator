package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.CalcException
import com.thedirepack.constructioncalc.core.measure.Evaluator
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.MeasureLiteral
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.lengthFormat
import kotlinx.serialization.Serializable

/** One committed step inside a related [CalcGroup]. */
@Serializable
data class CalcStep(
    val id: Long = 0L,
    val tokens: List<SavedToken> = emptyList(),
    val result: StoredQuantity,
    val preferInches: Boolean? = null,
    val label: String = "",
    /** Non-empty only for migrated pre-grouping [HistoryEntry] rows without saved tokens. */
    val legacyExpression: String = "",
)

/** A group of related calculation steps separated from earlier or cleared calculations. */
@Serializable
data class CalcGroup(
    val id: Long = 0L,
    val steps: List<CalcStep> = emptyList(),
    val label: String = "",
    val favorite: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val finalStep: CalcStep? get() = steps.lastOrNull()
    val finalResult: StoredQuantity? get() = finalStep?.result
    val finalPreferInches: Boolean? get() = finalStep?.preferInches
}

/** One persisted memory register slot (`MS`, `MR`, `M+`, `M−`, `M×`, `M÷`, `MC`). */
@Serializable
data class MemoryEntry(
    val id: Long = 0L,
    val value: StoredQuantity,
    val preferInches: Boolean? = null,
    val label: String = "",
)

sealed interface CascadeResult {
    data class Success(val steps: List<CalcStep>) : CascadeResult
    data class Failure(val stepIndex: Int, val message: String) : CascadeResult
}

sealed interface TapeEditResult {
    data class Updated(val groups: List<CalcGroup>, val group: CalcGroup?) : TapeEditResult
    data class Invalid(val stepIndex: Int, val message: String) : TapeEditResult
}

data class GroupAppendOutcome(
    val groups: List<CalcGroup>,
    val appendedCount: Int,
)

sealed interface EditableStepSpec {
    /** A chained continuation step `[PrevAnswer, Op, operand...]` (e.g. `+ 5' 3"`). */
    data class BinaryContinuation(
        val operator: Operator,
        val operandTokens: List<SavedToken>,
    ) : EditableStepSpec

    /** Opening step or compound/prefix/postfix expression. */
    data class FullExpression(
        val tokens: List<SavedToken>,
        val hasPrevAnswer: Boolean,
    ) : EditableStepSpec
}

/** Pure operations for grouped calculations, cascading step edits, and migrations. */
internal object CalculationTapeOps {
    const val MAX_RECENT_GROUPS = 100

    /**
     * Breaks an expression into sequential atomic steps when top-level (depth-0) binary operators
     * all share one precedence level, so each step's value can be edited individually and
     * cascaded forward. Mixed-precedence expressions (e.g. `5' + 3' × 2`) stay intact so PEMDAS
     * order of operations is never altered.
     */
    fun decompose(tokens: List<Token>): List<List<SavedToken>> {
        val saved = tokens.map(SavedToken::ofForTape)
        if (saved.isEmpty()) return emptyList()

        val topOps = mutableListOf<Pair<Int, Operator>>()
        var depth = 0
        for (i in saved.indices) {
            when (val tok = saved[i]) {
                SavedToken.OpenParen -> depth++
                SavedToken.CloseParen -> if (depth > 0) depth--
                is SavedToken.Op -> if (depth == 0 && !isUnaryMinus(saved, i)) {
                    topOps += i to tok.operator
                }
                else -> Unit
            }
        }

        if (topOps.isEmpty()) return listOf(saved)

        val precedence = topOps.first().second.precedence
        if (topOps.any { it.second.precedence != precedence }) {
            return listOf(saved)
        }

        val chunks = ArrayList<List<SavedToken>>(topOps.size + 1)
        var start = 0
        for ((idx, _) in topOps) {
            val chunk = saved.subList(start, idx)
            if (chunk.isEmpty()) return listOf(saved)
            chunks += chunk
            start = idx + 1
        }
        val tail = saved.subList(start, saved.size)
        if (tail.isEmpty()) return listOf(saved)
        chunks += tail

        // Do not split if a subsequent chunk explicitly references the pre-expression PrevAnswer.
        if (chunks.drop(1).any { SavedToken.PrevAnswer in it }) {
            return listOf(saved)
        }

        val steps = ArrayList<List<SavedToken>>(chunks.size)
        val startsWithPrev = chunks.first() == listOf(SavedToken.PrevAnswer)
        if (!startsWithPrev) {
            steps += chunks.first()
        }
        for (i in topOps.indices) {
            steps += listOf(SavedToken.PrevAnswer, SavedToken.Op(topOps[i].second)) + chunks[i + 1]
        }
        return steps
    }

    /** Evaluates [steps] from first to last, propagating every updated result downstream. */
    fun evaluateSteps(steps: List<CalcStep>): CascadeResult {
        if (steps.isEmpty()) return CascadeResult.Success(emptyList())
        var prevQty: Quantity? = null
        var prevPreferInches: Boolean? = null
        val evaluated = ArrayList<CalcStep>(steps.size)
        for (i in steps.indices) {
            val step = steps[i]
            if (step.tokens.isEmpty()) {
                val q = step.result.toQuantity()
                prevQty = q
                prevPreferInches = step.preferInches
                evaluated += step
                continue
            }
            try {
                val resolved = step.tokens.map { it.toToken(prevQty, prevPreferInches) }
                val qty = Evaluator.evaluate(resolved)
                val inches = CalcInput.prefersInches(resolved)
                val next = step.copy(result = qty.stored(), preferInches = inches)
                prevQty = qty
                prevPreferInches = inches
                evaluated += next
            } catch (e: CalcException) {
                return CascadeResult.Failure(i, "Step ${i + 1}: ${e.message ?: "Invalid calculation"}")
            }
        }
        return CascadeResult.Success(evaluated)
    }

    // A restored or cleared tape can leave a valid answer on the display. The next
    // group must store that answer as an exact input because it has no preceding step.
    private fun snapshotPreviousAnswer(tokens: List<Token>): List<Token> = tokens.map { token ->
        if (token is Token.Value && token.fromPreviousAnswer) token.copy(fromPreviousAnswer = false)
        else token
    }

    fun startNewGroup(
        current: List<CalcGroup>,
        expressionTokens: List<Token>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<List<CalcGroup>, CalcGroup> {
        val decomposed = decompose(snapshotPreviousAnswer(expressionTokens))
        val rawSteps = decomposed.mapIndexed { idx, stepTokens ->
            CalcStep(
                id = (idx + 1).toLong(),
                tokens = stepTokens,
                result = Quantity.number(com.thedirepack.constructioncalc.core.math.Rational.ZERO).stored(),
            )
        }
        val evaluated = (evaluateSteps(rawSteps) as CascadeResult.Success).steps
        val nextGroupId = nextGroupId(current)
        val group = CalcGroup(
            id = nextGroupId,
            steps = assignStepIds(evaluated),
            updatedAt = nowMillis,
        )
        val updated = trimGroups(listOf(group) + current)
        return updated to group
    }

    fun appendToGroup(
        current: List<CalcGroup>,
        groupId: Long,
        expressionTokens: List<Token>,
        nowMillis: Long = System.currentTimeMillis(),
    ): GroupAppendOutcome? {
        val target = current.firstOrNull { it.id == groupId } ?: return null
        val decomposed = decompose(expressionTokens)
        val placeholder = Quantity.number(com.thedirepack.constructioncalc.core.math.Rational.ZERO).stored()
        val existingMaxId = (target.steps.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)
        val appended = decomposed.mapIndexed { idx, stepTokens ->
            CalcStep(id = existingMaxId + idx + 1L, tokens = stepTokens, result = placeholder)
        }
        val cascade = evaluateSteps(target.steps + appended)
        if (cascade !is CascadeResult.Success) return null
        val updatedGroup = target.copy(steps = assignStepIds(cascade.steps), updatedAt = nowMillis)
        val reordered = trimGroups(listOf(updatedGroup) + current.filterNot { it.id == groupId })
        return GroupAppendOutcome(reordered, appended.size)
    }

    fun replaceLastSteps(
        current: List<CalcGroup>,
        groupId: Long,
        replaceCount: Int,
        expressionTokens: List<Token>,
        nowMillis: Long = System.currentTimeMillis(),
    ): GroupAppendOutcome? {
        val target = current.firstOrNull { it.id == groupId } ?: return null
        val keepCount = (target.steps.size - replaceCount).coerceAtLeast(0)
        if (keepCount == 0) {
            val decomposed = decompose(snapshotPreviousAnswer(expressionTokens))
            val placeholder = Quantity.number(com.thedirepack.constructioncalc.core.math.Rational.ZERO).stored()
            val raw = decomposed.mapIndexed { idx, stepTokens ->
                CalcStep(id = (idx + 1).toLong(), tokens = stepTokens, result = placeholder)
            }
            val cascade = evaluateSteps(raw) as? CascadeResult.Success ?: return null
            val updatedGroup = target.copy(steps = assignStepIds(cascade.steps), updatedAt = nowMillis)
            val reordered = trimGroups(listOf(updatedGroup) + current.filterNot { it.id == groupId })
            return GroupAppendOutcome(reordered, cascade.steps.size)
        }
        val prefix = target.steps.take(keepCount)
        val decomposed = decompose(expressionTokens)
        val placeholder = Quantity.number(com.thedirepack.constructioncalc.core.math.Rational.ZERO).stored()
        val maxId = (prefix.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)
        val replacement = decomposed.mapIndexed { idx, stepTokens ->
            CalcStep(id = maxId + idx + 1L, tokens = stepTokens, result = placeholder)
        }
        val cascade = evaluateSteps(prefix + replacement)
        if (cascade !is CascadeResult.Success) return null
        val updatedGroup = target.copy(steps = assignStepIds(cascade.steps), updatedAt = nowMillis)
        val reordered = trimGroups(listOf(updatedGroup) + current.filterNot { it.id == groupId })
        return GroupAppendOutcome(reordered, replacement.size)
    }

    /** Edits [stepId] within [groupId] and cascades recomputation through all subsequent steps. */
    fun editStep(
        current: List<CalcGroup>,
        groupId: Long,
        stepId: Long,
        newTokens: List<SavedToken>,
        nowMillis: Long = System.currentTimeMillis(),
    ): TapeEditResult {
        val group = current.firstOrNull { it.id == groupId }
            ?: return TapeEditResult.Invalid(0, "Calculation group not found")
        val idx = group.steps.indexOfFirst { it.id == stepId }
        if (idx < 0) return TapeEditResult.Invalid(0, "Step not found")
        if (newTokens.isEmpty()) return TapeEditResult.Invalid(idx, "Step ${idx + 1}: Empty expression")

        val replacedSteps = group.steps.mapIndexed { i, step ->
            if (i == idx) step.copy(tokens = newTokens, legacyExpression = "") else step
        }
        return when (val cascade = evaluateSteps(replacedSteps)) {
            is CascadeResult.Failure -> TapeEditResult.Invalid(cascade.stepIndex, cascade.message)
            is CascadeResult.Success -> {
                val updatedGroup = group.copy(steps = cascade.steps, updatedAt = nowMillis)
                val updatedList = current.map { if (it.id == groupId) updatedGroup else it }
                TapeEditResult.Updated(updatedList, updatedGroup)
            }
        }
    }

    /** Deletes [stepId] within [groupId], adapting step 0 if needed and validating downstream steps. */
    fun deleteStep(
        current: List<CalcGroup>,
        groupId: Long,
        stepId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): TapeEditResult {
        val group = current.firstOrNull { it.id == groupId }
            ?: return TapeEditResult.Invalid(0, "Calculation group not found")
        val idx = group.steps.indexOfFirst { it.id == stepId }
        if (idx < 0) return TapeEditResult.Invalid(0, "Step not found")

        val remaining = group.steps.filterNot { it.id == stepId }.toMutableList()
        if (remaining.isEmpty()) {
            return TapeEditResult.Updated(current.filterNot { it.id == groupId }, null)
        }

        if (idx == 0 && remaining.first().tokens.isNotEmpty()) {
            val firstTokens = remaining.first().tokens
            if (firstTokens.size >= 3 &&
                firstTokens[0] == SavedToken.PrevAnswer &&
                firstTokens[1] is SavedToken.Op
            ) {
                val op = (firstTokens[1] as SavedToken.Op).operator
                val rest = firstTokens.drop(2)
                when (op) {
                    Operator.ADD -> if (SavedToken.PrevAnswer !in rest) {
                        remaining[0] = remaining[0].copy(tokens = rest)
                    }
                    Operator.SUBTRACT -> if (SavedToken.PrevAnswer !in rest) {
                        remaining[0] = remaining[0].copy(tokens = listOf(SavedToken.Op(Operator.SUBTRACT)) + rest)
                    }
                    Operator.MULTIPLY, Operator.DIVIDE -> Unit
                }
            }
        }

        return when (val cascade = evaluateSteps(remaining)) {
            is CascadeResult.Failure -> TapeEditResult.Invalid(cascade.stepIndex, cascade.message)
            is CascadeResult.Success -> {
                val updatedGroup = group.copy(steps = cascade.steps, updatedAt = nowMillis)
                val updatedList = current.map { if (it.id == groupId) updatedGroup else it }
                TapeEditResult.Updated(updatedList, updatedGroup)
            }
        }
    }

    fun classifyForEdit(step: CalcStep, stepIndex: Int): EditableStepSpec {
        val tokens = step.tokens
        if (stepIndex > 0 &&
            tokens.size >= 3 &&
            tokens[0] == SavedToken.PrevAnswer &&
            tokens[1] is SavedToken.Op
        ) {
            val operand = tokens.drop(2)
            if (operand.isNotEmpty() && SavedToken.PrevAnswer !in operand) {
                return EditableStepSpec.BinaryContinuation(
                    operator = (tokens[1] as SavedToken.Op).operator,
                    operandTokens = operand,
                )
            }
        }
        return EditableStepSpec.FullExpression(
            tokens = tokens,
            hasPrevAnswer = SavedToken.PrevAnswer in tokens,
        )
    }

    /** Token indices in [tokens] representing directly editable numbers or measurements. */
    fun editableValueIndices(tokens: List<SavedToken>): List<Int> =
        tokens.indices.filter { tokens[it] is SavedToken.Literal || tokens[it] is SavedToken.Value }

    /** Replaces the token at [index] with [replacement], parenthesizing multi-token replacements inside compound expressions. */
    fun replaceToken(
        tokens: List<SavedToken>,
        index: Int,
        replacement: List<SavedToken>,
    ): List<SavedToken> {
        if (index !in tokens.indices || replacement.isEmpty()) return tokens
        val wrapped = if (tokens.size > 1 && replacement.size > 1) {
            listOf(SavedToken.OpenParen) + replacement + SavedToken.CloseParen
        } else {
            replacement
        }
        return tokens.subList(0, index) + wrapped + tokens.subList(index + 1, tokens.size)
    }

    /** Formats a step's entered input (e.g. `5' 3 1/2"`, `+ 2' 8"`, `× 2`, `√(Ans² + 3'²)`). */
    fun formatStepInput(
        step: CalcStep,
        settings: AppSettings,
        prevPreferInches: Boolean?,
    ): String {
        if (step.tokens.isEmpty()) return step.legacyExpression
        val t = step.tokens
        if (t.size >= 3 && t[0] == SavedToken.PrevAnswer && t[1] is SavedToken.Op) {
            val rest = t.drop(2)
            if (SavedToken.PrevAnswer !in rest) {
                val op = (t[1] as SavedToken.Op).operator
                return "${op.symbol} ${formatTokens(rest, settings, prevPreferInches)}"
            }
        }
        return formatTokens(t, settings, prevPreferInches)
    }

    fun formatTokens(
        tokens: List<SavedToken>,
        settings: AppSettings,
        prevPreferInches: Boolean?,
    ): String {
        val format = settings.lengthFormat(inferPreferInches(tokens, prevPreferInches))
        return buildString {
            tokens.forEachIndexed { i, token ->
                val previous = tokens.getOrNull(i - 1)
                val afterUnaryMinus = previous is SavedToken.Op && previous.operator == Operator.SUBTRACT &&
                    (i == 1 || tokens[i - 2] is SavedToken.Op || tokens[i - 2] == SavedToken.OpenParen || tokens[i - 2] == SavedToken.Sqrt)
                val noSpaceBefore = i == 0 ||
                    afterUnaryMinus ||
                    previous == SavedToken.OpenParen ||
                    previous == SavedToken.Sqrt ||
                    token == SavedToken.CloseParen ||
                    token == SavedToken.Square
                if (!noSpaceBefore) append(' ')
                when (token) {
                    is SavedToken.Literal -> append(MeasureLiteral.display(token.text))
                    is SavedToken.Value -> append(MeasureFormat.primary(token.quantity.toQuantity(), format))
                    SavedToken.PrevAnswer -> append("Ans")
                    is SavedToken.Op -> append(token.operator.symbol)
                    SavedToken.OpenParen -> append('(')
                    SavedToken.CloseParen -> append(')')
                    SavedToken.Sqrt -> append('√')
                    SavedToken.Square -> append('²')
                }
            }
        }
    }

    private fun inferPreferInches(tokens: List<SavedToken>, prevPreferInches: Boolean?): Boolean? {
        var sawInches = false
        for (token in tokens) {
            when (token) {
                is SavedToken.Literal -> when {
                    MeasureLiteral.FEET in token.text -> return false
                    MeasureLiteral.INCH in token.text -> sawInches = true
                }
                is SavedToken.Value -> when (token.preferInches) {
                    false -> return false
                    true -> sawInches = true
                    null -> Unit
                }
                SavedToken.PrevAnswer -> when (prevPreferInches) {
                    false -> return false
                    true -> sawInches = true
                    null -> Unit
                }
                else -> Unit
            }
        }
        return if (sawInches) true else null
    }

    /** Resolves `stepIndex` tokens using the immediately preceding step's result for loading into `CalcState`. */
    fun resolvedTokensForStep(group: CalcGroup, stepIndex: Int): List<Token> {
        val step = group.steps.getOrNull(stepIndex) ?: return emptyList()
        if (step.tokens.isEmpty()) {
            return listOf(Token.Value(step.result.toQuantity(), step.preferInches))
        }
        val prev = group.steps.getOrNull(stepIndex - 1)
        val prevQty = prev?.result?.toQuantity()
        val prevInches = prev?.preferInches
        return step.tokens.map { it.toToken(prevQty, prevInches) }
    }

    fun toggleFavorite(current: List<CalcGroup>, groupId: Long): List<CalcGroup> =
        trimGroups(current.map { if (it.id == groupId) it.copy(favorite = !it.favorite) else it })

    fun setGroupLabel(current: List<CalcGroup>, groupId: Long, label: String): List<CalcGroup> {
        val clean = label.trim()
        return current.map { if (it.id == groupId) it.copy(label = clean) else it }
    }

    fun setStepLabel(current: List<CalcGroup>, groupId: Long, stepId: Long, label: String): List<CalcGroup> {
        val clean = label.trim()
        return current.map { g ->
            if (g.id != groupId) g
            else g.copy(steps = g.steps.map { s -> if (s.id == stepId) s.copy(label = clean) else s })
        }
    }

    fun removeGroup(current: List<CalcGroup>, groupId: Long): List<CalcGroup> =
        current.filterNot { it.id == groupId }

    fun clearRecent(current: List<CalcGroup>): List<CalcGroup> =
        current.filter { it.favorite }

    fun clearAll(): List<CalcGroup> = emptyList()

    fun migrateLegacyHistory(entries: List<HistoryEntry>): List<CalcGroup> =
        entries.mapIndexed { idx, entry ->
            val groupId = if (entry.id > 0L) entry.id else (idx + 1).toLong()
            CalcGroup(
                id = groupId,
                label = entry.label,
                steps = listOf(
                    CalcStep(
                        id = 1L,
                        tokens = emptyList(),
                        result = entry.result,
                        preferInches = entry.preferInches,
                        label = entry.label,
                        legacyExpression = entry.expression,
                    ),
                ),
                updatedAt = 0L,
            )
        }.let(::ensureUniqueIds)

    fun ensureUniqueIds(groups: List<CalcGroup>): List<CalcGroup> {
        val used = HashSet<Long>(groups.size)
        var nextId = (groups.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        return trimGroups(
            groups.filter { it.steps.isNotEmpty() }.map { g ->
                val gid = if (g.id > 0L && used.add(g.id)) {
                    g.id
                } else {
                    while (!used.add(nextId)) nextId++
                    nextId++
                }
                g.copy(id = gid, steps = assignStepIds(g.steps))
            },
        )
    }

    fun mergeGroups(current: List<CalcGroup>, restored: List<CalcGroup>, replace: Boolean): List<CalcGroup> {
        val combined = if (replace) {
            restored
        } else {
            (restored + current).distinctBy { g ->
                g.label to g.steps.map { it.tokens to it.result to it.legacyExpression }
            }
        }
        return ensureUniqueIds(combined)
    }

    private fun assignStepIds(steps: List<CalcStep>): List<CalcStep> {
        val used = HashSet<Long>(steps.size)
        var nextId = (steps.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        return steps.map { step ->
            if (step.id > 0L && used.add(step.id)) {
                step
            } else {
                while (!used.add(nextId)) nextId++
                step.copy(id = nextId++)
            }
        }
    }

    private fun nextGroupId(groups: List<CalcGroup>): Long =
        (groups.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L

    private fun trimGroups(groups: List<CalcGroup>): List<CalcGroup> {
        var recentCount = 0
        return groups.filter { g ->
            if (g.favorite) true
            else {
                recentCount++
                recentCount <= MAX_RECENT_GROUPS
            }
        }
    }

    private fun isUnaryMinus(tokens: List<SavedToken>, i: Int): Boolean {
        val op = (tokens[i] as? SavedToken.Op)?.operator ?: return false
        if (op != Operator.SUBTRACT) return false
        if (i == 0) return true
        val prev = tokens[i - 1]
        return prev is SavedToken.Op || prev is SavedToken.OpenParen || prev is SavedToken.Sqrt
    }
}

sealed interface MemoryOpResult {
    data class Success(val entries: List<MemoryEntry>, val activeId: Long?) : MemoryOpResult
    data class Error(val message: String) : MemoryOpResult
    data object Empty : MemoryOpResult
}

/** Pure operations for persisted multi-slot calculator memory (`MS`, `MR`, `M+`, `M−`, `M×`, `M÷`, `MC`). */
internal object MemoryOps {
    const val MAX = 50

    fun ensureUniqueIds(entries: List<MemoryEntry>): List<MemoryEntry> {
        val used = HashSet<Long>(entries.size)
        var nextId = (entries.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        return entries.take(MAX).map { entry ->
            if (entry.id > 0L && used.add(entry.id)) {
                entry
            } else {
                while (!used.add(nextId)) nextId++
                entry.copy(id = nextId++)
            }
        }
    }

    fun resolveActiveId(entries: List<MemoryEntry>, activeId: Long?): Long? =
        if (entries.isEmpty()) null
        else activeId?.takeIf { id -> entries.any { it.id == id } } ?: entries.first().id

    fun activeEntry(entries: List<MemoryEntry>, activeId: Long?): MemoryEntry? =
        resolveActiveId(entries, activeId)?.let { id -> entries.firstOrNull { it.id == id } }

    fun store(
        current: List<MemoryEntry>,
        value: Quantity,
        preferInches: Boolean?,
        label: String = "",
    ): Pair<List<MemoryEntry>, Long> {
        val cleanLabel = label.trim()
        val stored = value.stored()
        val top = current.firstOrNull()
        if (top != null && cleanLabel.isEmpty() && top.label.isEmpty() && top.value == stored) {
            val refreshed = top.copy(preferInches = preferInches ?: top.preferInches)
            val updated = listOf(refreshed) + current.drop(1)
            return updated to refreshed.id
        }
        val nextId = ((current.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)) + 1L
        val created = MemoryEntry(id = nextId, value = stored, preferInches = preferInches, label = cleanLabel)
        val updated = (listOf(created) + current).take(MAX)
        return updated to nextId
    }

    fun applyOperator(
        current: List<MemoryEntry>,
        activeId: Long?,
        targetId: Long?,
        op: Operator,
        operand: Quantity,
        operandPreferInches: Boolean?,
    ): MemoryOpResult {
        val target = targetId?.let { id -> current.firstOrNull { it.id == id } }
            ?: activeEntry(current, activeId)

        if (target == null) {
            return when (op) {
                Operator.ADD -> {
                    val (updated, newId) = store(current, operand, operandPreferInches)
                    MemoryOpResult.Success(updated, newId)
                }
                Operator.SUBTRACT -> {
                    val (updated, newId) = store(current, operand.copy(value = -operand.value), operandPreferInches)
                    MemoryOpResult.Success(updated, newId)
                }
                Operator.MULTIPLY, Operator.DIVIDE -> MemoryOpResult.Empty
            }
        }

        val updatedValue = try {
            Evaluator.apply(op, target.value.toQuantity(), operand)
        } catch (e: CalcException) {
            return MemoryOpResult.Error(e.message ?: "Incompatible units")
        }

        if (updatedValue.value.isZero && target.label.isBlank() && (op == Operator.ADD || op == Operator.SUBTRACT)) {
            val remaining = current.filterNot { it.id == target.id }
            val nextActive = resolveActiveId(remaining, if (activeId == target.id) null else activeId)
            return MemoryOpResult.Success(remaining, nextActive)
        }

        val combinedPreferInches = when {
            target.preferInches == false || operandPreferInches == false -> false
            target.preferInches == true || operandPreferInches == true -> true
            else -> null
        }
        val nextEntry = target.copy(value = updatedValue.stored(), preferInches = combinedPreferInches)
        val nextList = current.map { if (it.id == target.id) nextEntry else it }
        return MemoryOpResult.Success(nextList, target.id)
    }

    fun remove(current: List<MemoryEntry>, activeId: Long?, id: Long): Pair<List<MemoryEntry>, Long?> {
        val next = current.filterNot { it.id == id }
        val nextActive = resolveActiveId(next, if (activeId == id) null else activeId)
        return next to nextActive
    }

    fun setLabel(current: List<MemoryEntry>, id: Long, label: String): List<MemoryEntry> {
        val clean = label.trim()
        return current.map { if (it.id == id) it.copy(label = clean) else it }
    }

    fun merge(current: List<MemoryEntry>, restored: List<MemoryEntry>, replace: Boolean): List<MemoryEntry> {
        val combined = if (replace) {
            restored
        } else {
            (restored + current).distinctBy { Triple(it.value, it.preferInches, it.label) }
        }
        return ensureUniqueIds(combined)
    }
}
