package io.github.joshitup.lantern.ui.common

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.roundToInt

/** The reader's text column: at most this wide, centred on wider screens. */
val READER_MAX_WIDTH = 680.dp

/** Card lists (Search, Library, work lists): at most this wide, centred on wider screens. */
val LIST_MAX_WIDTH = 720.dp

/** The reader's side padding on a phone. */
val READER_BASE_PADDING = 20.dp

data class Sides(val start: Dp, val end: Dp)

/**
 * Side padding for content at most [maxContent] wide in [available]: centred when there is room, never less
 * than [base], and clear of a camera cutout on either side ([cutoutStart], [cutoutEnd]: a cutout or a side navigation bar).
 */
fun sidePadding(available: Dp, maxContent: Dp, base: Dp, cutoutStart: Dp, cutoutEnd: Dp): Sides {
    val centred = (available - maxContent) / 2
    return Sides(max(base + cutoutStart, centred), max(base + cutoutEnd, centred))
}

/** The width the reader lays its text out at, in px, for a list [viewportPx] wide ([sidePadding] in px). */
fun readerContentWidthPx(viewportPx: Int, density: Float, cutoutStartPx: Int, cutoutEndPx: Int): Int {
    val s = sidePadding((viewportPx / density).dp, READER_MAX_WIDTH, READER_BASE_PADDING, (cutoutStartPx / density).dp, (cutoutEndPx / density).dp)
    return (viewportPx - (s.start.value + s.end.value) * density).roundToInt()
}

/** [sidePadding] for the current window, clear of a camera cutout or a side navigation bar. */
@Composable
fun rememberSidePadding(maxContent: Dp, base: Dp): Sides {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val width = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val (start, end) = sideInsetsPx(direction)
    return with(density) { sidePadding(width, maxContent, base, start.toDp(), end.toDp()) }
}

/** What the content must clear at the sides, in px (start, end): a camera cutout, or a navigation bar on the side in landscape. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun sideInsetsPx(direction: LayoutDirection = LocalLayoutDirection.current): Pair<Int, Int> {
    val density = LocalDensity.current
    val cutout = WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility)
    val left = cutout.getLeft(density, direction)
    val right = cutout.getRight(density, direction)
    return if (direction == LayoutDirection.Ltr) left to right else right to left
}

/** The card lists' side padding on a phone. */
val LIST_BASE_PADDING = 12.dp

/** [rememberSidePadding] for a card list. */
@Composable
fun rememberListSides(): Sides = rememberSidePadding(LIST_MAX_WIDTH, LIST_BASE_PADDING)
