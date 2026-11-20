package app.ao3reader.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ao3reader.AppContainer
import app.ao3reader.ui.reader.ReaderScreen
import app.ao3reader.ui.reader.ReaderViewModel
import app.ao3reader.ui.search.SearchScreen
import app.ao3reader.ui.search.SearchViewModel

object Routes {
    const val SEARCH = "search"

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
private const val EXIT_MS = 200
private const val UNDER_MS = 150
private const val SCALE_FROM = 0.96f

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.SEARCH,
        // Forward: the new screen fades in while growing slightly; the one beneath fades out quickly.
        enterTransition = {
            fadeIn(tween(ENTER_MS, easing = FastOutSlowInEasing)) +
                scaleIn(tween(ENTER_MS, easing = FastOutSlowInEasing), initialScale = SCALE_FROM)
        },
        exitTransition = { fadeOut(tween(UNDER_MS, easing = FastOutSlowInEasing)) },
        // Back: the mirror image.
        popEnterTransition = { fadeIn(tween(UNDER_MS, easing = FastOutSlowInEasing)) },
        popExitTransition = {
            fadeOut(tween(EXIT_MS, easing = FastOutSlowInEasing)) +
                scaleOut(tween(EXIT_MS, easing = FastOutSlowInEasing), targetScale = SCALE_FROM)
        },
    ) {
        composable(Routes.SEARCH) {
            val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
            SearchScreen(vm) { workId, chapterId, number ->
                nav.navigate(Routes.reader(workId, chapterId, number)) { launchSingleTop = true }
            }
        }
        composable(
            Routes.READER,
            arguments = listOf(
                navArgument(Routes.ARG_WORK_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_CHAPTER_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(Routes.ARG_NUMBER) { type = NavType.IntType; defaultValue = 1 },
            ),
        ) { entry ->
            val args = entry.arguments
            val workId = args?.getLong(Routes.ARG_WORK_ID) ?: 0L
            val chapterId = args?.getString(Routes.ARG_CHAPTER_ID)?.toLongOrNull()
            val number = args?.getInt(Routes.ARG_NUMBER) ?: 1
            val vm: ReaderViewModel = viewModel(factory = ReaderViewModel.factory(container, workId, chapterId, number))
            ReaderScreen(vm)
        }
    }
}
