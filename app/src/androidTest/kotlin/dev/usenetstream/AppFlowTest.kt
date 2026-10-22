package dev.usenetstream

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app on the phone against the real indexer (needs server + indexer configured).
 * Uses Big Buck Bunny and Pioneer One: both free-licensed. Leaves filters and My List as it found them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(text: String, timeoutMs: Long = 25_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String, timeoutMs: Long = 10_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }

    /** Never the search box, which may hold the same words. */
    private fun clickText(text: String) {
        // Sheets slide in; tap once they have settled.
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true) and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(350)
        rule.onAllNodes(hasText(text, substring = true) and hasClickAction() and !hasSetTextAction()).onFirst().performClick()
    }

    /** A search result card: poster label + its year line (Home tiles never show the year). */
    private fun card(title: String, year: String) =
        androidx.compose.ui.test.hasContentDescription(title) and hasText(year, substring = true) and hasClickAction()

    private fun openResultVisible(title: String, year: String) =
        rule.waitUntil(15_000) { rule.onAllNodes(card(title, year)).fetchSemanticsNodes().isNotEmpty() }

    private fun openResult(title: String, year: String) {
        openResultVisible(title, year)
        rule.onAllNodes(card(title, year)).onFirst().performClick()
    }

    /** Lazy lists only compose what's on screen: scroll the page until [text] exists, then wait for it. */
    private fun scrollTo(text: String, timeoutMs: Long = 25_000) {
        rule.waitUntil(timeoutMs) {
            runCatching {
                rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
            }.isSuccess
        }
    }

    private fun tab(name: String) = rule.onNodeWithContentDescription(name).performClick()

    /** First launch shows the beta notice; accept it so the flows start on Home. */
    @org.junit.Before
    fun acceptDisclaimer() {
        rule.waitForIdle()
        val ok = rule.onAllNodesWithText("I understand").fetchSemanticsNodes()
        if (ok.isNotEmpty()) rule.onNodeWithText("I understand").performClick()
    }

    private fun back() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    private fun search(q: String) {
        tab("Search")
        rule.onNode(hasSetTextAction()).performTextReplacement(q)
        rule.onNode(hasSetTextAction()).performImeAction()
    }

    @Test
    fun bottomNavSwitchesTabsAndBackReturnsHome() {
        waitText("Grabbit")
        tab("Search"); waitText("Movies, shows, releases")
        tab("My List"); rule.waitUntil(10_000) {
            // Empty state, or the grid's "My List" header next to the nav label.
            rule.onAllNodesWithText("Your list is empty").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("My List").fetchSemanticsNodes().size >= 2
        }
        tab("Settings"); waitText("Playback"); waitText("This phone decodes")
        back(); waitText("Grabbit")
    }

    @Test
    fun searchToMovieDetailsFilterReleasesAndBack() {
        search("Big.Buck.Bunny")
        openResult("Big Buck Bunny", "2008")
        scrollTo("Releases")
        scrollTo("BEST")

        clickText("Filters"); waitText("Filter releases")
        // A filter chip (selectable), not a release row that merely has a "1080p" badge.
        rule.waitForIdle()
        rule.onAllNodes(hasText("1080p") and androidx.compose.ui.test.isSelectable()).onFirst().performClick()
        clickText("Show ") // "Show N releases"
        scrollTo("shown")

        // Undo the sticky filter.
        clickText("Filters"); waitText("Filter releases")
        clickText("Reset")
        clickText("Show ")
        scrollTo("Releases")
        gone("Filter releases")

        back(); openResultVisible("Big Buck Bunny", "2008")
        back(); waitText("Grabbit")
    }

    @Test
    fun myListAddAndRemove() {
        search("Big.Buck.Bunny")
        openResult("Big Buck Bunny", "2008")
        waitText("Big Buck Bunny")
        val wasIn = rule.onAllNodesWithContentDescription("Remove from My List").fetchSemanticsNodes().isNotEmpty()
        if (wasIn) rule.onAllNodesWithContentDescription("Remove from My List").onFirst().performClick().also { waitText("Removed from My List") }

        rule.onAllNodesWithContentDescription("Add to My List").onFirst().performClick()
        waitText("Added to My List")
        back(); back()
        tab("My List")
        // My List cards show the year too.
        openResult("Big Buck Bunny", "2008")
        rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription("Remove from My List").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription("Remove from My List").onFirst().performClick()
        waitText("Removed from My List")
        if (wasIn) rule.onAllNodesWithContentDescription("Add to My List").onFirst().performClick()
        back()
    }

    @Test
    fun tvShowPageListsSeasonsAndEpisodes() {
        search("Pioneer One")
        openResult("Pioneer One", "2010")
        waitText("Season 1", 30_000)
        waitText("1. ", 30_000) // first episode row
        rule.waitUntil(30_000) {
            listOf("Play S01", "Resume S01", "Next S01", "Watch again").any { rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        gone("Finding releases", 30_000)
        back(); openResultVisible("Pioneer One", "2010")
    }

    @Test
    fun appearanceChoiceSticksAcrossRestart() {
        tab("Settings"); waitText("Appearance")
        fun chip(label: String) = rule.onAllNodes(hasText(label) and androidx.compose.ui.test.isSelectable()).onFirst()
        chip("Light").performClick()
        rule.waitForIdle()
        rule.activityRule.scenario.recreate()
        tab("Settings"); waitText("Appearance")
        chip("Light").assert(androidx.compose.ui.test.isSelected())
        chip("System").performClick() // leave the phone on its default
        rule.waitForIdle()
        chip("System").assert(androidx.compose.ui.test.isSelected())
    }
}
