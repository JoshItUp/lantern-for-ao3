package io.github.joshitup.lantern.ui.library

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
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.joshitup.lantern.ao3.net.FetchResult
import io.github.joshitup.lantern.data.LibrarySection
import io.github.joshitup.lantern.ui.common.BlockText
import io.github.joshitup.lantern.ui.common.Hint
import io.github.joshitup.lantern.ui.common.Loading
import io.github.joshitup.lantern.ui.common.OpenWorkList
import io.github.joshitup.lantern.ui.search.OpenWork
import io.github.joshitup.lantern.ui.search.Tags
import io.github.joshitup.lantern.ui.search.WorkCard
import io.github.joshitup.lantern.ui.search.badgeText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import io.github.joshitup.lantern.ui.common.rememberListSides
import io.github.joshitup.lantern.ui.common.heightForLabel

/** Items from the end of the list at which the next page is requested (as in Search). */
private const val PREFETCH_DISTANCE = 5

private fun LibrarySection.label() = when (this) {
    LibrarySection.HISTORY -> "History"
    LibrarySection.MARKED_FOR_LATER -> "Later"
    LibrarySection.BOOKMARKS -> "Bookmarks"
    LibrarySection.SUBSCRIPTIONS -> "Subscriptions"
}

private fun LibraryTab.label() = when (this) {
    is LibraryTab.Ao3 -> section.label()
    LibraryTab.Downloads -> "Downloads"
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
    downloadsVm: DownloadsViewModel,
    /** The bottom bar overlays this screen; the list pads by it (constant, so nothing relayouts). */
    bottomBarHeight: Dp,
    onAccount: () -> Unit,
    onOpenWork: OpenWork,
    onOpenWorkList: OpenWorkList,
    /** Runs with each Update/Retry tap: asks for the notification permission the first time (never blocks it). */
    askNotifications: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by downloadsVm.state.collectAsStateWithLifecycle()
    // One list state per tab, saved with the screen: switching tabs (or coming back from the reader)
    // returns to where each list was left.
    val listStates = LIBRARY_TABS.map { rememberLazyListState() }
    val scope = rememberCoroutineScope()
    val openWork: (Long) -> Unit = { workId ->
        val p = state.progressByWork[workId]
        onOpenWork(workId, p?.chapterId, p?.chapterNumber ?: 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            // Opaque, so the tab crossfade never shows the window behind.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
    ) {
        val selected = state.selected
        val selectedIndex = LIBRARY_TABS.indexOf(selected)
        // The tab row always shows; signed out, the AO3 tabs show the sign-in prompt in their own area.
        Tabs(selectedIndex) { tapped ->
            if (tapped == selectedIndex) {
                // Tapping the shown tab again: back to the top (jump when far, like Search).
                val ls = listStates[tapped]
                scope.launch { if (ls.firstVisibleItemIndex > 10) ls.scrollToItem(0) else ls.animateScrollToItem(0) }
            } else {
                vm.select(LIBRARY_TABS[tapped])
            }
        }
        val section = (selected as? LibraryTab.Ao3)?.let { state.sections.getValue(it.section) }
        // Fixed 2 dp slot: the list never shifts when the bar appears.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.signedIn && section != null && section.refreshing && !section.pulled && !section.isEmpty) {
                LinearProgressIndicator(Modifier.fillMaxSize(), trackColor = MaterialTheme.colorScheme.background)
            }
        }
        key(selected) {
            when {
                selected is LibraryTab.Downloads -> DownloadsTab(
                    s = downloads,
                    listState = listStates[selectedIndex],
                    progressByWork = state.progressByWork,
                    readMarks = state.readMarks,
                    bottomBarHeight = bottomBarHeight,
                    onOpenWork = openWork,
                    onUpdate = { id -> downloadsVm.update(id); askNotifications() },
                    onAskDelete = downloadsVm::askDelete,
                    onConfirmDelete = downloadsVm::confirmDelete,
                    onCancelDelete = downloadsVm::cancelDelete,
                )
                selected is LibraryTab.Ao3 && !state.signedIn -> SignInPrompt(bottomBarHeight, onAccount)
                selected is LibraryTab.Ao3 && section != null -> SectionList(
                    section = selected.section,
                    s = section,
                    listState = listStates[selectedIndex],
                    state = state,
                    bottomBarHeight = bottomBarHeight,
                    onRefresh = { vm.refresh(selected.section) },
                    onNextPage = { vm.loadNextPage(selected.section) },
                    onRetry = { vm.retry(selected.section) },
                    onOpenWork = openWork,
                    onOpenWorkList = onOpenWorkList,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(selected: Int, onTap: (Int) -> Unit) {
    SecondaryScrollableTabRow(
        selectedTabIndex = selected,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        edgePadding = 8.dp,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(selected, matchContentSize = false),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) },
    ) {
        LIBRARY_TABS.forEachIndexed { i, tab ->
            Tab(
                selected = i == selected,
                onClick = { onTap(i) },
                selectedContentColor = MaterialTheme.colorScheme.onBackground,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                // Grows with the label at large font sizes.
                modifier = Modifier.height(heightForLabel(44.dp, MaterialTheme.typography.labelLarge)),
                text = {
                    Text(
                        tab.label(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium,
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
    val sides = rememberListSides()
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
                start = sides.start, end = sides.end, top = 0.dp,
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
                        badge = workId?.let { badgeText(p?.chapterNumber, it in state.readMarks, it in state.downloadedIds) },
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
                        badge = badgeText(progress?.chapterNumber, w.workId in state.readMarks, w.workId in state.downloadedIds),
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

/** Always retryable: with no account the sign-in prompt shows instead of the AO3 lists. */
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
