package app.ao3reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ao3reader.AppContainer
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

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SEARCH) {
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
            ReaderPlaceholder(
                workId = args?.getLong(Routes.ARG_WORK_ID) ?: 0L,
                chapterId = args?.getString(Routes.ARG_CHAPTER_ID)?.toLongOrNull(),
                number = args?.getInt(Routes.ARG_NUMBER) ?: 1,
            )
        }
    }
}

/** Replaced by the real reader in Task 11. */
@Composable
private fun ReaderPlaceholder(workId: Long, chapterId: Long?, number: Int) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Reader coming in Task 11", style = MaterialTheme.typography.titleMedium)
        Text(
            "Work $workId · chapter $number" + (chapterId?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
