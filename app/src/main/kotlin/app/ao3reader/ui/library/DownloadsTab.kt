package app.ao3reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.data.db.ProgressEntity
import app.ao3reader.ui.common.Hint
import app.ao3reader.ui.search.WorkCard
import app.ao3reader.ui.search.badgeText
import app.ao3reader.ui.theme.WarningColor

private val RowShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(6.dp)

const val DOWNLOADS_EMPTY = "Download works from the reader to read them offline."

private fun DownloadJob.runningText(): String? = when (this) {
    DownloadJob.QUEUED -> "Queued…"
    DownloadJob.DOWNLOADING -> "Downloading…"
    else -> null
}

/**
 * The Library's Downloads tab: saved works, readable offline and signed out. A row with a copy opens the
 * reader ([onOpenWork]); one without (first download queued or failed) shows its title and state only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsTab(
    s: DownloadsUiState,
    listState: LazyListState,
    progressByWork: Map<Long, ProgressEntity>,
    readMarks: Set<Long>,
    bottomBarHeight: Dp,
    onOpenWork: (Long) -> Unit,
    onUpdate: (Long) -> Unit,
    onAskDelete: (Long) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = bottomInset + bottomBarHeight + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Always first (anchors the top, as in the other tabs); holds the total once anything is stored.
        item(key = "top", contentType = "top") {
            if (s.copies > 0) {
                Text(
                    totalLine(s.copies, s.totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
        }
        if (s.loaded && s.rows.isEmpty()) {
            item(key = "status", contentType = "status") { Hint(DOWNLOADS_EMPTY) }
        }
        items(s.rows, key = { it.entity.workId }, contentType = { if (it.blurb != null) "work" else "pending" }) { row ->
            val id = row.entity.workId
            var menu by remember(id) { mutableStateOf(false) }
            val confirming = s.confirmingDelete == id
            val blurb = row.blurb
            if (blurb != null) {
                val p = progressByWork[id]
                WorkCard(
                    work = blurb,
                    summary = row.summary,
                    badge = badgeText(p?.chapterNumber, id in readMarks, downloaded = false),
                    onClick = { if (row.openable) onOpenWork(id) },
                    onLongClick = { if (!row.busy) menu = true },
                    extras = {
                        DownloadExtras(
                            row, menu, confirming,
                            onMenu = { menu = it },
                            onUpdate = { onUpdate(id) }, onAskDelete = { onAskDelete(id) },
                            onConfirmDelete = onConfirmDelete, onCancelDelete = onCancelDelete,
                        )
                    },
                )
            } else {
                PendingRow(
                    row, menu, confirming, { menu = it }, { onUpdate(id) }, { onAskDelete(id) }, onConfirmDelete, onCancelDelete,
                    onOpen = if (row.openable) ({ onOpenWork(id) }) else null,
                )
            }
        }
    }
}

/** Chapters (and "N new"), size, date; the failure reason; the ⋯ menu or the running job's state. */
@Composable
private fun ColumnScope.DownloadExtras(
    row: DownloadRow,
    menu: Boolean,
    confirming: Boolean,
    onMenu: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onAskDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StoredDetails(row.entity)
            Failure(row)
        }
        Actions(row, menu, onMenu, onUpdate, onAskDelete)
    }
    if (confirming) ConfirmDelete(onConfirmDelete, onCancelDelete)
}

/** Chapters stored (with the "N new" chip when known), then size · date. Nothing for a row without a copy. */
@Composable
private fun StoredDetails(e: DownloadEntity) {
    val d = remember(e.hasCopy, e.chaptersStored, e.chaptersPostedRemote, e.sizeBytes, e.downloadedAt) { copyDetails(e) } ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(d.chapters, style = MaterialTheme.typography.labelMedium, color = muted)
        d.newChapters?.let { n ->
            Text(" · ", style = MaterialTheme.typography.labelMedium, color = muted)
            Text(
                "$n new",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, ChipShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    Text(d.sizeAndDate, style = MaterialTheme.typography.labelMedium, color = muted)
}

/**
 * A row with no copy yet (or a copy whose blurb can't be read): title, authors and state, plus the stored
 * chapters, size and date when there is a copy. Tappable only with a copy ([onOpen]).
 */
@Composable
private fun PendingRow(
    row: DownloadRow,
    menu: Boolean,
    confirming: Boolean,
    onMenu: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onAskDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val e = row.entity
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (e.authors.isNotBlank()) {
                    Text(
                        "by ${e.authors}", style = MaterialTheme.typography.bodySmall, color = muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                StoredDetails(e)
                Failure(row)
            }
            Actions(row, menu, onMenu, onUpdate, onAskDelete)
        }
        if (confirming) ConfirmDelete(onConfirmDelete, onCancelDelete)
    }
}

@Composable
private fun Failure(row: DownloadRow) {
    val reason = row.entity.failure?.takeIf { row.entity.job == DownloadJob.FAILED } ?: return
    Text(reason, style = MaterialTheme.typography.labelMedium, color = WarningColor)
}

/** While a job runs only its state shows (no Update or Delete); otherwise the ⋯ menu. */
@Composable
private fun Actions(row: DownloadRow, menu: Boolean, onMenu: (Boolean) -> Unit, onUpdate: () -> Unit, onAskDelete: () -> Unit) {
    val running = row.entity.job.runningText()
    if (running != null) {
        Text(
            running, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        )
        return
    }
    Box {
        TextButton(onClick = { onMenu(true) }) {
            Text("⋯", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
            DropdownMenuItem(
                text = { Text(if (row.entity.job == DownloadJob.FAILED) "Retry" else "Update") },
                onClick = { onMenu(false); onUpdate() },
            )
            DropdownMenuItem(text = { Text("Delete") }, onClick = { onMenu(false); onAskDelete() })
        }
    }
}

/** Inline, no dialog: "Delete download? · Delete · Cancel". */
@Composable
private fun ConfirmDelete(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Delete download?", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
