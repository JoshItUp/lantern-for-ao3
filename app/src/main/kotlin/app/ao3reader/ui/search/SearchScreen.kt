package app.ao3reader.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ao3reader.ao3.model.SearchQuery
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.db.PresetEntity
import app.ao3reader.data.db.ProgressEntity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Opens the reader. chapterId null + number 1 means "start of the work". */
typealias OpenWork = (workId: Long, chapterId: Long?, number: Int) -> Unit

/** Items from the end of the list at which the next page is requested. */
private const val PREFETCH_DISTANCE = 5

fun errorMessage(e: FetchResult): String = when (e) {
    FetchResult.Blocked -> "AO3 is blocking requests right now."
    is FetchResult.RateLimited -> "AO3 asked us to slow down. Try again in ${e.retryAfterSec} s."
    else -> "Couldn't load results."
}

/** "Title — Ch 4/12" (or "Ch 4/?" when the total is unknown). */
fun continueLabel(p: ProgressEntity): String = "${p.workTitle} — Ch ${p.chapterNumber}/${p.chaptersTotal ?: "?"}"

/** Preset dialog modes: a new preset from the current query, or renaming an existing one. */
private sealed interface NameDialog {
    /**
     * [pending]: filters from the sheet, applied (and searched) only when the name is confirmed;
     * cancelling leaves the current query untouched. Null saves the current query as is.
     */
    data class New(val pending: SearchQuery? = null) : NameDialog
    data class Rename(val preset: PresetEntity) : NameDialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: SearchViewModel, onOpenWork: OpenWork) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }

    // Local text keeps typing smooth; it follows the query when a preset or the sheet changes it.
    var text by rememberSaveable { mutableStateOf(state.query.anyField) }
    LaunchedEffect(state.query.anyField) { if (text != state.query.anyField) text = state.query.anyField }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 1 - PREFETCH_DISTANCE
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd && state.hasNext && !state.loading && state.error == null && state.works.isNotEmpty() }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.loadNextPage() }
    }

    fun search() {
        focus.clearFocus()
        vm.runSearch()
    }

    // Pad for the bars even while the reader has them hidden, so this screen never shifts when
    // they come back during the pop transition.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBarsIgnoringVisibility,
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = inner.calculateTopPadding() + 12.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.continueReading?.let { p ->
                item(key = "continue", contentType = "continue") {
                    ContinueStrip(p) { onOpenWork(p.workId, p.chapterId, p.chapterNumber) }
                }
            }
            item(key = "presets", contentType = "presets") {
                PresetRow(
                    presets = state.presets,
                    activeId = state.activePresetId,
                    onApply = { focus.clearFocus(); vm.applyPreset(it) },
                    onNew = { nameDialog = NameDialog.New() },
                    onRename = { nameDialog = NameDialog.Rename(it) },
                    onUpdate = vm::updatePresetFromCurrent,
                    onDelete = vm::deletePreset,
                    onReorder = vm::reorderPresets,
                )
            }
            item(key = "search", contentType = "search") {
                SearchBar(
                    text = text,
                    filterCount = activeFilterCount(state.query),
                    onText = { text = it; vm.setQuery(state.query.copy(anyField = it)) },
                    onSearch = ::search,
                    onFilters = { focus.clearFocus(); showFilters = true },
                )
            }
            if (state.works.isEmpty()) {
                item(key = "status", contentType = "status") {
                    when {
                        state.error != null -> ErrorRow(state.error!!, vm::retry)
                        state.loading -> Loading()
                        state.searched -> Hint("No works found.")
                        else -> Hint("Search, or tap a saved preset.")
                    }
                }
            }
            items(state.works, key = { it.workId }, contentType = { "work" }) { w ->
                val progress = state.progressByWork[w.workId]
                WorkCard(
                    work = w,
                    summary = state.summaries[w.workId].orEmpty(),
                    badge = badgeText(progress?.chapterNumber, w.workId in state.readMarks),
                    onClick = { onOpenWork(w.workId, progress?.chapterId, progress?.chapterNumber ?: 1) },
                )
            }
            if (state.works.isNotEmpty() && (state.loading || state.error != null)) {
                item(key = "footer", contentType = "status") {
                    if (state.error != null) ErrorRow(state.error!!, vm::retry) else Loading()
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            initial = state.query,
            autocomplete = vm::autocomplete,
            onApply = { q ->
                showFilters = false
                vm.setQuery(q)
                vm.runSearch()
            },
            onSaveAsPreset = { q ->
                showFilters = false
                nameDialog = NameDialog.New(pending = q)
            },
            onDismiss = { showFilters = false },
        )
    }

    nameDialog?.let { d ->
        PresetNameDialog(
            title = if (d is NameDialog.Rename) "Rename preset" else "Save preset",
            initial = (d as? NameDialog.Rename)?.preset?.name ?: "",
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                nameDialog = null
                when (d) {
                    is NameDialog.Rename -> vm.renamePreset(d.preset.id, name)
                    is NameDialog.New -> {
                        d.pending?.let(vm::setQuery)
                        vm.savePreset(name)
                        if (d.pending != null) vm.runSearch()
                    }
                }
            },
        )
    }
}

@Composable
private fun ContinueStrip(p: ProgressEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "▶  Continue",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            continueLabel(p),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(
    presets: List<PresetEntity>,
    activeId: Long?,
    onApply: (Long) -> Unit,
    onNew: () -> Unit,
    onRename: (PresetEntity) -> Unit,
    onUpdate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEachIndexed { i, p ->
            var menu by remember(p.id) { mutableStateOf(false) }
            val active = p.id == activeId
            Box {
                Text(
                    p.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(
                            if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(10.dp),
                        )
                        .combinedClickable(onClick = { onApply(p.id) }, onLongClick = { menu = true })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename(p) })
                    DropdownMenuItem(text = { Text("Update with current filters") }, onClick = { menu = false; onUpdate(p.id) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(p.id) })
                    DropdownMenuItem(
                        text = { Text("Move left") }, enabled = i > 0,
                        onClick = { menu = false; onReorder(presets.map { it.id }.swap(i, i - 1)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Move right") }, enabled = i < presets.lastIndex,
                        onClick = { menu = false; onReorder(presets.map { it.id }.swap(i, i + 1)) },
                    )
                }
            }
        }
        Text(
            "+ Save",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onNew)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> = toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

@Composable
private fun SearchBar(
    text: String,
    filterCount: Int,
    onText: (String) -> Unit,
    onSearch: () -> Unit,
    onFilters: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            placeholder = { Text("Search works") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onFilters, shape = RoundedCornerShape(14.dp)) {
            Text(if (filterCount > 0) "Filters · $filterCount" else "Filters")
        }
    }
}

@Composable
private fun ErrorRow(error: FetchResult, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(errorMessage(error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}

@Composable
private fun PresetNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Inside the slot, so it runs once the field (and its focusRequester) is attached.
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
