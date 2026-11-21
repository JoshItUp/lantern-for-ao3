package app.ao3reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.ao3reader.ao3.parse.BookmarkInput
import app.ao3reader.ui.common.AppIcons

/**
 * Splits typed tag text at commas: the finished tags (trimmed, non-blank) and what is still being typed.
 * With [commit] the remainder is finished too (Enter, or Save).
 */
internal fun splitTags(text: String, commit: Boolean = false): Pair<List<String>, String> {
    val parts = text.split(',')
    val done = if (commit) parts else parts.dropLast(1)
    val rest = if (commit) "" else parts.last()
    return done.map { it.trim() }.filter { it.isNotEmpty() } to rest
}

/** Adds [new] to [tags], skipping ones already there (AO3 tags are case-insensitive). */
internal fun addTags(tags: List<String>, new: List<String>): List<String> {
    val out = tags.toMutableList()
    for (t in new) if (out.none { it.equals(t, ignoreCase = true) }) out += t
    return out
}

/**
 * Create or edit the bookmark: notes, tags (comma or Enter makes a chip), Private, Rec.
 * The fields keep what was typed until AO3 confirms the save ([ReaderViewModel.saveBookmark] closes it).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookmarkSheet(
    sheet: ActionSheet.Bookmark,
    saving: Boolean,
    sheetState: SheetState,
    onSave: (BookmarkInput) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Re-seeded once AO3's bookmark has been read (loading -> loaded).
        val seed = sheet.initial
        var notes by rememberSaveable(sheet.loading) { mutableStateOf(seed.notes) }
        var tags by rememberSaveable(sheet.loading) { mutableStateOf(seed.tags) }
        var tagText by rememberSaveable(sheet.loading) { mutableStateOf("") }
        var isPrivate by rememberSaveable(sheet.loading) { mutableStateOf(seed.isPrivate) }
        var isRec by rememberSaveable(sheet.loading) { mutableStateOf(seed.isRec) }
        val editable = !sheet.loading && !saving

        fun commitTags() {
            val (done, _) = splitTags(tagText, commit = true)
            tags = addTags(tags, done)
            tagText = ""
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(if (sheet.existing) "Edit bookmark" else "Bookmark", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (sheet.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                enabled = editable,
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in tags) {
                        InputChip(
                            selected = false,
                            enabled = editable,
                            onClick = { tags = tags - t },
                            label = { Text(t) },
                            trailingIcon = { Icon(AppIcons.Close, contentDescription = "Remove $t", Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = tagText,
                onValueChange = { v ->
                    val (done, rest) = splitTags(v)
                    if (done.isNotEmpty()) tags = addTags(tags, done)
                    tagText = rest
                },
                label = { Text("Tags") },
                placeholder = { Text("Comma or Enter adds a tag") },
                enabled = editable,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitTags() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow("Private", isPrivate, editable) { isPrivate = it }
            SwitchRow("Rec", isRec, editable) { isRec = it }
            sheet.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            // A failed save shows here: a snackbar would sit under the sheet's scrim.
            sheet.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    commitTags()
                    onSave(BookmarkInput(notes, tags, isPrivate, isRec))
                },
                enabled = editable,
                modifier = Modifier.align(Alignment.End),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "Saving" }, strokeWidth = 2.dp)
                else Text("Save")
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
