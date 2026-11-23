package io.github.joshitup.lantern.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomBarTest {
    @get:Rule val compose = createComposeRule()

    private fun reportedHeight(fontScale: Float): Dp {
        var height = 0.dp
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                BottomBar(libraryTab = false, onSearch = {}, onLibrary = {}, onHeight = { height = it })
            }
        }
        compose.waitForIdle()
        return height
    }

    @Test fun bottomBar_normalScale_is64() {
        assertEquals(BottomBarHeight, reportedHeight(1f))
    }

    @Test fun bottomBar_growsAndReportsHeight() {
        val h = reportedHeight(2f)
        assertTrue("reported $h", h > BottomBarHeight)
    }
}
