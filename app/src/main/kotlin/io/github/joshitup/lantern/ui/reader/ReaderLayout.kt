package io.github.joshitup.lantern.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import io.github.joshitup.lantern.ui.common.annotate
import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.model.ChapterContent
import io.github.joshitup.lantern.ao3.parse.BlankLines

/** The chapter as the reader lays it out: every blank-line-separated paragraph its own block. */
fun ChapterContent.splitForReader(): ChapterContent = copy(
    summary = BlankLines.split(summary),
    beginNotes = BlankLines.split(beginNotes),
    body = BlankLines.split(body),
    endNotes = BlankLines.split(endNotes),
)

/**
 * A place saved before blank-line splitting ([io.github.joshitup.lantern.data.db.LAYOUT_LEGACY]), as an item
 * index and pixel offset into the split layout of the same chapter. [original] is the unsplit content. Exact
 * for an item that didn't split; the start of its first piece for one that did ([legacyInBlock] lets the
 * screen place it inside). An index past the end lands on the last item.
 */
fun legacyRestore(original: ChapterContent, blockIndex: Int, offsetPx: Int): Pair<Int, Int> {
    val counts = legacyItems(original).map { it.count }
    val i = blockIndex.coerceIn(0, counts.lastIndex)
    val offset = if (i == blockIndex && counts[i] == 1) offsetPx else 0
    return counts.subList(0, i).sum() to offset
}

/** An item of the unsplit reader list: how many items it is now, and its block (null for title, rules, footer). */
internal class LegacyItem(val count: Int, val block: Block?, val note: Boolean)

/** The unsplit reader list, in [ReaderScreen]'s order and under its conditions (change both together). */
internal fun legacyItems(original: ChapterContent): List<LegacyItem> = buildList {
    fun one() = add(LegacyItem(1, null, false))
    fun blocks(list: List<Block>, note: Boolean) = list.forEach { add(LegacyItem(BlankLines.split(it).size, it, note)) }
    if (original.ref.number == 1 || original.summary.isNotEmpty()) {
        one() // work title
        blocks(original.summary, note = true)
        one() // header rule
    }
    if (original.ref.title.isNotBlank()) one() // chapter title
    blocks(original.beginNotes, note = true)
    if (original.beginNotes.isNotEmpty()) one() // begin rule
    blocks(original.body, note = false)
    if (original.endNotes.isNotEmpty()) one() // end rule
    blocks(original.endNotes, note = true)
    one() // footer
}

/**
 * A legacy place inside a block that now splits into several items, starting at [firstItem]. Only the screen
 * can place it: it lays out [block] whole (body style, or the notes style when [note]) at the list's width,
 * then [place] picks the piece and the offset in it. [pieces] are the pieces' ranges in [block]'s text.
 */
@Immutable
data class InBlockRestore(val firstItem: Int, val block: Block, val pieces: List<IntRange>, val offsetPx: Int, val note: Boolean)

/** The questions [InBlockRestore.place] asks of the whole block's text layout, in pixels. */
interface LineLookup {
    fun lineAt(y: Int): Int
    fun lineTop(line: Int): Int
    fun lineBottom(line: Int): Int
    fun lineStart(line: Int): Int
    fun lineOf(offset: Int): Int
}

/**
 * The item and offset that show what was at [InBlockRestore.offsetPx] in the whole block. Exact: each piece
 * starts a line of its own, so it wraps the way it did inside the block. A blank line gives the next piece's
 * top; an offset past the text stays on its last line.
 */
fun InBlockRestore.place(lines: LineLookup): Pair<Int, Int> {
    val line = lines.lineAt(offsetPx.coerceAtLeast(0))
    val y = offsetPx.coerceIn(0, lines.lineBottom(line) - 1)
    val ch = lines.lineStart(line)
    val k = pieces.indexOfLast { it.first <= ch }
    if (k < 0) return firstItem to 0
    if (ch > pieces[k].last && k + 1 < pieces.size) return firstItem + k + 1 to 0
    return firstItem + k to (y - lines.lineTop(lines.lineOf(pieces[k].first))).coerceAtLeast(0)
}

/** The [InBlockRestore] for a legacy place inside a block that now splits; null when its item didn't split. */
fun legacyInBlock(original: ChapterContent, blockIndex: Int, offsetPx: Int): InBlockRestore? {
    val items = legacyItems(original)
    val item = items.getOrNull(blockIndex) ?: return null
    val block = item.block ?: return null
    val text = when (block) {
        is Block.Text -> block.text
        is Block.Quote -> block.text
        else -> return null
    }
    val pieces = BlankLines.ranges(text).takeIf { it.size > 1 } ?: return null
    return InBlockRestore(items.subList(0, blockIndex).sumOf { it.count }, block, pieces, offsetPx, item.note)
}

/**
 * Lays out [r]'s whole block as the reader drew it before splitting (same [style], item width [widthPx]; a
 * quote's text sits [quoteInsetPx] in from its bar) and places the saved offset in its pieces.
 */
fun measureInBlock(
    r: InBlockRestore,
    measurer: TextMeasurer,
    style: TextStyle,
    widthPx: Int,
    linkColor: Color,
    quoteInsetPx: Int = 0,
): Pair<Int, Int> {
    val (text, spans, inset) = when (val b = r.block) {
        is Block.Text -> Triple(b.text, b.spans, 0)
        is Block.Quote -> Triple(b.text, b.spans, quoteInsetPx)
        else -> return r.firstItem to 0
    }
    val layout = measurer.measure(
        annotate(text, spans, linkColor), style,
        constraints = Constraints(maxWidth = (widthPx - inset).coerceAtLeast(1)),
    )
    return r.place(layout.lines())
}

private fun TextLayoutResult.lines() = object : LineLookup {
    override fun lineAt(y: Int) = getLineForVerticalPosition(y.toFloat())
    override fun lineTop(line: Int) = getLineTop(line).toInt()
    override fun lineBottom(line: Int) = getLineBottom(line).toInt()
    override fun lineStart(line: Int) = getLineStart(line)
    override fun lineOf(offset: Int) = getLineForOffset(offset)
}
