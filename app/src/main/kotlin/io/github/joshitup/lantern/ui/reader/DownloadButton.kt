package io.github.joshitup.lantern.ui.reader

import androidx.compose.foundation.layout.Column
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
import io.github.joshitup.lantern.data.db.DownloadEntity
import io.github.joshitup.lantern.data.db.DownloadJob
import io.github.joshitup.lantern.ui.common.AppIcons

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
 * What the Download control shows for this work's row. [enabled] is false while queued or downloading
 * (a tap would be ignored) and once the copy is current ([done]). [failure]: the reason a FAILED job
 * gave. [newLine]: "N new chapters · Update", only when [showNewChapters] and nothing else is shown.
 */
data class DownloadUi(val label: String, val enabled: Boolean, val done: Boolean, val failure: String?, val newLine: String?)

fun downloadUi(d: DownloadEntity?, showNewChapters: Boolean): DownloadUi {
    val label = downloadLabel(d)
    val busy = d?.job == DownloadJob.QUEUED || d?.job == DownloadJob.DOWNLOADING
    val failure = if (d?.job == DownloadJob.FAILED) d.failure?.takeIf { it.isNotBlank() } else null
    val newLine = if (showNewChapters && failure == null && !busy) newChaptersLine(d) else null
    val done = label == DOWNLOADED_LABEL
    return DownloadUi(label, enabled = !busy && !done, done = done, failure = failure, newLine = newLine)
}

/**
 * The chapter footer's Download button (visible signed out too), with the failure reason under it when
 * the job FAILED, else, when [showNewChapters], the tappable new-chapters line. [onDownload] runs on a tap.
 * Collects [ReaderViewModel.download] itself, so a download change never recomposes the chapter text.
 */
@Composable
fun DownloadSlot(vm: ReaderViewModel, showNewChapters: Boolean, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val d by vm.download.collectAsStateWithLifecycle()
    val ui = downloadUi(d, showNewChapters)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = onDownload,
            enabled = ui.enabled,
            colors = ButtonDefaults.textButtonColors(
                disabledContentColor = if (ui.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(ui.label, modifier = Modifier.padding(start = 6.dp))
        }
        when {
            ui.failure != null -> Text(
                ui.failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            ui.newLine != null -> TextButton(onClick = onDownload) {
                Text(ui.newLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
