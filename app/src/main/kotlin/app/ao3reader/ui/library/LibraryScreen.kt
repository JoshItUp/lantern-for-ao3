package app.ao3reader.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.data.LibrarySection
import app.ao3reader.ui.common.BlockText
import app.ao3reader.ui.common.OpenWorkList
import app.ao3reader.ui.search.OpenWork
import app.ao3reader.ui.search.Tags
import app.ao3reader.ui.search.WorkCard
import app.ao3reader.ui.search.badgeText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Items from the end of the list at which the next page is requested (as in Search). */
private const val PREFETCH_DISTANCE = 5

private fun LibrarySection.label() = when (this) {
    LibrarySection.HISTORY -> "History"
    LibrarySection.MARKED_FOR_LATER -> "Later"
    LibrarySection.BOOKMARKS -> "Bookmarks"
    LibrarySection.SUBSCRIPTIONS -> "Subscriptions"
}

private fun LibrarySection.emptyText() = when (this) {
    LibrarySection.HISTORY -> "No reading history yet."
    LibrarySection.MARKED_FOR_LATER -> "Nothing marked for later."
    LibrarySection.BOOKMARKS -> "No bookmarks yet."
    LibrarySection.SUBSCRIPTIONS -> "No subscriptions yet."
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    /** The bottom bar overlays this screen; the list pads by it (constant, so nothing relayouts). */
    bottomBarHeight: Dp,
    onAccount: () -> Unit,
    onOpenWork: OpenWork,
    onOpenWorkList: OpenWorkList,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // One list state per tab, saved with the screen: switching tabs (or coming back from the reader)
    // returns to where each list was left.
    val listStates = LibrarySection.entries.map { rememberLazyListState() }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            // Opaque, so the tab crossfade never shows the window behind.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
    ) {
        if (!state.signedIn) {
            SignInPrompt(bottomBarHeight, onAccount)
            return@Column
        }
        val selected = state.selected
        val section = state.sections.getValue(selected)
        Tabs(selected) { tapped ->
            if (tapped == selected) {
                // Tapping the shown tab again: back to the top (jump when far, like Search).
                val ls = listStates[tapped.ordinal]
                scope.launch { if (ls.firstVisibleItemIndex > 10) ls.scrollToItem(0) else ls.animateScrollToItem(0) }
            } else {
                vm.select(tapped)
            }
        }
        // Fixed 2 dp slot: the list never shifts when the bar appears.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (section.refreshing && !section.pulled && !section.isEmpty) {
                LinearProgressIndicator(Modifier.fillMaxSize(), trackColor = MaterialTheme.colorScheme.background)
            }
        }
        key(selected) {
            SectionList(
                section = selected,
                s = section,
                listState = listStates[selected.ordinal],
                state = state,
                bottomBarHeight = bottomBarHeight,
                onRefresh = { vm.refresh(selected) },
                onNextPage = { vm.loadNextPage(selected) },
                onRetry = { vm.retry(selected) },
                onOpenWork = { workId ->
                    val p = state.progressByWork[workId]
                    onOpenWork(workId, p?.chapterId, p?.chapterNumber ?: 1)
                },
                onOpenWorkList = onOpenWorkList,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(selected: LibrarySection, onTap: (LibrarySection) -> Unit) {
    SecondaryScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        edgePadding = 8.dp,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(selected.ordinal, matchContentSize = false),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) },
    ) {
        for (sec in LibrarySection.entries) {
            Tab(
                selected = sec == selected,
                onClick = { onTap(sec) },
                selectedContentColor = MaterialTheme.colorScheme.onBackground,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(44.dp),
                text = {
                    Text(
                        sec.label(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sec == selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SectionList(
    section: LibrarySection,
    s: SectionState,
    listState: LazyListState,
    state: LibraryUiState,
    bottomBarHeight: Dp,
    onRefresh: () -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    onOpenWork: (Long) -> Unit,
    onOpenWorkList: OpenWorkList,
) {
    val current by rememberUpdatedState(s)
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 1 - PREFETCH_DISTANCE
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd && current.hasNext && !current.loading && current.error == null && !current.isEmpty }
            .distinctUntilChanged()
            .filter { it }
            .collect { onNextPage() }
    }
    // Page 1 replaced the list (refresh or first load): while the list sits at its very top, keep it
    // there so new works at the top are seen (the list otherwise stays anchored on the old first card).
    // Index 0 is the zero-height "top" item, so this holds only at the very top.
    var seenReplace by remember(listState) { mutableIntStateOf(s.replaceId) }
    LaunchedEffect(listState) {
        snapshotFlow { current.replaceId }
            .filter { it != seenReplace }
            .collect {
                seenReplace = it
                if (listState.firstVisibleItemIndex == 0) listState.requestScrollToItem(0)
            }
    }

    val bottomInset = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    PullToRefreshBox(
        isRefreshing = s.refreshing && s.pulled,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // The zero-height "top" item plus the item spacing gives the gap above the first card.
                start = 12.dp, end = 12.dp, top = 0.dp,
                bottom = bottomInset + bottomBarHeight + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (section == LibrarySection.SUBSCRIPTIONS) 8.dp else 10.dp),
        ) {
            // Always first: anchors the very top, and holds a failed refresh's error above the kept list.
            item(key = "top", contentType = "top") {
                if (!s.isEmpty && s.error != null && s.failedPage == 1) ErrorRow(s.error, onRetry)
            }
            if (s.isEmpty) {
                item(key = "status", contentType = "status") {
                    when {
                        s.error != null -> ErrorRow(s.error, onRetry)
                        s.loading -> Loading()
                        s.loaded -> Hint(section.emptyText())
                        else -> Unit
                    }
                }
            }
            if (section == LibrarySection.SUBSCRIPTIONS) {
                items(s.subs, key = { it.key }, contentType = { "sub" }) { row ->
                    val p = row.workId?.let { state.progressByWork[it] }
                    val workId = row.workId
                    val url = row.url
                    SubscriptionRow(
                        row = row,
                        badge = workId?.let { badgeText(p?.chapterNumber, it in state.readMarks) },
                        onClick = when {
                            workId != null -> { { onOpenWork(workId) } }
                            url != null -> { { onOpenWorkList(url, row.listTitle) } }
                            else -> null
                        },
                    )
                }
            } else {
                items(s.works, key = { it.workId }, contentType = { "work" }) { w ->
                    val progress = state.progressByWork[w.workId]
                    val extras = s.extras[w.workId]
                    WorkCard(
                        work = w,
                        summary = s.summaries[w.workId].orEmpty(),
                        badge = badgeText(progress?.chapterNumber, w.workId in state.readMarks),
                        onClick = { onOpenWork(w.workId) },
                        links = s.links[w.workId],
                        onOpenWorkList = onOpenWorkList,
                        extras = extras?.let { e -> { LibraryExtras(e) } },
                    )
                }
            }
            if (!s.isEmpty && ((s.loading && !s.refreshing) || (s.error != null && s.failedPage != 1))) {
                item(key = "footer", contentType = "status") {
                    if (s.error != null) ErrorRow(s.error, onRetry) else Loading()
                }
            }
        }
    }
}

private val ChipShape = RoundedCornerShape(6.dp)

/** History: "Visited … · N visits" and an "Update available" chip. Bookmarks: notes, labels and tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LibraryExtras(e: CardExtras) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    if (e.visited != null || e.updateAvailable) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (e.visited != null) {
                Text(e.visited, style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.weight(1f, fill = false))
            }
            if (e.updateAvailable) {
                Text(
                    "Update available",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(start = if (e.visited != null) 8.dp else 0.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, ChipShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
    if (e.labels.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in e.labels) {
                Text(
                    l, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (e.notes.isNotEmpty()) {
        BlockText(
            e.notes, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), blockSpacing = 4.dp,
        )
    }
    if (e.tags.isNotEmpty()) Tags(e.tags, muted, emphasized = false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignInPrompt(bottomBarHeight: Dp, onAccount: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(start = 24.dp, end = 24.dp, bottom = bottomBarHeight),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            SIGN_IN_TO_SEE_LIBRARY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onAccount, shape = RoundedCornerShape(14.dp)) { Text("Sign in") }
    }
}

/** Always retryable: with no account the sign-in prompt shows instead of any list. */
@Composable
private fun ErrorRow(error: FetchResult, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            libraryErrorMessage(error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
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
