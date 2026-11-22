package io.github.joshitup.lantern.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val RowShape = RoundedCornerShape(14.dp)
private val KindShape = RoundedCornerShape(6.dp)
private val KindBackground = Color.White.copy(alpha = 0.07f)

/**
 * A compact subscription: "title / by author" for a work, "title [Series|Author]" for the others.
 * [onClick] null: not tappable (a link that isn't AO3).
 */
@Composable
fun SubscriptionRow(row: SubRow, badge: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isWork = row.workId != null
    Row(
        modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isWork && row.detail != null) {
                Text(
                    "by ${row.detail}", style = MaterialTheme.typography.bodySmall, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val label = if (isWork) badge else row.detail
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isWork) MaterialTheme.colorScheme.onPrimaryContainer else muted,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .background(if (isWork) MaterialTheme.colorScheme.primaryContainer else KindBackground, KindShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
