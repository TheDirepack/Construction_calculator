package com.thedirepack.constructioncalc.feature.pullplanner

import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
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
) {
    val linked: Boolean get() = projectId != null && runId != null
}

class PullPlannerViewModel : ToolInputsViewModel<PullPlannerInputs>(PullPlannerInputs(), PullPlannerInputs.serializer()) {
    /** Whether the planner has already picked a run for a fresh screen (the active project's run). */
    var autoLinked: Boolean = false
}
