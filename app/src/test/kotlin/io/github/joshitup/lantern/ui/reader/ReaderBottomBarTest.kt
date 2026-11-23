package io.github.joshitup.lantern.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderBottomBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wrappedSignInLabel_staysInsideTheBar() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    ReaderBottomBar { TextButton(onClick = {}) { Text("Sign in for kudos & bookmarks") } }
                }
            }
        }
        // A label squeezed into too short a bar is cut off inside the text: ask its layout.
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Sign in for kudos & bookmarks").fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse("label clipped: ${layouts.single().size}", layouts.single().didOverflowHeight)
    }
}
