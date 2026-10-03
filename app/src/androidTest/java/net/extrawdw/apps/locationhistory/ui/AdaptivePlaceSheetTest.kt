package net.extrawdw.apps.locationhistory.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.repo.PlaceChoice
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Verifies creating a custom place through Assign place. */
class AdaptivePlaceSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun customPlaceCanBeTypedAndSaved() {
        var confirmed: PlaceChoice? = null
        showAssignSheet { confirmed = it }
        val input = compose.onNode(
            hasSetTextAction() and hasText(context.getString(R.string.custom_place_name_label)),
        )
        input.performClick()
        input.performTextInput("Custom test place")
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.runOnIdle {
            assertEquals(PlaceChoice.NewNamed("Custom test place"), confirmed)
        }
    }

    private fun showAssignSheet(onConfirm: (PlaceChoice) -> Unit) {
        val places = List(6) { index ->
            fixturePlace().copy(id = index + 1L, name = "Saved place ${index + 1}")
        }
        compose.setContent {
            PathlineTheme {
                ConfirmPlaceSheet(
                    visit = fixtureVisit(),
                    localPlaces = places,
                    loadNearby = { _, _ -> emptyList() },
                    searchPlaces = { _, _, _ -> emptyList() },
                    mapsApiKeyConfigured = true,
                    onConfirm = onConfirm,
                    onDismiss = {},
                )
            }
        }
    }
}
