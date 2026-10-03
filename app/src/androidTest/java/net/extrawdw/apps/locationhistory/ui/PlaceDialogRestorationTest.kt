package net.extrawdw.apps.locationhistory.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaceDialogRestorationTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun openAssignmentAndBothInputsRestore() {
        val restoration = StateRestorationTester(compose)
        lateinit var selected: MutableState<VisitEntity?>
        restoration.setContent {
            selected = rememberSaveable(stateSaver = VisitDialogSaver) { mutableStateOf(null) }
            PathlineTheme {
                Text("Places")
                val visit by selected
                visit?.let {
                    ConfirmPlaceSheet(
                        visit = it, localPlaces = listOf(fixturePlace()),
                        loadNearby = { _, _ -> emptyList() }, searchPlaces = { _, _, _ -> emptyList() },
                        mapsApiKeyConfigured = true, onConfirm = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.runOnIdle { selected.value = fixtureVisit() }
        input(R.string.search_places_label).performTextReplacement("Saved query")
        input(R.string.custom_place_name_label).performTextReplacement("Saved custom name")
        restoration.emulateSavedInstanceStateRestore()
        input(R.string.search_places_label).assertTextContains("Saved query")
        input(R.string.custom_place_name_label).assertTextContains("Saved custom name")
        compose.runOnIdle { assertEquals(fixtureVisit(), selected.value) }
    }

    @Test
    fun openEditorRestoresDraftAndPreservesOriginalGeometry() {
        val restoration = StateRestorationTester(compose)
        lateinit var selected: MutableState<PlaceEntity?>
        var savedName: String? = null
        var savedCenter: Wgs84Coordinate? = null
        restoration.setContent {
            selected = rememberSaveable(stateSaver = PlaceDialogSaver) { mutableStateOf(null) }
            PathlineTheme {
                Text("Places")
                val place by selected
                place?.let {
                    PlaceEditDialog(
                        place = it,
                        loadAnnotations = { _, _ -> AnnotationData("", emptyList(), emptyMap()) },
                        projectPlace = ::fixtureProjection,
                        projectCoordinate = { p -> GoogleMapCoordinate(p.latitude, p.longitude) },
                        normalizeMapCoordinate = { p -> Wgs84Coordinate(p.latitude, p.longitude) },
                        canUndoRepair = { false },
                        onSave = { name, _, lat, lon, _, _, _, _ ->
                            savedName = name
                            savedCenter = Wgs84Coordinate(lat, lon)
                        },
                        onSaveAnnotations = { _, _ -> }, onRepair = { false }, onUndoRepair = { false },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.runOnIdle { selected.value = fixturePlace() }
        input(R.string.field_name).performTextReplacement("Restored draft")
        restoration.emulateSavedInstanceStateRestore()
        input(R.string.field_name).assertTextContains("Restored draft")
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.runOnIdle {
            assertEquals("Restored draft", savedName)
            assertEquals(Wgs84Coordinate(1.3, 103.8), savedCenter)
            assertEquals(fixturePlace(), selected.value)
        }
    }

    private fun input(label: Int) = compose.onNode(hasSetTextAction() and hasText(context.getString(label)))
}
