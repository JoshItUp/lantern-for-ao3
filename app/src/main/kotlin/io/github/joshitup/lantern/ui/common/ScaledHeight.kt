package io.github.joshitup.lantern.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [base] (the height at normal font size) plus however much one line of [label] has grown with the system
 * font size: a bar of fixed-size icons and one line of text keeps its look at 100% and never clips its label.
 */
@Composable
fun heightForLabel(base: Dp, label: TextStyle): Dp {
    val line = label.lineHeight.takeIf { it.isSp } ?: label.fontSize
    val grown = with(LocalDensity.current) { line.toDp() }
    return base + (grown - line.value.dp).coerceAtLeast(0.dp)
}
