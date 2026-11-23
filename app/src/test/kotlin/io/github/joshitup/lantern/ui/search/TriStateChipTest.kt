package io.github.joshitup.lantern.ui.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriStateChipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun excludedChip_announcesExcluded() {
        compose.setContent { TriStateChip("M/M", TriState.EXCLUDED) {} }
        compose.onNodeWithContentDescription("M/M, excluded", useUnmergedTree = true).assertExists()
    }

    @Test fun includedChip_isSelected_notExcluded() {
        compose.setContent { TriStateChip("F/F", TriState.INCLUDED) {} }
        compose.onNodeWithText("F/F").assertIsSelected()
        compose.onAllNodesWithContentDescription("F/F, excluded", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun offChip_notSelected() {
        compose.setContent { TriStateChip("Gen", TriState.OFF) {} }
        compose.onNodeWithText("Gen").assertIsNotSelected()
        compose.onAllNodesWithContentDescription("Gen, excluded", useUnmergedTree = true).assertCountEquals(0)
    }
}
