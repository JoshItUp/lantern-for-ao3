package app.ao3reader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ao3reader.ao3.model.Block
import app.ao3reader.ao3.model.ChapterContent
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ui.common.CardLinks
import app.ao3reader.ui.common.OpenWorkList
import app.ao3reader.ui.common.headerLinks
import app.ao3reader.ui.search.LinkLine
import app.ao3reader.ui.common.BlockItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What one chapter pane shows; AnimatedContent keeps the outgoing pane's copy only while it slides out. */
private data class Page(val index: Int, val content: ChapterContent?, val showSpinner: Boolean, val error: FetchResult?)

/** A swipe that changed chapter: the pane leaving ([from]) keeps the offset it was released at, so it doesn't jump. */
private data class SwipeExit(val from: Int, val to: Int, val offset: Float)

private const val SWIPE_FRACTION = 0.3f
private const val CHAPTER_SLIDE_MS = 280
private const val FADE_IN_MS = 180
private const val FLING_VELOCITY_DP = 800f
/** Resistance applied to drags that can't change chapter (first/last). */
private const val EDGE_RESISTANCE = 0.2f
/** The bottom controls bar's height above the navigation-bar inset: the action row alone. */
private val BOTTOM_BAR_HEIGHT = 64.dp

/**
 * [onBack] pops the reader. [askNotifications] runs with every Download/Update/Retry tap (it asks for
 * the notification permission the first time); the download is requested whatever it does. Back is handled here with a plain (non-predictive) callback, so there is
 * no gesture-following preview: the bars come back first, then the pop plays on release.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onBack: () -> Unit,
    onOpenWorkList: OpenWorkList,
    onAccount: () -> Unit,
    askNotifications: () -> Unit = {},
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var showFontSheet by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val account by rememberUpdatedState(onAccount)
    val ask by rememberUpdatedState(askNotifications)
    val onDownload: () -> Unit = remember(vm) { { vm.onDownload(); ask() } }
    // One stable slot for every chapter footer: the row inside collects the actions itself, so an
    // action never recomposes the chapter LazyColumn.
    val footerActions: @Composable () -> Unit = remember(vm) { { ReaderActionRow(vm, onSignIn = { account() }) } }
    // The footer's download slot shows the new-chapters line only at the last stored chapter; the ⋯ menu always does.
    val footerDownload: @Composable (Boolean) -> Unit = remember(vm) { { showNew -> DownloadSlot(vm, showNew, onDownload) } }
    val lastStored = s.download?.takeIf { it.hasCopy }?.chaptersStored?.minus(1) ?: -1

    val bars = ImmersiveMode(hidden = !s.controlsVisible)
    FlushOnStop(vm)
    // One handler: an open chapter drawer closes first; otherwise restore the bars, then leave.
    BackHandler {
        if (drawer.isOpen) {
            scope.launch { drawer.close() }
        } else {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            onBack()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,   // an edge swipe must never fight chapter swipes
        drawerContent = {
            ChapterDrawer(s.chapters, s.index) { i ->
                scope.launch { drawer.close() }
                vm.goTo(i)
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ChapterSwiper(
                index = s.index,
                count = s.chapters.size,
                onNext = vm::next,
                onPrevious = vm::previous,
                onCentreTap = vm::toggleControls,
            ) { dragOffset, exit, onSettled ->
                AnimatedContent(
                    targetState = Page(s.index, s.content, s.showSpinner, s.error),
                    contentKey = { it.index },
                    transitionSpec = {
                        val forward = targetState.index > initialState.index
                        // After a swipe the outgoing pane sits at the release offset; start the
                        // incoming one right beside it and move both by the same distance, so the
                        // two stay edge to edge with no gap.
                        val released = exit?.takeIf { it.from == initialState.index && it.to == targetState.index }
                            ?.offset?.roundToInt() ?: 0
                        val spec = tween<IntOffset>(CHAPTER_SLIDE_MS)
                        slideInHorizontally(spec) { w -> (if (forward) w else -w) + released } togetherWith
                            slideOutHorizontally(spec) { w -> -((if (forward) w else -w) + released) }
                    },
                    label = "chapter",
                ) { page ->
                    val isCurrent = page.index == s.index
                    if (isCurrent) {
                        // Once the incoming pane has fully arrived, the release offset is stale.
                        LaunchedEffect(Unit) {
                            snapshotFlow { transition.currentState == EnterExitState.Visible && !transition.isRunning }
                                .first { it }
                            onSettled(page.index)
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            translationX = when {
                                isCurrent -> dragOffset()
                                exit != null && exit.from == page.index && exit.to == s.index -> exit.offset
                                else -> 0f
                            }
                        }
                    ) {
                        ChapterPane(
                            page = page,
                            isCurrent = isCurrent,
                            isLast = page.index == s.chapters.lastIndex,
                            restore = if (isCurrent) s.restore else null,
                            style = rememberReaderTextStyle(s.font, s.fontSizeSp),
                            onRestored = vm::restoreApplied,
                            onScroll = vm::onScroll,
                            onNext = vm::next,
                            onRetry = vm::retry,
                            onOpenWorkList = onOpenWorkList,
                            footerActions = footerActions,
                            footerDownload = footerDownload,
                            atLastStored = page.index == lastStored,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = s.controlsVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    label = chapterLabel(s.chapters, s.index, s.chapterListLoaded),
                    onMenu = { scope.launch { drawer.open() } },
                    onFont = { showFontSheet = true },
                    download = s.download,
                    onDownload = onDownload,
                )
            }

            AnimatedVisibility(
                visible = s.controlsVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderBottomBar(actions = footerActions)
            }

            // Above the bottom bar while it shows; the inset ignores visibility so nothing jumps.
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(bottom = if (s.controlsVisible) BOTTOM_BAR_HEIGHT else 0.dp),
            )
        }
    }

    ReaderActionSheets(vm, snackbar, onAccount = { account() })

    if (showFontSheet) {
        FontSheet(
            sizeSp = s.fontSizeSp,
            font = s.font,
            onSize = vm::setFontSize,
            onFont = vm::setFont,
            onDismiss = { showFontSheet = false },
        )
    }
}

/**
 * Horizontal swipes change chapter; vertical drags are left to the LazyColumn inside (the
 * horizontal detector only claims a gesture once it has moved past touch slop sideways).
 * A tap in the middle third (width and height) toggles controls; links consume their own taps.
 */
