package io.github.joshitup.lantern.ui.reader

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
 * for an item that didn't split; the start of its first piece for one that did (the offset can't be placed
 * without measuring). An index past the end lands on the last item.
 */
fun legacyRestore(original: ChapterContent, blockIndex: Int, offsetPx: Int): Pair<Int, Int> {
    val counts = legacyItemCounts(original)
    val i = blockIndex.coerceIn(0, counts.lastIndex)
    val offset = if (i == blockIndex && counts[i] == 1) offsetPx else 0
    return counts.subList(0, i).sum() to offset
}

/**
 * One entry per item of the unsplit reader list, in [ReaderScreen]'s order and under its conditions (change
 * both together): how many items it is now.
 */
internal fun legacyItemCounts(original: ChapterContent): List<Int> = buildList {
    fun blocks(list: List<Block>) = list.forEach { add(BlankLines.split(it).size) }
    if (original.ref.number == 1 || original.summary.isNotEmpty()) {
        add(1) // work title
        blocks(original.summary)
        add(1) // header rule
    }
    if (original.ref.title.isNotBlank()) add(1) // chapter title
    blocks(original.beginNotes)
    if (original.beginNotes.isNotEmpty()) add(1) // begin rule
    blocks(original.body)
    if (original.endNotes.isNotEmpty()) add(1) // end rule
    blocks(original.endNotes)
    add(1) // footer
}
