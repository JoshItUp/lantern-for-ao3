package io.github.joshitup.lantern.ui.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import io.github.joshitup.lantern.ao3.model.Block
import io.github.joshitup.lantern.ao3.parse.BlankLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** [measureInBlock] against real text layout: the line it finds is the one the old offset showed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InBlockMeasureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offsetInSecondPiece_landsInSecondPiece() {
        val text = "first paragraph\n\nsecond paragraph line\n\nthird"
        val block = Block.Text(text, emptyList())
        val pieces = BlankLines.ranges(text)
        lateinit var lineHeight: Pair<Int, Int>
        lateinit var placed: Pair<Int, Int>
        compose.setContent {
            val measurer = rememberTextMeasurer()
            val style = TextStyle(fontSize = 20.sp)
            val whole = measurer.measure(text, style)
            // The second piece starts on line 2 (line 1 is the blank line); aim a few px into it.
            val top = whole.getLineTop(2).toInt()
            lineHeight = top to whole.getLineBottom(2).toInt()
            placed = measureInBlock(InBlockRestore(5, block, pieces, top + 3, note = false), measurer, style, widthPx = 100_000, linkColor = androidx.compose.ui.graphics.Color.Blue)
        }
        compose.waitForIdle()
        assertTrue("layout measured: $lineHeight", lineHeight.second > lineHeight.first)
        assertEquals(6 to 3, placed)
    }
}
