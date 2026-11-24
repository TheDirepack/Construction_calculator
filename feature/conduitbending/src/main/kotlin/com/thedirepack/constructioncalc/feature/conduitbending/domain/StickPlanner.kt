package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Plans an ordered sequence of bends along a single stick of conduit, anchored from End A or
 * relative to the preceding bend. Produces cumulative hook-once-at-End-A tape marks, developed
 * and cut lengths, remaining straight stick, and physical warnings (360° NEC limit, stick
 * overflow requiring a coupling, and bender shoe clearance).
 */
object StickPlanner {

    private val MIN_COMPOUND_GAP_IN = Rational.of(1)

    fun plan(stick: ConduitStick): StickPlan {
        val stickLen = stick.stickLength
        val spec = stick.bender.spec(stick.conduit, stick.conduitSize)
        val radiusIn = spec?.let { BenderDefaults.centerlineRadiusIn(stick.conduit, stick.conduitSize, it) }
            ?: (RacewayBends.minimumRadiusInches(stick.conduit, stick.conduitSize) ?: 4.0)
        val minShoeClearance = spec?.takeUp ?: Rational.of(5)

        val bendResults = mutableListOf<StickBendResult>()
        val allMarks = mutableListOf<StickMark>()
        val allPhysicalBends = mutableListOf<PhysicalBend>()
        val stickIssues = mutableListOf<Issue>()

        var prevEndPipe = Rational.ZERO
        var prevExitRef = Rational.ZERO
        var cumulativeDelta = Rational.ZERO
        var hasValidPrior = false
        var lastExplicitTail = Rational.ZERO

        stick.bends.forEachIndexed { index, item ->
            val rollDeg = normalizeRotationDeg(item.axialRotationDeg)
            val anchor = if (index == 0) DistanceAnchor.FROM_END_A else item.distanceAnchor
            val rawDist = item.distance.rationalOrNull()
            val effectiveAnchorBase: Rational? = when {
                anchor == DistanceAnchor.FROM_PREVIOUS && hasValidPrior ->
                    rawDist?.let { prevExitRef + it }
                anchor == DistanceAnchor.FROM_END_A && rawDist != null ->
                    rawDist + cumulativeDelta
                else -> null
            }

            val placed = placeBendItem(
                item = item,
                index = index,
                stick = stick,
                spec = spec,
                radiusIn = radiusIn,
                anchorOrigin = effectiveAnchorBase,
                rawDistance = rawDist,
                anchor = anchor,
                rollDeg = rollDeg,
                hasValidPrior = hasValidPrior,
                prevEndPipe = prevEndPipe,
                prevExitRef = prevExitRef,
            )

            bendResults += placed.result
            allMarks += placed.result.marks
            allPhysicalBends += placed.physicalBends
            stickIssues += placed.result.issues

            if (placed.result.hasResults) {
                if (hasValidPrior && placed.startPipe < prevEndPipe) {
                    val overlap = prevEndPipe - placed.startPipe
                    stickIssues += Issue(
                        Severity.ERROR,
                        "Bend ${index + 1} starts ${stick.format.dist(overlap)} before Bend ${index} ends. Increase distance or switch to From prev.",
                    )
                }
                prevEndPipe = maxRational(prevEndPipe, placed.endPipe)
                prevExitRef = placed.exitRef
                cumulativeDelta += placed.deltaLen
                hasValidPrior = true
                lastExplicitTail = placed.explicitTrailingTail
            }
        }

        val hasAnyResults = bendResults.any { it.hasResults }
        val maxMarkOrArc = allPhysicalBends.maxOfOrNull { maxRational(it.arcEndIn, it.markPosIn) } ?: Rational.ZERO
        val developedLength = if (hasAnyResults) {
            maxRational(prevEndPipe, maxMarkOrArc)
        } else {
            Rational.ZERO
        }
        val extraTail = stick.tailLength ?: lastExplicitTail
        val cutLength = if (hasAnyResults) {
            maxRational(developedLength, prevEndPipe + (stick.tailLength ?: Rational.ZERO))
        } else {
            Rational.ZERO
        }
        // When an item has explicit trailing tail (back-to-back secondStub) and no stick tail was set,
        // developedLength already includes that trailing stub via prevEndPipe.
        val resolvedCutLength = if (hasAnyResults && stick.tailLength != null) {
            developedLength + stick.tailLength!!
        } else {
            cutLength
        }

        val remainingStraight = if (hasAnyResults) {
            (stickLen - resolvedCutLength).coerceAtLeast(Rational.ZERO)
        } else {
            stickLen
        }
        val overflowAmount = if (hasAnyResults && resolvedCutLength > stickLen) {
            resolvedCutLength - stickLen
        } else {
            Rational.ZERO
        }
        val overflowsStick = overflowAmount.signum > 0

        val totalDegrees = bendResults.sumOf { it.bendDegrees }
        val maxAllowedDegrees = RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS
        val exceeds360 = totalDegrees > maxAllowedDegrees + 1e-6

        if (exceeds360) {
            val section = RacewayBends.section(stick.conduit)
            stickIssues += Issue(
                Severity.WARNING,
                "Total bend degrees (${totalDegrees.fmt()}°) exceed the ${maxAllowedDegrees.toInt()}° NEC limit ($section) — add a pull point or coupling.",
            )
        }

        if (overflowsStick) {
            stickIssues += Issue(
                Severity.WARNING,
                "Cut length (${stick.format.dist(resolvedCutLength)}) exceeds the ${stick.format.dist(stickLen)} stick by ${stick.format.dist(overflowAmount)} — coupling needed.",
            )
        }

        var hasShoeClearanceWarning = false
        for (i in 0 until allPhysicalBends.size - 1) {
            val a = allPhysicalBends[i]
            val b = allPhysicalBends[i + 1]
            val gap = b.arcStartIn - a.arcEndIn
            if (a.bendItemIndex != b.bendItemIndex) {
                if (gap.signum < 0) {
                    hasShoeClearanceWarning = true
                    stickIssues += Issue(
                        Severity.WARNING,
                        "Insufficient bender shoe clearance: Bend ${b.bendItemIndex + 1} overlaps Bend ${a.bendItemIndex + 1} by ${stick.format.dist(-gap)}.",
                    )
                } else if (gap < minShoeClearance) {
                    hasShoeClearanceWarning = true
                    stickIssues += Issue(
                        Severity.WARNING,
                        "Shoe clearance between Bend ${a.bendItemIndex + 1} and Bend ${b.bendItemIndex + 1} is ${stick.format.dist(gap)} (below ${stick.format.dist(minShoeClearance)} minimum for ${stick.format.trade(stick.conduitSize)} ${stick.conduit.displayName}).",
                    )
                }
            } else {
                if (gap.signum < 0) {
                    hasShoeClearanceWarning = true
                    stickIssues += Issue(
                        Severity.WARNING,
                        "Bend ${a.bendItemIndex + 1} marks are too close (${stick.format.dist(b.markPosIn - a.markPosIn)}): bend arcs collide on the ${stick.format.trade(stick.conduitSize)} ${stick.conduit.displayName} shoe.",
                    )
                } else if (gap < MIN_COMPOUND_GAP_IN) {
                    hasShoeClearanceWarning = true
                    stickIssues += Issue(
                        Severity.WARNING,
                        "Tight shoe clearance inside Bend ${a.bendItemIndex + 1} (${stick.format.dist(gap)} straight between arcs).",
                    )
                }
            }
        }

        // Preserve useful single-bend informational notes when there's only 1 bend item, without cluttering multi-bend sticks.
        val finalIssues = stickIssues.distinctBy { it.severity to it.text }

        return StickPlan(
            conduit = stick.conduit,
            conduitSize = stick.conduitSize,
            stickLengthIn = stickLen,
            tailLengthIn = if (extraTail.signum > 0) extraTail else stick.tailLength,
            bendResults = bendResults,
            marks = allMarks,
            physicalBends = allPhysicalBends,
            totalDevelopedLengthIn = developedLength,
            cutLengthIn = resolvedCutLength,
            remainingStraightIn = remainingStraight,
            totalDegrees = totalDegrees,
            exceeds360Degrees = exceeds360,
            overflowsStick = overflowsStick,
            overflowAmountIn = overflowAmount,
            hasShoeClearanceWarning = hasShoeClearanceWarning,
            issues = finalIssues,
            hasResults = hasAnyResults,
            centerlineRadiusIn = radiusIn,
        )
    }

