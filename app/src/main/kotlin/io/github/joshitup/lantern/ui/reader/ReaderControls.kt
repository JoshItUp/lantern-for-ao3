package io.github.joshitup.lantern.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.joshitup.lantern.ao3.model.ChapterRef
import io.github.joshitup.lantern.data.ReaderFont
import io.github.joshitup.lantern.data.db.DownloadEntity
import io.github.joshitup.lantern.ui.common.AppIcons
import kotlin.math.roundToInt

private val MenuIcon: ImageVector by lazy {
    ImageVector.Builder("Menu", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 6f); horizontalLineTo(21f); verticalLineTo(8f); horizontalLineTo(3f); close()
        moveTo(3f, 11f); horizontalLineTo(21f); verticalLineTo(13f); horizontalLineTo(3f); close()
        moveTo(3f, 16f); horizontalLineTo(21f); verticalLineTo(18f); horizontalLineTo(3f); close()
    }.build()
}

fun chapterLabel(chapters: List<ChapterRef>, index: Int, listLoaded: Boolean): String {
    val ref = chapters.getOrNull(index) ?: return ""
    return if (listLoaded) "Ch ${ref.number} / ${chapters.size}" else "Ch ${ref.number}"
}

private fun ChapterRef.display(): String = if (title.isBlank()) "Chapter $number" else "$number. $title"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    label: String,
    onMenu: () -> Unit,
    onFont: () -> Unit,
    download: DownloadEntity?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = { Text(label, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onMenu) { Icon(MenuIcon, contentDescription = "Chapters") }
        },
        actions = {
            TextButton(onClick = onFont) { Text("Aa", fontSize = 18.sp) }
            ReaderOverflowMenu(download, onDownload)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
    )
}

/** The ⋯ menu: for now just Download (works signed out too), labelled and enabled as the footer's button. */
@Composable
private fun ReaderOverflowMenu(download: DownloadEntity?, onDownload: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AppIcons.More, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val ui = downloadUi(download, showNewChapters = true)
            val supporting = ui.failure ?: ui.newLine
            DropdownMenuItem(
                text = {
                    Column {
                        Text(ui.label)
                        if (supporting != null) {
                            Text(
                                supporting, style = MaterialTheme.typography.bodySmall,
                                color = if (ui.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(AppIcons.Download, contentDescription = null) },
                enabled = ui.enabled,
                colors = MenuDefaults.itemColors(
                    disabledTextColor = if (ui.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLeadingIconColor = if (ui.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                onClick = {
                    open = false
                    onDownload()
                },
            )
        }
    }
}

@Composable
fun ChapterDrawer(chapters: List<ChapterRef>, current: Int, onSelect: (Int) -> Unit) {
    ModalDrawerSheet {
        Text(
            "Chapters", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 12.dp),
        )
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
        LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 12.dp)) {
            itemsIndexed(chapters, key = { i, _ -> i }) { i, ref ->
                NavigationDrawerItem(
                    label = { Text(ref.display(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    selected = i == current,
                    onClick = { onSelect(i) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSheet(
    sizeSp: Int,
    font: ReaderFont,
    onSize: (Int) -> Unit,
    onFont: (ReaderFont) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            // Local value keeps the thumb smooth; each whole-sp step is pushed to the ViewModel.
            var value by remember { mutableFloatStateOf(sizeSp.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${value.roundToInt()} sp", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = value,
                onValueChange = {
                    value = it
                    val sp = it.roundToInt()
                    if (sp != sizeSp) onSize(sp)
                },
                valueRange = ReaderViewModel.MIN_FONT_SP.toFloat()..ReaderViewModel.MAX_FONT_SP.toFloat(),
                steps = ReaderViewModel.MAX_FONT_SP - ReaderViewModel.MIN_FONT_SP - 1,
            )
            Text("Font", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in ReaderFont.entries) {
                    FilterChip(
                        selected = f == font,
                        onClick = { onFont(f) },
                        label = { Text(f.label(), fontFamily = f.family()) },
                    )
                }
            }
        }
    }
}
