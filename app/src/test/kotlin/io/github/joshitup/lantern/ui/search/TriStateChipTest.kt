package io.github.joshitup.lantern.ui.search

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriStateChipTest {
    @get:Rule val compose = createComposeRule()

    private fun state(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)

    @Test fun excludedChip_announcesExcluded() {
        compose.setContent { TriStateChip("M/M", TriState.EXCLUDED) {} }
        compose.onNode(hasText("M/M") and state("Excluded")).assertExists()
    }

    @Test fun includedChip_announcesIncluded() {
        compose.setContent { TriStateChip("F/F", TriState.INCLUDED) {} }
        compose.onNode(hasText("F/F") and state("Included")).assertExists()
    }

    @Test fun offChip_announcesNotSelected() {
        compose.setContent { TriStateChip("Gen", TriState.OFF) {} }
        compose.onNode(hasText("Gen") and state("Not selected")).assertExists()
    }
}

/** Names in an Exclude field read as excluded and look like the excluded toggles. */
@RunWith(RobolectricTestRunner::class)
class ExcludedTagChipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun excludedNames_announcedAsExcluded() {
        compose.setContent { TagChips(listOf("Synthetic Character"), excluded = true) {} }
        compose.onAllNodesWithContentDescription("Synthetic Character, excluded. Remove", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun includedNames_notAnnouncedAsExcluded() {
        compose.setContent { TagChips(listOf("Synthetic Character"), excluded = false) {} }
        compose.onAllNodesWithContentDescription("Synthetic Character, excluded. Remove", useUnmergedTree = true).assertCountEquals(0)
    }
}
