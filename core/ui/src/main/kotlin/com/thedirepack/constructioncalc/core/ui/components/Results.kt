package com.thedirepack.constructioncalc.core.ui.components

import com.thedirepack.constructioncalc.core.ui.spokenText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.ui.fractionText

/** How serious a [Notice] is. */
enum class NoticeLevel { INFO, WARNING, ERROR }

/** A message shown with results: an assumption, a code limit exceeded, a missing input… */
data class Notice(val text: String, val level: NoticeLevel = NoticeLevel.INFO)

/**
 * Standard results block: the first result large, the rest as label/value rows, then notices.
 * Tap any value to copy it.
 */
@Composable
fun ResultCard(
    results: List<ResultValue>,
    modifier: Modifier = Modifier,
    notices: List<Notice> = emptyList(),
    emptyText: String = "Fill in the inputs to see results.",
) {
    val context = LocalContext.current
    val copy: (ResultValue) -> Unit = { value ->
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(value.label, MeasureFormat.forClipboard(value.text)))
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (results.isEmpty()) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            results.firstOrNull()?.let { primary ->
                Column(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Copy") { copy(primary) }
                        .semantics(mergeDescendants = true) { contentDescription = "${primary.label}: ${spokenText(primary.text)}" },
                ) {
                    Text(primary.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        fractionText(primary.text),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            results.drop(1).forEach { value ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Copy") { copy(value) }.padding(vertical = 2.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "${value.label}: ${spokenText(value.text)}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        fractionText(value.text),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }
            }
            notices.forEach { notice ->
                Text(
                    (if (notice.level == NoticeLevel.INFO) "" else "⚠ ") + notice.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (notice.level) {
                        NoticeLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        NoticeLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                        NoticeLevel.ERROR -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/** A titled group of inputs inside a tool form. */
@Composable
fun FormSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
