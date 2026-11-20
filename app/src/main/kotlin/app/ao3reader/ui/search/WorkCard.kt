package app.ao3reader.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.WorkBlurb
import app.ao3reader.ui.common.BlockText

private val CardShape = RoundedCornerShape(16.dp)
private val TagShape = RoundedCornerShape(6.dp)
private val TagBackground = Color.White.copy(alpha = 0.07f)
private val WarningColor = Color(0xFFE8A27C)

/** What the card's corner badge says: reading progress wins over an AO3 history mark. */
fun badgeText(progressChapter: Int?, readMark: Boolean): String? = when {
    progressChapter != null -> "✓ Ch $progressChapter"
    readMark -> "Read"
    else -> null
}

private fun fmt(n: Int): String = "%,d".format(n)

/** "4/12", "4/?", or null when AO3 gave nothing. */
fun chaptersText(posted: Int?, total: Int?): String? =
    posted?.let { "$it/${total ?: "?"}" }

@Composable
fun WorkCard(
    work: WorkBlurb,
    summary: List<Block>,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val meta = remember(work) {
        buildList {
            work.rating?.let { add(it) }
            if (work.categories.isNotEmpty()) add(work.categories.joinToString(", "))
            work.language?.let { add(it) }
            work.words?.let { add("${fmt(it)} words") }
            chaptersText(work.chaptersPosted, work.chaptersTotal)?.let { add("Ch $it") }
            when (work.complete) {
                true -> add("Complete")
                false -> add("In progress")
                null -> {}
            }
        }.joinToString("  ·  ")
    }
    val stats = remember(work) {
        buildList {
            work.kudos?.let { add("♥ ${fmt(it)}") }
            work.hits?.let { add("${fmt(it)} hits") }
            work.bookmarks?.let { add("${fmt(it)} bookmarks") }
            work.comments?.let { add("${fmt(it)} comments") }
            work.updated?.let { add(it) }
        }.joinToString("  ·  ")
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CardShape,
        modifier = modifier.fillMaxWidth().clip(CardShape).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(work.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (work.authors.isNotEmpty()) {
                        Text("by " + work.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                if (badge != null) Badge(badge, Modifier.padding(start = 8.dp))
            }
            if (work.fandoms.isNotEmpty()) {
                Text(
                    work.fandoms.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = muted)
            if (work.warnings.isNotEmpty()) {
                Text("⚠ " + work.warnings.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = WarningColor)
            }
            if (work.relationships.isNotEmpty()) {
                Tags(work.relationships, MaterialTheme.colorScheme.onSurface, emphasized = true)
            }
            if (work.characters.isNotEmpty() || work.freeforms.isNotEmpty()) {
                Tags(work.characters + work.freeforms, muted, emphasized = false)
            }
            if (summary.isNotEmpty()) {
                BlockText(
                    summary, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f), blockSpacing = 6.dp,
                )
            }
            if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun Tags(tags: List<String>, color: Color, emphasized: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in tags) {
            Text(
                t,
                color = color,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.background(TagBackground, TagShape).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
