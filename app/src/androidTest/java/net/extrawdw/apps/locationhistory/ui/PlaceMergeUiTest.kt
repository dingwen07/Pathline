package net.extrawdw.apps.locationhistory.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.repo.placeMergePairKey
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlaceMergeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = fixturePlace().copy(name = "Source", address = "Source street")
    private val destination = fixturePlace().copy(id = 2, name = "Destination", address = "Destination street")

    @Test
    fun expandedPickerKeepsSearchUsableWithLongListAndKeyboard() {
        val places = (2L..40L).map { destination.copy(id = it, name = "Saved place $it") }
        var mergedId: Long? = null
        compose.setContent {
            PathlineTheme {
                PlaceMergePickerSheet(source, places, onMerge = { mergedId = it; true },
                    onMerged = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        captureIfRequested("picker")
        compose.onNode(hasSetTextAction()).performClick().performTextReplacement("Saved place 40")
        val result = hasText("Saved place 40") and !hasSetTextAction()
        compose.onNode(result).assertIsDisplayed()
        captureIfRequested("picker-keyboard")
        compose.onNode(result).performClick()
        confirmMerge()
        compose.runOnIdle { assertEquals(40L, mergedId) }
    }

    @Test
    fun pickerSearchRestoresAndMergesIntoSelectedDestination() {
        val restoration = StateRestorationTester(compose)
        var mergedId: Long? = null
        var closed = false
        restoration.setContent {
            PathlineTheme {
                PlaceMergePickerSheet(source, listOf(source, destination),
                    onMerge = { mergedId = it; true }, onMerged = { closed = true }, onDismiss = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Destination street")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("Destination street")
        compose.onNodeWithText("Destination", substring = false).performClick()
        confirmMerge()
        compose.runOnIdle { assertEquals(2L, mergedId); assertEquals(true, closed) }
    }

    @Test
    fun failedMergeShowsErrorAndDoesNotClosePicker() {
        var closed = false
        compose.setContent {
            PathlineTheme {
                PlaceMergePickerSheet(source, listOf(source, destination), onMerge = { false },
                    onMerged = { closed = true }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Destination").performClick()
        confirmMerge()
        compose.onNodeWithText(context.getString(R.string.place_merge_failed)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(false, closed) }
    }

    @Test
    fun ignoreRemovesOnlySuggestionAndManualPickerStillOffersPlace() {
        var ignored by mutableStateOf(emptySet<String>())
        var showCandidates by mutableStateOf(true)
        compose.setContent {
            PathlineTheme {
                if (showCandidates) {
                    PlaceDuplicatesDialog(listOf(source, destination), ignored, projectPlace = { null },
                        onMerge = { _, _ -> true },
                        onIgnore = { a, b -> ignored = ignored + placeMergePairKey(a, b) }, onDismiss = {})
                } else {
                    PlaceMergePickerSheet(source, listOf(source, destination), onMerge = { true },
                        onMerged = {}, onDismiss = {})
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.place_merge_ignore)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.place_merge_ignore)).performClick()
        compose.waitUntil(5_000) { ignored.isNotEmpty() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Destination").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Destination").assertDoesNotExist()
        compose.runOnIdle { showCandidates = false }
        compose.onNodeWithText("Destination").assertIsDisplayed()
    }

    @Test
    fun comparisonKeepsSelectedPlaceAfterStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var merged: Pair<Long, Long>? = null
        restoration.setContent {
            PathlineTheme {
                PlaceMergeComparisonDialog(source, destination, projectPlace = ::fixtureProjection,
                    onMerge = { from, to -> merged = from to to; true }, onIgnore = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Source street").assertIsDisplayed()
        captureIfRequested("comparison")
        compose.onAllNodesWithText(context.getString(R.string.place_merge_keep))[1].performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        confirmMerge()
        compose.runOnIdle { assertEquals(1L to 2L, merged) }
    }

    private fun confirmMerge() {
        compose.onNode(hasText(context.getString(R.string.place_merge_action)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /** Optional visual artifacts; behavioral assertions do not depend on device geometry. */
    private fun captureIfRequested(name: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("placeMergeScreenshots") ?: return
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "$prefix-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