@Composable
private fun ChapterSwiper(
    index: Int,
    count: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCentreTap: () -> Unit,
    content: @Composable (dragOffset: () -> Float, exit: SwipeExit?, onSettled: (index: Int) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var exit by remember { mutableStateOf<SwipeExit?>(null) }
    val flingPx = with(LocalDensity.current) { FLING_VELOCITY_DP.dp.toPx() }
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val tap by rememberUpdatedState(onCentreTap)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width / 3f
                    val h = size.height / 3f
                    if (pos.x in w..2 * w && pos.y in h..2 * h) tap()
                }
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var raw = 0f
                fun canGo(towardsNext: Boolean) =
                    if (towardsNext) currentIndex < currentCount - 1 else currentIndex > 0
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        raw = 0f
                        scope.launch { offset.stop() }
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        raw += amount
                        val shown = if (canGo(raw < 0)) raw else raw * EDGE_RESISTANCE
                        scope.launch { offset.snapTo(shown) }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val width = size.width.toFloat()
                        val towardsNext = raw < 0
                        val passed = abs(raw) > width * SWIPE_FRACTION ||
                            (abs(v) > flingPx && (v < 0) == towardsNext && abs(raw) > 0f)
                        if (passed && canGo(towardsNext)) {
                            exit = SwipeExit(currentIndex, currentIndex + if (towardsNext) 1 else -1, offset.value)
                            scope.launch { offset.snapTo(0f) }
                            if (towardsNext) next() else previous()
                        } else {
                            scope.launch { offset.animateTo(0f, spring()) }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                )
            },
    ) {
        content({ offset.value }, exit) { settled -> if (exit?.to == settled) exit = null }
    }
}

