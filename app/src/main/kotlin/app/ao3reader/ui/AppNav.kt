package app.ao3reader.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ao3reader.AppContainer
import app.ao3reader.ui.account.AccountScreen
import app.ao3reader.ui.account.AccountViewModel
import app.ao3reader.ui.common.AppIcons
import app.ao3reader.ui.library.LibraryPlaceholder
import app.ao3reader.ui.reader.ReaderScreen
import app.ao3reader.ui.reader.ReaderViewModel
import app.ao3reader.ui.search.SearchScreen
import app.ao3reader.ui.search.SearchViewModel
import kotlinx.coroutines.flow.MutableSharedFlow

object Routes {
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val ACCOUNT = "account"

    /** Top-level destinations: the bottom bar shows only on these. */
    val TABS = setOf(SEARCH, LIBRARY)

    const val ARG_WORK_ID = "workId"
    const val ARG_CHAPTER_ID = "chapterId"
    const val ARG_NUMBER = "number"

    /** workId: Long. chapterId: Long as a string, absent for "first chapter"/single-chapter works. number: Int, default 1. */
    const val READER = "reader/{$ARG_WORK_ID}?$ARG_CHAPTER_ID={$ARG_CHAPTER_ID}&$ARG_NUMBER={$ARG_NUMBER}"

    fun reader(workId: Long, chapterId: Long?, number: Int): String =
        "reader/$workId?$ARG_NUMBER=$number" + (chapterId?.let { "&$ARG_CHAPTER_ID=$it" } ?: "")
}

/** Short screen transitions (the NavHost default is a ~700 ms crossfade). */
private const val ENTER_MS = 220
private const val UNDER_MS = 150
private const val SCALE_FROM = 0.96f
/** Switching tabs: a plain quick crossfade. */
private const val TAB_MS = 150
/** The bottom bar slides out/in this fast when a pushed screen covers the tabs. */
private const val BAR_MS = 160

/** Height of the bottom bar above the navigation-bar inset; tab screens pad their content by this (constant). */
val BottomBarHeight = 64.dp

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in Routes.TABS && targetState.destination.route in Routes.TABS

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    // Derived: the bar recomposes only when tab-ness or the selected tab actually changes.
    val showBar by remember { derivedStateOf { entry?.destination?.route.let { it == null || it in Routes.TABS } } }
    val libraryTab by remember { derivedStateOf { entry?.destination?.route == Routes.LIBRARY } }
    val searchReselect = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    // Plain (non-predictive) back from the Library root: instant switch to Search.
    BackHandler(enabled = libraryTab) { nav.switchTab(Routes.SEARCH) }

    fun openAccount() = nav.navigate(Routes.ACCOUNT) { launchSingleTop = true }

    // The bar overlays the NavHost, so hiding it never relayouts a screen (tabs pad by BottomBarHeight).
    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.SEARCH,
            modifier = Modifier.fillMaxSize(),
            // Forward: the new screen fades in while growing slightly; the one beneath fades out quickly.
            enterTransition = {
                if (isTabSwitch()) {
                    fadeIn(tween(TAB_MS))
                } else {
                    fadeIn(tween(ENTER_MS, easing = FastOutSlowInEasing)) +
                        scaleIn(tween(ENTER_MS, easing = FastOutSlowInEasing), initialScale = SCALE_FROM)
                }
            },
            exitTransition = {
                if (isTabSwitch()) fadeOut(tween(TAB_MS)) else fadeOut(tween(UNDER_MS, easing = FastOutSlowInEasing))
            },
            // Back is always instant: no pop transitions anywhere.
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.SEARCH) {
                val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
                val needsSignIn by container.session.needsSignIn.collectAsStateWithLifecycle()
                SearchScreen(
                    vm,
                    needsSignIn = needsSignIn,
                    bottomBarHeight = BottomBarHeight,
                    reselect = searchReselect,
                    onAccount = ::openAccount,
                ) { workId, chapterId, number ->
                    nav.navigate(Routes.reader(workId, chapterId, number)) { launchSingleTop = true }
                }
            }
            composable(Routes.LIBRARY) {
                // Placeholder until the real library screen replaces it.
                val account by container.session.state.collectAsStateWithLifecycle()
                LibraryPlaceholder(account.signedIn, BottomBarHeight, onAccount = ::openAccount)
            }
            composable(Routes.ACCOUNT) { e ->
                val vm: AccountViewModel = viewModel(factory = AccountViewModel.factory(container))
                AccountScreen(vm, onBack = { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack() })
            }
            composable(
                Routes.READER,
                arguments = listOf(
                    navArgument(Routes.ARG_WORK_ID) { type = NavType.LongType },
                    navArgument(Routes.ARG_CHAPTER_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Routes.ARG_NUMBER) { type = NavType.IntType; defaultValue = 1 },
                ),
            ) { e ->
                val args = e.arguments
                val workId = args?.getLong(Routes.ARG_WORK_ID) ?: 0L
                val chapterId = args?.getString(Routes.ARG_CHAPTER_ID)?.toLongOrNull()
                val number = args?.getInt(Routes.ARG_NUMBER) ?: 1
                val vm: ReaderViewModel = viewModel(factory = ReaderViewModel.factory(container, workId, chapterId, number))
                // Only pop while this entry is on top: a second back during the exit animation must
                // not pop the search screen as well.
                ReaderScreen(vm, onBack = { if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack() })
            }
        }

        AnimatedVisibility(
            visible = showBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(BAR_MS, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(BAR_MS, easing = FastOutSlowInEasing)) { it },
        ) {
            BottomBar(
                libraryTab = libraryTab,
                onSearch = { if (libraryTab) nav.switchTab(Routes.SEARCH) else searchReselect.tryEmit(Unit) },
                onLibrary = { if (!libraryTab) nav.switchTab(Routes.LIBRARY) },
            )
        }
    }
}

/** Top-level switch: each tab keeps its own back stack and scroll state. */
private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BottomBar(libraryTab: Boolean, onSearch: () -> Unit, onLibrary: () -> Unit) {
    val color = NavigationBarDefaults.containerColor
    // The inset ignores visibility, so the bar never jumps while the reader's system bars come back.
    Box(
        Modifier
            .fillMaxWidth()
            .background(color)
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
    ) {
        NavigationBar(
            modifier = Modifier.height(BottomBarHeight),
            containerColor = color,
            windowInsets = WindowInsets(0),
        ) {
            NavigationBarItem(
                selected = !libraryTab,
                onClick = onSearch,
                icon = { Icon(AppIcons.Search, contentDescription = null) },
                label = { Text("Search") },
            )
            NavigationBarItem(
                selected = libraryTab,
                onClick = onLibrary,
                icon = { Icon(AppIcons.Library, contentDescription = null) },
                label = { Text("Library") },
            )
        }
    }
}
