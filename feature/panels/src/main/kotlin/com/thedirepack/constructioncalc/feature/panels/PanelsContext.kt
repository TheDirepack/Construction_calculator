package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.CircuitGroup
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectDesign
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost

/**
 * What every part of the tool needs: the active project, its analysis, and the one way to change it.
 * [edit] saves immediately through `ProjectRepository.edit` and does nothing for archived projects.
 */
internal class PanelsContext(
    val project: Project,
    val design: ProjectDesign,
    val host: ToolHost,
    private val saveEdit: ((Project, Long) -> Project) -> Unit,
) {
    val readOnly: Boolean get() = project.archived

    fun edit(change: (Project, Long) -> Project) {
        if (!readOnly) saveEdit(change)
    }

    fun group(id: String): CircuitGroup? = project.groups.firstOrNull { it.id == id }
}

internal fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"

/** Formats a length the way the user chose (ft-in or in, with their precision). */
@Composable
internal fun rememberLengthFormatter(): (Quantity) -> String {
    val settings = rememberAppSettings()
    return remember(settings) { { q: Quantity -> settings.format(q) } }
}

/** A small tag for a circuit group: its colour with its name beside it in text. */
@Composable
internal fun GroupTag(group: CircuitGroup, modifier: Modifier = Modifier, full: Boolean = false) {
    val color = Color(group.color.takeIf { it != 0 } ?: 0xFF546E7A.toInt())
    val on = if (color.luminance() > 0.45f) Color.Black else Color.White
    Box(modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) {
        Text(
            if (full) group.name else group.name.take(3).uppercase(),
            color = on,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/** A line of warning text with a mark, so it never relies on colour. */
@Composable
internal fun WarningText(text: String, modifier: Modifier = Modifier) {
    Text(
        "⚠ $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}
