package io.github.joshitup.lantern.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SidePaddingTest {
    @Test fun narrowNoCutout_isBase() {
        assertEquals(Sides(20.dp, 20.dp), sidePadding(411.dp, 680.dp, 20.dp, 0.dp, 0.dp))
    }

    @Test fun wide_centresTheColumn() {
        assertEquals(Sides(160.dp, 160.dp), sidePadding(1000.dp, 680.dp, 20.dp, 0.dp, 0.dp))
    }

    @Test fun cutoutOneSide_onlyThatSide() {
        assertEquals(Sides(60.dp, 60.dp), sidePadding(800.dp, 680.dp, 20.dp, 40.dp, 0.dp))
        assertEquals(Sides(97.5.dp, 97.5.dp), sidePadding(915.dp, 720.dp, 12.dp, 48.dp, 0.dp))
        assertEquals(Sides(60.dp, 20.dp), sidePadding(700.dp, 680.dp, 20.dp, 40.dp, 0.dp))
        assertEquals(Sides(20.dp, 60.dp), sidePadding(700.dp, 680.dp, 20.dp, 0.dp, 40.dp))
    }

    @Test fun readerContentWidth_matchesPadding() {
        assertEquals(1360, readerContentWidthPx(viewportPx = 2000, density = 2f, cutoutStartPx = 0, cutoutEndPx = 0))
        assertEquals(742, readerContentWidthPx(viewportPx = 822, density = 2f, cutoutStartPx = 0, cutoutEndPx = 0))
        // Landscape phone, cutout on the left: 900 dp wide, 30 dp cutout → sides 110/110 → 680 dp.
        assertEquals(1360, readerContentWidthPx(viewportPx = 1800, density = 2f, cutoutStartPx = 60, cutoutEndPx = 0))
        // 720 dp wide, 30 dp cutout → sides 50/20 → 650 dp.
        assertEquals(1300, readerContentWidthPx(viewportPx = 1440, density = 2f, cutoutStartPx = 60, cutoutEndPx = 0))
    }
}
