package app.ao3reader.ui.worklist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ui.common.AppIcons
import app.ao3reader.ui.common.OpenWorkList
import app.ao3reader.ui.search.OpenWork
import app.ao3reader.ui.search.WorkCard
import app.ao3reader.ui.search.badgeText
import app.ao3reader.ui.search.errorMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Items from the end of the list at which the next page is requested (as in Search). */
private const val PREFETCH_DISTANCE = 5

/** A series' or author's works: back arrow and title, then the works as Search shows them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkListScreen(
    vm: WorkListViewModel,
    onBack: () -> Unit,
    onOpenWork: OpenWork,
    onOpenWorkList: OpenWorkList,
    onAccount: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val account by rememberUpdatedState(onAccount)
    LaunchedEffect(vm, snackbar) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, actionLabel = if (m.toAccount) "Account" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) account()
        }
    }
    val current by rememberUpdatedState(state)
    val listState = rememberLazyListState()
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 1 - PREFETCH_DISTANCE
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd && current.hasNext && !current.loading && current.error == null && current.works.isNotEmpty() }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.loadNextPage() }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
            Text(
                state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
            )
            if (!state.invalid) SubscribeButton(vm)
        }
        // Fixed 2 dp slot: page 1 loading shows here (no full-screen spinner); the list never shifts.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.loading && state.page == 0) {
                LinearProgressIndicator(Modifier.fillMaxSize(), trackColor = MaterialTheme.colorScheme.background)
            }
        }
        val bottomInset = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.works.isEmpty()) {
                item(key = "status", contentType = "status") {
                    when {
                        state.invalid -> Hint("This link can't be opened in the app.")
                        state.error != null -> ErrorRow(state.error!!, vm::retry)
                        state.loaded -> Hint("No works here.")
                        else -> Unit
                    }
                }
            }
            items(state.works, key = { it.workId }, contentType = { "work" }) { w ->
                val progress = state.progressByWork[w.workId]
                WorkCard(
                    work = w,
                    summary = state.summaries[w.workId].orEmpty(),
                    badge = badgeText(progress?.chapterNumber, w.workId in state.readMarks, w.workId in state.downloadedIds),
                    onClick = { onOpenWork(w.workId, progress?.chapterId, progress?.chapterNumber ?: 1) },
                    links = state.links[w.workId],
                    onOpenWorkList = onOpenWorkList,
                )
            }
            if (state.works.isNotEmpty() && (state.loading || state.error != null)) {
                item(key = "footer", contentType = "status") {
                    if (state.error != null) ErrorRow(state.error!!, vm::retry) else Loading()
                }
            }
        }
    }
    SnackbarHost(
        snackbar,
        Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
    )
    }
}

/** Subscribe / Unsubscribe for the series or author (signed in only); collects its own narrow state. */
@Composable
private fun SubscribeButton(vm: WorkListViewModel) {
    val ui by vm.subscribe.collectAsStateWithLifecycle()
    if (!ui.signedIn) return
    TextButton(onClick = vm::toggleSubscribe, enabled = !ui.running, modifier = Modifier.padding(end = 4.dp)) {
        if (ui.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Text(if (ui.subscribed) "Unsubscribe" else "Subscribe")
    }
}

@Composable
private fun ErrorRow(error: FetchResult, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            errorMessage(error), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
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
