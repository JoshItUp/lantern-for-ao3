package io.github.joshitup.lantern.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.Span

/** Builds a styled string from converted AO3 text and its spans. Out-of-range spans are clamped. */
fun annotate(text: String, spans: List<Span>, linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val n = text.length
    for (s in spans) {
        val start = s.start.coerceIn(0, n)
        val end = s.end.coerceIn(start, n)
        if (start == end) continue
        when (s) {
            is Span.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            is Span.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            is Span.Underline -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
            is Span.Strike -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            is Span.Link -> addLink(
                LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                start, end,
            )
        }
    }
}

/**
 * Renders converted AO3 blocks (summaries, notes; the reader builds on this).
 * Blocks must already be converted; nothing here parses HTML.
 */
@Composable
fun BlockText(
    blocks: List<Block>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
    blockSpacing: Dp = 8.dp,
) {
    if (blocks.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(blockSpacing)) {
        for (b in blocks) BlockItem(b, style, color)
    }
}

@Composable
fun BlockItem(block: Block, style: TextStyle, color: Color) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (block) {
        is Block.Text -> {
            val text = remember(block, linkColor) { annotate(block.text, block.spans, linkColor) }
            Text(
                text, style = style, color = color, modifier = Modifier.fillMaxWidth(),
                textAlign = if (block.center) TextAlign.Center else TextAlign.Start,
            )
        }
        is Block.Heading -> Text(
            block.text, color = color, fontWeight = FontWeight.SemiBold,
            style = if (block.level <= 2 && style.fontSize.isSpecified) style.copy(fontSize = style.fontSize * 1.2f) else style,
        )
        is Block.Quote -> {
            val text = remember(block, linkColor) { annotate(block.text, block.spans, linkColor) }
            Row(Modifier.height(IntrinsicSize.Min)) {
                Spacer(Modifier.width(3.dp).fillMaxHeight().background(color.copy(alpha = 0.3f)))
                Text(text, style = style, color = color.copy(alpha = 0.85f), modifier = Modifier.padding(start = 10.dp))
            }
        }
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = color.copy(alpha = 0.2f))
    }
}
