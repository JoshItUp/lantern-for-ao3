package app.ao3reader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * A plain-text comment on [sheet]'s chapter. [initialDraft] is the ViewModel's kept draft; every edit
 * goes back through [onDraft], so the text survives closing the sheet or a failed post.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    sheet: ActionSheet.Comment,
    initialDraft: String,
    posting: Boolean,
    sheetState: SheetState,
    onDraft: (String) -> Unit,
    onPost: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        var text by rememberSaveable { mutableStateOf(initialDraft) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
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
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPost, enabled = text.isNotBlank() && !posting, modifier = Modifier.align(Alignment.End)) {
                if (posting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Post")
            }
        }
    }
}
