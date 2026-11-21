package app.ao3reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ao3reader.ui.common.AppIcons
import app.ao3reader.ui.common.WorkAction

/**
 * The action row for [vm]'s work. It collects [ReaderViewModel.actions] itself, so an action or state
 * change recomposes only this row, never the chapter text around it.
 */
@Composable
fun ReaderActionRow(vm: ReaderViewModel, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.actions.collectAsStateWithLifecycle()
    ActionRow(
        ui = ui,
        onKudos = vm::kudos,
        onBookmark = vm::openBookmark,
        onLater = vm::toggleLater,
        onSubscribe = vm::toggleSubscribe,
        onComment = vm::openComment,
        onSignIn = onSignIn,
        modifier = modifier,
    )
}

/**
 * Kudos · Bookmark · Later · Subscribe · Comment as compact icon buttons (label on long-press, filled
 * when done). Signed out, one text button to the Account screen instead.
 */
@Composable
fun ActionRow(
    ui: ActionsUi,
    onKudos: () -> Unit,
    onBookmark: () -> Unit,
    onLater: () -> Unit,
    onSubscribe: () -> Unit,
    onComment: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ui.signedIn) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = onSignIn) { Text("Sign in for kudos & bookmarks") }
        }
        return
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        ActionButton(
            label = if (ui.kudosGiven) "Kudos given" else "Kudos",
            icon = if (ui.kudosGiven) AppIcons.Heart else AppIcons.HeartOutline,
            done = ui.kudosGiven,
            running = WorkAction.KUDOS in ui.running,
            // Not a toggle: once given there is nothing to do.
            enabled = WorkAction.KUDOS in ui.available && !ui.kudosGiven,
            onClick = onKudos,
        )
        ActionButton(
            label = if (ui.bookmarked) "Edit bookmark" else "Bookmark",
            icon = if (ui.bookmarked) AppIcons.Bookmark else AppIcons.BookmarkOutline,
            done = ui.bookmarked,
            running = WorkAction.BOOKMARK in ui.running,
            enabled = WorkAction.BOOKMARK in ui.available,
            onClick = onBookmark,
        )
        ActionButton(
            label = if (ui.markedForLater) "Marked for later" else "Mark for later",
            icon = if (ui.markedForLater) AppIcons.Clock else AppIcons.ClockOutline,
            done = ui.markedForLater,
            running = WorkAction.LATER in ui.running,
            enabled = WorkAction.LATER in ui.available,
            onClick = onLater,
        )
        ActionButton(
            label = if (ui.subscribed) "Unsubscribe" else "Subscribe",
            icon = if (ui.subscribed) AppIcons.Bell else AppIcons.BellOutline,
            done = ui.subscribed,
            running = WorkAction.SUBSCRIBE in ui.running,
            enabled = WorkAction.SUBSCRIBE in ui.available,
            onClick = onSubscribe,
        )
        ActionButton(
            label = "Comment",
            icon = AppIcons.CommentOutline,
            done = false,
            stateful = false,
            running = WorkAction.COMMENT in ui.running,
            enabled = WorkAction.COMMENT in ui.available,
            onClick = onComment,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    done: Boolean,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    /** False for Comment, which has no done state to announce. */
    stateful: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        // While its request runs the button is busy (a second tap would only be ignored); the others stay usable.
        IconButton(
            onClick = onClick,
            enabled = enabled && !running,
            modifier = Modifier.semantics { if (stateful) stateDescription = if (done) "Done" else "Not done" },
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "$label, in progress" }, strokeWidth = 2.dp)
            } else {
                // Done shows in the accent colour even when disabled (kudos given).
                Icon(icon, contentDescription = label, tint = if (done) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
        }
    }
}

/**
 * The bookmark and comment sheets and the feedback snackbars, kept out of the reader's own
 * composition: opening a sheet or a message arriving recomposes only this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderActionSheets(vm: ReaderViewModel, snackbar: SnackbarHostState, onAccount: () -> Unit) {
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val ui by vm.actions.collectAsStateWithLifecycle()
    val account by rememberUpdatedState(onAccount)
    LaunchedEffect(vm, snackbar) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, actionLabel = if (m.toAccount) "Account" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) account()
        }
    }
    // The sheet the ViewModel closes (a confirmed save or post) slides away instead of vanishing.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var shown by remember { mutableStateOf<ActionSheet?>(null) }
    LaunchedEffect(sheet) {
        val s = sheet
        if (s != null) {
            shown = s
        } else if (shown != null) {
            sheetState.hide()
            shown = null
        }
    }
    when (val s = shown) {
        is ActionSheet.Bookmark -> BookmarkSheet(
            sheet = s, saving = WorkAction.BOOKMARK in ui.running, sheetState = sheetState,
            onSave = vm::saveBookmark, onDismiss = vm::closeSheet,
        )
        is ActionSheet.Comment -> CommentSheet(
            sheet = s, initialDraft = vm.commentDraft.value, posting = WorkAction.COMMENT in ui.running, sheetState = sheetState,
            onDraft = vm::setCommentDraft, onPost = vm::postComment, onPostAgain = vm::postAgain, onDismiss = vm::closeSheet,
        )
        null -> Unit
    }
}
