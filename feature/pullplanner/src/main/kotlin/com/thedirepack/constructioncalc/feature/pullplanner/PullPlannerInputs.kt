package com.thedirepack.constructioncalc.feature.pullplanner

import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullPlannerMode
import com.thedirepack.constructioncalc.feature.pullplanner.domain.STANDALONE_RACEWAY
import kotlinx.serialization.Serializable

/**
 * What a saved pull stores in `ProjectItem.inputs`.
 *
 * When [runId] names a run of [projectId], the planner works on that run: its path and pull settings
 * live on the run (shared with Bending and Panels), and [raceway], [path] and [pull] are the
 * snapshot taken when the item was saved. With no run, the planner works standalone on these.
 */
@Serializable
data class PullPlannerInputs(
    val projectId: String? = null,
    val runId: String? = null,
    val raceway: Raceway = STANDALONE_RACEWAY,
    val path: List<PathSegment> = emptyList(),
    val pull: PullSetup? = null,
    val mode: PullPlannerMode? = null,
    val quickLength: StoredQuantity? = null,
    val quickBends90: Int = 0,
    val quickBends45: Int = 0,
    val quickExtraBendDegrees: Double = 0.0,
) {
    init {
        require(quickLength == null || (quickLength.dimension == Dimension.LENGTH && quickLength.toQuantity().value.signum >= 0)) {
            "Invalid quick pull length"
        }
        require(quickBends90 in 0..MAX_QUICK_BENDS) { "Invalid 90° bend count: $quickBends90" }
        require(quickBends45 in 0..MAX_QUICK_BENDS) { "Invalid 45° bend count: $quickBends45" }
        require(quickExtraBendDegrees.isFinite() && quickExtraBendDegrees in 0.0..3600.0) {
            "Invalid extra bend degrees: $quickExtraBendDegrees"
        }
    }

    val linked: Boolean get() = projectId != null && runId != null

    /** Uses [mode] if explicitly picked, otherwise Detailed Route when [hasDetailedPath] is true and Quick Check otherwise. */
    fun resolvedMode(hasDetailedPath: Boolean = path.isNotEmpty()): PullPlannerMode =
        mode ?: if (hasDetailedPath) PullPlannerMode.DETAILED_ROUTE else PullPlannerMode.QUICK_CHECK

    companion object {
        const val MAX_QUICK_BENDS = 200
    }
}

class PullPlannerViewModel : ToolInputsViewModel<PullPlannerInputs>(PullPlannerInputs(), PullPlannerInputs.serializer()) {
    /** Whether the planner has already picked a run for a fresh screen (the active project's run). */
    var autoLinked: Boolean = false
}