@Composable
private fun ChapterPane(
    page: Page,
    isCurrent: Boolean,
    isLast: Boolean,
    restore: Pair<Int, Int>?,
    style: TextStyle,
    onRestored: () -> Unit,
    onScroll: (Int, Int) -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onOpenWorkList: OpenWorkList,
    footerActions: @Composable () -> Unit,
    footerDownload: @Composable (Boolean) -> Unit,
    atLastStored: Boolean,
) {
    val content = page.content
    // Text that was ready when the pane appeared shows at once; text that arrives later fades in instead of popping.
    val alpha = remember { Animatable(if (content != null) 1f else 0f) }
    LaunchedEffect(content != null) {
        if (content != null) alpha.animateTo(1f, tween(FADE_IN_MS)) else alpha.snapTo(0f)
    }
    when {
        content != null -> Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
            ChapterText(content, isCurrent, isLast, restore, style, onRestored, onScroll, onNext, onOpenWorkList, footerActions, footerDownload, atLastStored)
        }
        page.error != null -> ErrorMessage(page.error, onRetry)
        // Empty background until the load has taken SPINNER_DELAY_MS, so cached swipes never flash a spinner.
        page.showSpinner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Box(Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterText(
    content: ChapterContent,
    isCurrent: Boolean,
    isLast: Boolean,
    restore: Pair<Int, Int>?,
    style: TextStyle,
    onRestored: () -> Unit,
    onScroll: (Int, Int) -> Unit,
    onNext: () -> Unit,
    onOpenWorkList: OpenWorkList,
    footerActions: @Composable () -> Unit,
    footerDownload: @Composable (Boolean) -> Unit,
    atLastStored: Boolean,
) {
    val listState = rememberLazyListState()
    val report by rememberUpdatedState(if (isCurrent) onScroll else null)

    LaunchedEffect(restore) {
        if (restore != null) {
            listState.scrollToItem(restore.first, restore.second)
            onRestored()
        }
    }
    LaunchedEffect(listState) {
        // Emits once per item / SCROLL_BUCKET_PX while scrolling, plus the exact resting position
        // when a scroll ends, instead of every frame; the values reported are always exact.
        snapshotFlow {
            scrollKey(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect { report?.invoke(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    val text = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    val noteStyle = remember(style) { style.forNotes() }
    // Built once per chapter from the parsed links (a few objects; no parsing).
    val links = remember(content) { content.headerLinks() }
    // Stable padding: the bars come and go with the controls, the text must not jump.
    val bars = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    val padding = PaddingValues(
        start = 20.dp, end = 20.dp,
        top = bars.calculateTopPadding() + 16.dp, bottom = bars.calculateBottomPadding() + 24.dp,
    )

    LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
        if (content.ref.number == 1 || content.summary.isNotEmpty()) {
            workHeader(content, links, style, noteStyle, text, dim, onOpenWorkList)
        }
        if (content.ref.title.isNotBlank()) {
            item(key = "chapter-title") {
                Text(
                    content.ref.title, style = style, fontWeight = FontWeight.SemiBold, color = text,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
        blocks("begin", content.beginNotes, noteStyle, dim)
        if (content.beginNotes.isNotEmpty()) item(key = "begin-rule") { NoteRule() }
        blocks("body", content.body, style, text)
        if (content.endNotes.isNotEmpty()) item(key = "end-rule") { NoteRule() }
        blocks("end", content.endNotes, noteStyle, dim)
        item(key = "footer") { ChapterFooter(isLast, onNext, footerActions, footerDownload, atLastStored) }
    }
}

private fun LazyListScope.workHeader(
    content: ChapterContent,
    links: CardLinks,
    style: TextStyle,
    noteStyle: TextStyle,
    text: Color,
    dim: Color,
    onOpenWorkList: OpenWorkList,
) {
    item(key = "work-title") {
        Column(Modifier.padding(bottom = 12.dp)) {
            Text(
                content.workTitle, color = text, fontWeight = FontWeight.Bold,
                style = style.copy(fontSize = style.fontSize * 1.35f),
            )
            // Authors and series open their work lists; the rest of the header is not a tap target.
            if (links.authors.isNotEmpty()) LinkLine("by", links.authors, noteStyle, dim, onOpenWorkList)
            for (s in links.series) LinkLine(null, listOf(s), noteStyle, dim, onOpenWorkList)
        }
    }
    blocks("summary", content.summary, noteStyle, dim)
    item(key = "header-rule") { NoteRule() }
}

private fun LazyListScope.blocks(prefix: String, blocks: List<Block>, style: TextStyle, color: Color) {
    items(blocks.size, key = { "$prefix-$it" }) { i ->
        Box(Modifier.padding(bottom = 12.dp)) { BlockItem(blocks[i], style, color) }
    }
}

@Composable
private fun NoteRule() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** The same action row as the controls, then the way on. */
@Composable
private fun ChapterFooter(
    isLast: Boolean,
    onNext: () -> Unit,
    actions: @Composable () -> Unit,
    download: @Composable (Boolean) -> Unit,
    atLastStored: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        actions()
        download(atLastStored)
        Spacer(Modifier.height(16.dp))
        if (isLast) {
            Text("End of work", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedButton(onClick = onNext) { Text("Next chapter →") }
        }
    }
}

/** The controls' bottom bar: the action row on the same translucent surface as the top bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBottomBar(actions: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).height(BOTTOM_BAR_HEIGHT),
            contentAlignment = Alignment.Center,
        ) { actions() }
    }
}

@Composable
private fun ErrorMessage(error: FetchResult, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            readerErrorText(error),
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        )
        // Nothing to retry for a chapter that was never downloaded; other chapters still swipe (the swiper sits above this).
        if (!isNotDownloaded(error)) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

/**
 * Hides the system bars while [hidden]; always restores them when the reader leaves composition.
 * Returns the controller so back can show the bars before the pop starts.
 */
@Composable
private fun ImmersiveMode(hidden: Boolean): WindowInsetsControllerCompat? {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    val controller = remember(window) { window?.let { WindowCompat.getInsetsController(it, view) } }
    DisposableEffect(controller) {
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(controller, hidden) {
        if (hidden) controller?.hide(WindowInsetsCompat.Type.systemBars())
        else controller?.show(WindowInsetsCompat.Type.systemBars())
    }
    return controller
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun FlushOnStop(vm: ReaderViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.flushProgress() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            vm.flushProgress()   // leaving the reader: don't lose a position still inside the debounce
        }
    }
}