    private data class PlacedBend(
        val result: StickBendResult,
        val physicalBends: List<PhysicalBend>,
        val startPipe: Rational,
        val endPipe: Rational,
        val exitRef: Rational,
        val deltaLen: Rational,
        val explicitTrailingTail: Rational = Rational.ZERO,
    )

    private fun placeBendItem(
        item: StickBendItem,
        index: Int,
        stick: ConduitStick,
        spec: BenderSpec?,
        radiusIn: Double,
        anchorOrigin: Rational?,
        rawDistance: Rational?,
        anchor: DistanceAnchor,
        rollDeg: Double,
        hasValidPrior: Boolean,
        prevEndPipe: Rational,
        prevExitRef: Rational,
    ): PlacedBend {
        val isFirstFromEndA = index == 0 && anchor == DistanceAnchor.FROM_END_A
        val baseReq = item.toRequest(
            conduit = stick.conduit,
            conduitSize = stick.conduitSize,
            table = stick.bender,
            format = stick.format,
            overrideDistance = anchorOrigin,
        )
        val req = if (item.type == BendType.STUB_UP && !isFirstFromEndA && baseReq.size == null) {
            baseReq.copy(size = anchorOrigin ?: rawDistance)
        } else if (item.type == BendType.STUB_UP && isFirstFromEndA && baseReq.size == null && rawDistance != null) {
            baseReq.copy(size = rawDistance)
        } else {
            baseReq
        }
        val basePlan = BendPlanner.plan(req)
        val f = stick.format
        val baseOffset = if (anchor == DistanceAnchor.FROM_PREVIOUS && hasValidPrior) prevExitRef else Rational.ZERO

        if (!basePlan.hasResults) {
            return PlacedBend(
                result = StickBendResult(
                    item = item,
                    index = index,
                    plan = basePlan,
                    marks = emptyList(),
                    startPipeIn = Rational.ZERO,
                    endPipeIn = Rational.ZERO,
                    bendDegrees = 0.0,
                    issues = basePlan.issues,
                ),
                physicalBends = emptyList(),
                startPipe = Rational.ZERO,
                endPipe = Rational.ZERO,
                exitRef = Rational.ZERO,
                deltaLen = Rational.ZERO,
            )
        }

        val prefix = "Bend ${index + 1} (${item.type.label})"
        val num = index + 1

        return when (item.type) {
            BendType.OFFSET, BendType.ROLLING_OFFSET -> {
                val depth = if (item.type == BendType.OFFSET) item.primarySize()!! else {
                    val rise = item.rise.rationalOrNull()!!
                    val roll = item.roll.rationalOrNull()!!
                    BendMath.sqrt(rise * rise + roll * roll)
                }
                val angle = item.angleDegrees()!!
                val o = Bends.offset(depth, angle, anchorOrigin)!!
                val firstMark = o.firstMark ?: baseOffset
                val secondMark = o.secondMark ?: (baseOffset + o.spacing)

                val effectiveRoll = if (item.type == BendType.ROLLING_OFFSET) {
                    val riseD = item.rise.rationalOrNull()!!.toDouble()
                    val rollD = item.roll.rationalOrNull()!!.toDouble()
                    normalizeRotationDeg(rollDeg + Math.toDegrees(atan2(rollD, riseD)))
                } else {
                    rollDeg
                }
                val returnRoll = normalizeRotationDeg(effectiveRoll + 180.0)
                val tOff = tangentOffset(radiusIn, angle)
                val arcLen = arcLength(radiusIn, angle)

                val m1 = StickMark(
                    key = "b${num}_mark_1",
                    bendItemIndex = index,
                    bendItemId = item.id,
                    label = "$num. ${item.type.label} — Mark 1",
                    positionFromEndAIn = firstMark,
                    alignment = BenderAlignment.ARROW,
                    hookDirection = item.hookDirection,
                    bendAngleDeg = angle,
                    axialRotationDeg = effectiveRoll,
                )
                val m2 = StickMark(
                    key = "b${num}_mark_2",
                    bendItemIndex = index,
                    bendItemId = item.id,
                    label = "$num. ${item.type.label} — Mark 2",
                    positionFromEndAIn = secondMark,
                    alignment = BenderAlignment.ARROW,
                    hookDirection = item.hookDirection,
                    bendAngleDeg = angle,
                    axialRotationDeg = returnRoll,
                    note = "Flip conduit 180°",
                )
                val pb1 = PhysicalBend(
                    bendItemIndex = index,
                    bendItemId = item.id,
                    markLabel = "B${num}.1",
                    markPosIn = firstMark,
                    arcStartIn = firstMark - tOff,
                    arcEndIn = firstMark - tOff + arcLen,
                    angleDeg = angle,
                    axialRotationDeg = effectiveRoll,
                    radiusIn = radiusIn,
                    layoutEndIn = firstMark + tOff,
                )
                val pb2 = PhysicalBend(
                    bendItemIndex = index,
                    bendItemId = item.id,
                    markLabel = "B${num}.2",
                    markPosIn = secondMark,
                    arcStartIn = secondMark - tOff,
                    arcEndIn = secondMark - tOff + arcLen,
                    angleDeg = angle,
                    axialRotationDeg = returnRoll,
                    radiusIn = radiusIn,
                    layoutEndIn = secondMark + tOff,
                )
                val issues = filterSingleBendIssues(basePlan.issues, isSingle = stick.bends.size == 1).toMutableList()
                if (firstMark.signum < 0) {
                    issues += Issue(Severity.ERROR, "$prefix first mark falls before End A.")
                }
                val endPipe = secondMark + tOff
                PlacedBend(
                    result = StickBendResult(item, index, basePlan, listOf(m1, m2), firstMark - tOff, endPipe, 2 * angle, issues),
                    physicalBends = listOf(pb1, pb2),
                    startPipe = firstMark - tOff,
                    endPipe = endPipe,
                    exitRef = secondMark,
                    deltaLen = o.shrink,
                )
            }

            BendType.SADDLE_3 -> {
                val height = item.primarySize()!!
                val centerAngle = item.angleDegrees()!!
                val s = Bends.saddle3(height, centerAngle, anchorOrigin)!!
                val nearMark = s.nearMark ?: baseOffset
                val centerMark = s.centerMark ?: (baseOffset + s.outerDistance)
                val farMark = s.farMark ?: (baseOffset + s.outerDistance + s.outerDistance)
                val outerRoll = rollDeg
                val centerRoll = normalizeRotationDeg(rollDeg + 180.0)
                val tOuter = tangentOffset(radiusIn, s.outerAngleDeg)
                val arcOuter = arcLength(radiusIn, s.outerAngleDeg)
                val tCenter = tangentOffset(radiusIn, s.centerAngleDeg)
                val arcCenter = arcLength(radiusIn, s.centerAngleDeg)

                val marks = listOf(
                    StickMark("b${num}_near", index, item.id, "$num. 3-Pt Saddle — Near outer", nearMark, BenderAlignment.ARROW, item.hookDirection, s.outerAngleDeg, outerRoll),
                    StickMark("b${num}_center", index, item.id, "$num. 3-Pt Saddle — Center", centerMark, BenderAlignment.CENTER_NOTCH, item.hookDirection, s.centerAngleDeg, centerRoll, note = "Opposite side"),
                    StickMark("b${num}_far", index, item.id, "$num. 3-Pt Saddle — Far outer", farMark, BenderAlignment.ARROW, item.hookDirection, s.outerAngleDeg, outerRoll),
                )
                val pbs = listOf(
                    PhysicalBend(index, item.id, "B${num}.1", nearMark, nearMark - tOuter, nearMark - tOuter + arcOuter, s.outerAngleDeg, outerRoll, radiusIn, nearMark + tOuter),
                    PhysicalBend(index, item.id, "B${num}.C", centerMark, centerMark - tCenter, centerMark - tCenter + arcCenter, s.centerAngleDeg, centerRoll, radiusIn, centerMark + tCenter),
                    PhysicalBend(index, item.id, "B${num}.3", farMark, farMark - tOuter, farMark - tOuter + arcOuter, s.outerAngleDeg, outerRoll, radiusIn, farMark + tOuter),
                )
                val issues = filterSingleBendIssues(basePlan.issues, isSingle = stick.bends.size == 1).toMutableList()
                if (anchor == DistanceAnchor.FROM_END_A && nearMark.signum < 0 && issues.none { it.severity == Severity.ERROR }) {
                    issues += Issue(Severity.ERROR, "$prefix near outer mark falls ${f.dist(-nearMark)} before End A.")
                }
                val endPipe = farMark + tOuter
                PlacedBend(
                    result = StickBendResult(item, index, basePlan, marks, nearMark - tOuter, endPipe, centerAngle + 2 * s.outerAngleDeg, issues),
                    physicalBends = pbs,
                    startPipe = nearMark - tOuter,
                    endPipe = endPipe,
                    exitRef = farMark,
                    deltaLen = s.idealShrink,
                )
            }

            BendType.SADDLE_4 -> {
                val height = item.primarySize()!!
                val width = item.saddleWidth.rationalOrNull()!!
                val angle = item.angleDegrees()!!
                val s = Bends.saddle4(height, width, angle, anchorOrigin)!!
                val shift = if (anchorOrigin == null) baseOffset else Rational.ZERO
                val shiftedMarks = s.marks.map { it + shift }
                val m1 = shiftedMarks[0]
                val m2 = shiftedMarks[1]
                val m3 = shiftedMarks[2]
                val m4 = shiftedMarks[3]
                val oppositeRoll = normalizeRotationDeg(rollDeg + 180.0)
                val tOff = tangentOffset(radiusIn, angle)
                val arcLen = arcLength(radiusIn, angle)

                val rolls = listOf(rollDeg, oppositeRoll, oppositeRoll, rollDeg)
                val notes = listOf(null, "Flip 180°", "Same as #2", "Flip 180°")
                val marks = shiftedMarks.mapIndexed { i, pos ->
                    StickMark(
                        key = "b${num}_mark_${i + 1}",
                        bendItemIndex = index,
                        bendItemId = item.id,
                        label = "$num. 4-Pt Saddle — Mark ${i + 1}",
                        positionFromEndAIn = pos,
                        alignment = BenderAlignment.ARROW,
                        hookDirection = item.hookDirection,
                        bendAngleDeg = angle,
                        axialRotationDeg = rolls[i],
                        note = notes[i],
                    )
                }
                val pbs = shiftedMarks.mapIndexed { i, pos ->
                    PhysicalBend(index, item.id, "B${num}.${i + 1}", pos, pos - tOff, pos - tOff + arcLen, angle, rolls[i], radiusIn, pos + tOff)
                }
                val issues = filterSingleBendIssues(basePlan.issues, isSingle = stick.bends.size == 1).toMutableList()
                if (m1.signum < 0) issues += Issue(Severity.ERROR, "$prefix Mark 1 falls before End A.")
                val endPipe = m4 + tOff
                PlacedBend(
                    result = StickBendResult(item, index, basePlan, marks, m1 - tOff, endPipe, 4 * angle, issues),
                    physicalBends = pbs,
                    startPipe = m1 - tOff,
                    endPipe = endPipe,
                    exitRef = m4,
                    deltaLen = s.totalShrink,
                )
            }

            BendType.STUB_UP -> {
                val bSpec = spec!!
                val takeUp = bSpec.takeUp
                val gain = bSpec.gain
                val arc90 = takeUp + takeUp - gain

                // For Bend 1 from End A, stubHeight (or distance) is the End A stub height.
                // For subsequent 90s on the stick, distance (if FROM_PREVIOUS or FROM_END_A) positions the back of this 90°.
                val backOfBendPos: Rational = if (isFirstFromEndA) {
                    (item.stubHeight.rationalOrNull() ?: rawDistance)!!
                } else {
                    anchorOrigin ?: (baseOffset + (item.stubHeight.rationalOrNull() ?: takeUp))
                }
                val startTangent = backOfBendPos - takeUp
                val endTangent = startTangent + arc90
                val exitBack = endTangent - takeUp // = backOfBendPos - gain

                val (mark1Pos, align1) = when (item.hookDirection) {
                    HookDirection.TOWARD_END_A -> startTangent to BenderAlignment.ARROW
                    HookDirection.TOWARD_END_B -> backOfBendPos to BenderAlignment.STAR
                }

                val marks = mutableListOf(
                    StickMark(
                        key = "b${num}_stub_1",
                        bendItemIndex = index,
                        bendItemId = item.id,
                        label = "$num. 90° Stub" + if (item.backToBack) " — 1st 90°" else "",
                        positionFromEndAIn = mark1Pos,
                        alignment = align1,
                        hookDirection = item.hookDirection,
                        bendAngleDeg = 90.0,
                        axialRotationDeg = rollDeg,
                    ),
                )
                val pbs = mutableListOf(
                    PhysicalBend(index, item.id, "B${num}.1", mark1Pos, startTangent, endTangent, 90.0, rollDeg, radiusIn),
                )
                val issues = filterSingleBendIssues(basePlan.issues, isSingle = stick.bends.size == 1).toMutableList()
                var totalDeg = 90.0
                var finalStart = startTangent
                var finalEnd = endTangent
                var finalExit = exitBack
                var totalDelta = -gain
                var explicitTail = Rational.ZERO

                if (startTangent.signum < 0) {
                    issues += Issue(Severity.ERROR, "$prefix is too short for the bender's ${f.dist(takeUp)} take-up.")
                }

                if (item.backToBack) {
                    val between = item.between.rationalOrNull()
                    if (between != null && between.signum > 0) {
                        val back2Pos = exitBack + between
                        val start2 = back2Pos - takeUp
                        val end2 = start2 + arc90
                        val exit2 = end2 - takeUp
                        marks += StickMark(
                            key = "b${num}_stub_2",
                            bendItemIndex = index,
                            bendItemId = item.id,
                            label = "$num. 90° Stub — 2nd 90° (B2B)",
                            positionFromEndAIn = back2Pos,
                            alignment = BenderAlignment.STAR,
                            hookDirection = HookDirection.TOWARD_END_B,
                            bendAngleDeg = 90.0,
                            axialRotationDeg = rollDeg,
                            note = "Measured ${f.dist(between)} back-to-back",
                        )
                        pbs += PhysicalBend(index, item.id, "B${num}.2", back2Pos, start2, end2, 90.0, rollDeg, radiusIn)
                        totalDeg += 90.0
                        finalEnd = end2
                        finalExit = exit2
                        totalDelta -= gain

                        val secondStub = item.secondStub.rationalOrNull()
                        if (secondStub != null) {
                            if (secondStub < takeUp) {
                                issues += Issue(Severity.ERROR, "$prefix second stub (${f.dist(secondStub)}) is shorter than take-up (${f.dist(takeUp)}).")
                            } else {
                                val legAfterArc = secondStub - takeUp
                                finalEnd = end2 + legAfterArc
                                explicitTail = legAfterArc
                            }
                        }
                    }
                } else if (!isFirstFromEndA && item.stubHeight.rationalOrNull() != null) {
                    // Optional stub leg after a 90° bend placed mid-stick
                    val second = item.stubHeight.rationalOrNull()!!
                    if (second >= takeUp) {
                        val legAfterArc = second - takeUp
                        finalEnd = endTangent + legAfterArc
                        explicitTail = legAfterArc
                    }
                }

                PlacedBend(
                    result = StickBendResult(item, index, basePlan, marks, finalStart, finalEnd, totalDeg, issues),
                    physicalBends = pbs,
                    startPipe = finalStart,
                    endPipe = finalEnd,
                    exitRef = finalExit,
                    deltaLen = totalDelta,
                    explicitTrailingTail = explicitTail,
                )
            }

            BendType.KICK -> {
                val height = item.primarySize()!!
                val angle = item.angleDegrees()!!
                val k = Bends.kick(height, angle, anchorOrigin)
                if (k == null) {
                    // Distance was shorter than run; compute negative mark for clear error reporting.
                    val kNoDist = Bends.kick(height, angle, null)!!
                    val badMark = (anchorOrigin ?: Rational.ZERO) - kNoDist.run
                    val msg = "$prefix needs ${f.dist(kNoDist.run)} of run before the kick reaches height, which puts the mark at ${f.dist(badMark)}."
                    return PlacedBend(
                        result = StickBendResult(item, index, basePlan, emptyList(), badMark, badMark, 0.0, listOf(Issue(Severity.ERROR, msg))),
                        physicalBends = emptyList(),
                        startPipe = badMark,
                        endPipe = badMark,
                        exitRef = prevEndPipe,
                        deltaLen = Rational.ZERO,
                    )
                }
                val markPos = k.bendMark ?: baseOffset
                val tOff = tangentOffset(radiusIn, angle)
                val arcLen = arcLength(radiusIn, angle)
                val endPipe = markPos + k.travel
                val mark = StickMark(
                    key = "b${num}_kick",
                    bendItemIndex = index,
                    bendItemId = item.id,
                    label = "$num. ${angle.fmt()}° Kick",
                    positionFromEndAIn = markPos,
                    alignment = BenderAlignment.ARROW,
                    hookDirection = item.hookDirection,
                    bendAngleDeg = angle,
                    axialRotationDeg = rollDeg,
                )
                val pb = PhysicalBend(
                    bendItemIndex = index,
                    bendItemId = item.id,
                    markLabel = "B${num}",
                    markPosIn = markPos,
                    arcStartIn = maxRational(Rational.ZERO, markPos - tOff),
                    arcEndIn = maxRational(Rational.ZERO, markPos - tOff) + arcLen,
                    angleDeg = angle,
                    axialRotationDeg = rollDeg,
                    radiusIn = radiusIn,
                    layoutEndIn = markPos + tOff,
                )
                val issues = filterSingleBendIssues(basePlan.issues, isSingle = stick.bends.size == 1).toMutableList()
                if (hasValidPrior && anchor == DistanceAnchor.FROM_PREVIOUS && markPos < prevEndPipe) {
                    issues += Issue(Severity.ERROR, "$prefix mark overlaps Bend ${index} because the kick run (${f.dist(k.run)}) exceeds the distance from previous.")
                }
                PlacedBend(
                    result = StickBendResult(item, index, basePlan, listOf(mark), pb.arcStartIn, endPipe, angle, issues),
                    physicalBends = listOf(pb),
                    startPipe = pb.arcStartIn,
                    endPipe = endPipe,
                    exitRef = endPipe,
                    deltaLen = k.shrink,
                )
            }
        }
    }

    private fun filterSingleBendIssues(issues: List<Issue>, isSingle: Boolean): List<Issue> =
        if (isSingle) issues else issues.filter { it.severity != Severity.INFO }

    private fun tangentOffset(radiusIn: Double, angleDeg: Double): Rational =
        BendMath.tidy(radiusIn * tan(Math.toRadians(angleDeg / 2.0)))

    private fun arcLength(radiusIn: Double, angleDeg: Double): Rational =
        BendMath.tidy(radiusIn * Math.toRadians(angleDeg))

    private fun StoredQuantity?.rationalOrNull(): Rational? =
        this?.let { runCatching { Rational.parse(it.exact) }.getOrNull() }

    private fun maxRational(a: Rational, b: Rational): Rational = if (a >= b) a else b

    private fun Rational.coerceAtLeast(min: Rational): Rational = if (this < min) min else this
}
