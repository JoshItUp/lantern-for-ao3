package app.ao3reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ao3reader.data.db.DownloadEntity
import app.ao3reader.data.db.DownloadJob
import app.ao3reader.ui.common.AppIcons

internal const val DOWNLOADED_LABEL = "Downloaded ✓"

/** The Download button's text for this work's download row (null: never downloaded). */
fun downloadLabel(d: DownloadEntity?): String = when {
    d == null -> "Download"
    d.job == DownloadJob.QUEUED -> "Queued…"
    d.job == DownloadJob.DOWNLOADING -> "Downloading…"
    d.job == DownloadJob.FAILED -> "Retry"
    !d.hasCopy -> "Download"
    d.newChapters() > 0 -> "Update (${d.newChapters()} new)"
    else -> DOWNLOADED_LABEL
}

/** "N new chapters · Update" for a copy AO3 has outgrown; null when there is nothing new. */
fun newChaptersLine(d: DownloadEntity?): String? {
    val n = d?.newChapters() ?: return null
    return if (n > 0) "$n new chapter${if (n == 1) "" else "s"} · Update" else null
}

private fun DownloadEntity.newChapters(): Int {
    val remote = chaptersPostedRemote ?: return 0
    return if (hasCopy) remote - chaptersStored else 0
}

/**
 * The Download button in its own slot (visible signed out too), with a line under or beside it:
 * the failure reason when the job FAILED, else, when [showNewChapters], the tappable new-chapters line.
 * [inBar]: one compact row for the controls bar; otherwise a centred column for the chapter footer.
 * Collects [ReaderViewModel.download] itself, so a download change never recomposes the chapter text.
 */
@Composable
fun DownloadSlot(vm: ReaderViewModel, showNewChapters: Boolean, inBar: Boolean, modifier: Modifier = Modifier) {
    val d by vm.download.collectAsStateWithLifecycle()
    val label = downloadLabel(d)
    val busy = d?.job == DownloadJob.QUEUED || d?.job == DownloadJob.DOWNLOADING
    val failure = if (d?.job == DownloadJob.FAILED) d?.failure?.takeIf { it.isNotBlank() } else null
    val newLine = if (showNewChapters && failure == null && !busy) newChaptersLine(d) else null
    // Busy and done states are inert: a tap would be ignored (request is a no-op while queued; a current copy needs nothing).
    val done = label == DOWNLOADED_LABEL

    val button: @Composable () -> Unit = {
        TextButton(
            onClick = vm::onDownload,
            enabled = !busy && !done,
            colors = ButtonDefaults.textButtonColors(
                disabledContentColor = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, modifier = Modifier.padding(start = 6.dp))
        }
    }
    val line: @Composable (Modifier) -> Unit = { m ->
        when {
            failure != null -> Text(
                failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = if (inBar) 1 else 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = m,
            )
            newLine != null -> TextButton(onClick = vm::onDownload, modifier = m) {
                Text(newLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (inBar) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            button()
            line(Modifier.weight(1f, fill = false))
        }
    } else {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            button()
            line(Modifier)
        }
    }
}
