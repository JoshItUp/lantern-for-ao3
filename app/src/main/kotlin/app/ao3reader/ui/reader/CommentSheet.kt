package app.ao3reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * A plain-text comment on [sheet]'s chapter. [initialDraft] is the ViewModel's kept draft; every edit
 * goes back through [onDraft], so the text survives closing the sheet or a failed post.
 *
 * A failure shows here (a snackbar would sit under the sheet's scrim). After a post that may have gone
 * through ([ActionSheet.Comment.mustCheck]) Post stays off until the text is edited, or the owner taps
 * "I checked — post again" ([onPostAgain]), so a comment is never sent twice by reflex.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommentSheet(
    sheet: ActionSheet.Comment,
    initialDraft: String,
    posting: Boolean,
    sheetState: SheetState,
    onDraft: (String) -> Unit,
    onPost: () -> Unit,
    onPostAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        var text by rememberSaveable { mutableStateOf(initialDraft) }
        // Scrolls, so Post is never clipped at a large font scale with the keyboard up.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Commenting on Chapter ${sheet.chapterNumber}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onDraft(it) },
                label = { Text("Comment") },
                // Not editable while it posts: the draft is cleared only if it is still the text that was sent.
                readOnly = posting,
                minLines = 4,
                maxLines = 12,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            sheet.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
            // Wraps onto two lines at a large font scale instead of clipping.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (sheet.mustCheck && !posting) {
                    TextButton(onClick = onPostAgain) { Text("I checked — post again") }
                }
                Button(onClick = onPost, enabled = text.isNotBlank() && !posting && !sheet.mustCheck) {
                    if (posting) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "Posting" }, strokeWidth = 2.dp)
                    else Text(if (sheet.failedOnce) "Post again" else "Post")
                }
            }
        }
    }
}
