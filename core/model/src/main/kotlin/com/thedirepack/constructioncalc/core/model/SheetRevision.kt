package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SheetRevision(val sheetId: String, val revision: String, val issueOrder: Int) {
    init {
        require(sheetId.isNotBlank() && sheetId.length <= 80 && sheetId.none(Char::isISOControl))
        require(revision.isNotBlank() && revision.length <= 40 && revision.none(Char::isISOControl))
        require(issueOrder > 0)
    }
}

/** A snapshot of the source when a result leaves the editor. PDF bytes and page define its origin. */
@Serializable
data class PlanSheetSource(
    val drawingId: String,
    val sha256: String,
    val page: Int,
    val sheetId: String,
    val revision: String? = null,
) {
    val label: String get() = listOfNotNull(sheetId, revision?.let { "Rev $it" }).joinToString(" · ")
}

fun PlanDrawing.sheetSource(page: Int, fallbackSheetId: String = "Page ${page + 1}"): PlanSheetSource {
    val identity = sheetRevisions[page]
    return PlanSheetSource(id, sha256, page, identity?.sheetId ?: fallbackSheetId, identity?.revision)
}

data class IssuedSheet(val drawing: PlanDrawing, val page: Int, val identity: SheetRevision) {
    val source: PlanSheetSource get() = drawing.sheetSource(page)
}

enum class SheetRevisionStatus(val label: String) {
    LATEST("Latest"), SUPERSEDED("Superseded"), ISSUE_ORDER_TIED("Issue order tied"),
}

fun Project.issuedSheets(sheetId: String): List<IssuedSheet> = drawings.flatMap { drawing ->
    drawing.sheetRevisions.mapNotNull { (page, identity) ->
        if (identity.sheetId.trim().equals(sheetId.trim(), ignoreCase = true)) {
            IssuedSheet(drawing, page, identity)
        } else null
    }
}.sortedByDescending { it.identity.issueOrder }

fun Project.sheetRevisionStatus(drawing: PlanDrawing, page: Int): SheetRevisionStatus? {
    val identity = drawing.sheetRevisions[page] ?: return null
    val issued = issuedSheets(identity.sheetId)
    val latest = issued.firstOrNull()?.identity?.issueOrder ?: return null
    return when {
        identity.issueOrder < latest -> SheetRevisionStatus.SUPERSEDED
        issued.count { it.identity.issueOrder == latest } > 1 -> SheetRevisionStatus.ISSUE_ORDER_TIED
        else -> SheetRevisionStatus.LATEST
    }
}
