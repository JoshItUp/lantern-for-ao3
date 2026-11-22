package io.github.joshitup.lantern.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.joshitup.lantern.ui.common.BlockText
import io.github.joshitup.lantern.update.ReleaseInfo
import io.github.joshitup.lantern.update.ReleaseNotes
import io.github.joshitup.lantern.update.UpdateState

/** The newer release: its notes and size, then Download, the progress, the permission ask or a failure with Retry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    release: ReleaseInfo,
    install: UpdateState,
    onLater: () -> Unit,
    onDownload: () -> Unit,
    onAllowInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Lantern ${release.version}", style = MaterialTheme.typography.titleLarge)
            Text(
                formatSize(release.apkSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReleaseNotesBody(release)
            Spacer(Modifier.height(20.dp))
            InstallStatus(install, onLater, onDownload, onAllowInstall)
        }
    }
}

/** "What's new" and the rendered notes; nothing when the release has no body. */
@Composable
internal fun ReleaseNotesBody(release: ReleaseInfo, heading: String? = "What's new") {
    val blocks = remember(release.notes) { ReleaseNotes.toBlocks(release.notes) }
    if (blocks.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    if (heading != null) {
        Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
    }
    BlockText(blocks, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InstallStatus(install: UpdateState, onLater: () -> Unit, onDownload: () -> Unit, onAllowInstall: () -> Unit) {
    when (install) {
        is UpdateState.Downloading -> Progress("Downloading…", install.progress)
        UpdateState.Installing, is UpdateState.AwaitingConfirmation, UpdateState.Done -> Progress("Installing…", null)
        UpdateState.NeedsPermission -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAllowInstall, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Allow Lantern to install updates")
            }
            OutlinedButton(onClick = onLater, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Later")
            }
        }
        is UpdateState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(install.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Buttons(onLater, onDownload, primary = "Retry")
        }
        UpdateState.Idle -> Buttons(onLater, onDownload, primary = "Download")
    }
}

@Composable
private fun Buttons(onLater: () -> Unit, onPrimary: () -> Unit, primary: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onLater, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
            Text("Later")
        }
        Button(onClick = onPrimary, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
            Text(primary)
        }
    }
}

/** [progress] null is indeterminate. */
@Composable
private fun Progress(label: String, progress: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
