package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import android.view.KeyEvent
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.repo.DataManager
import net.extrawdw.apps.locationhistory.data.repo.DataDeletionSummary
import net.extrawdw.apps.locationhistory.data.repo.DataOperationLock
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class DataManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var viewModel: DataManagementViewModel
    private fun text(id: Int) = context.getString(id)

    @Before fun open() {
        System.loadLibrary("sqlcipher")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .setDriver(SQLCipherDriver("data-ui-test".toByteArray(), null, null)).build()
        viewModel = DataManagementViewModel(DataManager(db,
            AnnotationStore(db.tagDao(), db.annotationDao(), db.conceptDao()), TimelineWriteLock(), DataOperationLock()), db,
            dagger.Lazy { error("This fixture tests selective deletion, not reset") })
        runBlocking { db.placeDao().insert(fixturePlace()) }
    }

    @After fun close() { db.close() }

    @Test fun placeDeletionDefaultsAndOptionsSurviveRestorationAndCancelKeepsData() {
        val restoration = StateRestorationTester(compose)
        var closed = false
        restoration.setContent { PathlineTheme {
            DeletePlaceDialog(fixturePlace(), onDismiss = { closed = true }, onDeleted = { fail("not confirmed") }, viewModel)
        } }
        compose.onNodeWithText(text(R.string.data_delete_connected_trips)).assertIsOn()
        compose.onNodeWithText(text(R.string.data_delete_visit_days)).assertIsOff()
        compose.onNodeWithText(text(R.string.data_delete_connected_trips)).performClick()
        compose.onNodeWithText(text(R.string.data_delete_visit_days)).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.data_delete_connected_trips)).assertIsOff()
        compose.onNodeWithText(text(R.string.data_delete_visit_days)).assertIsOn()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertTrue(closed) }
        runBlocking { assertEquals(1, db.placeDao().count()) }
    }

    @Test fun placeDeletionRequiresConfirmationAndClosesAfterCompletion() {
        var closed by mutableStateOf(false)
        compose.setContent { PathlineTheme {
            if (!closed) DeletePlaceDialog(fixturePlace(), onDismiss = {}, onDeleted = { closed = true }, viewModel)
        } }
        runBlocking { assertEquals(1, db.placeDao().count()) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.data_delete_action)).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.data_summary_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.data_summary_annotations, 0, 0, 0, 0, 0, 0))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.data_delete_action)).performClick()
        compose.waitUntil(5_000) { closed }
        runBlocking { assertEquals(0, db.placeDao().count()) }
    }

    @Test fun managePlaceSearchAndSelectionSurviveRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PathlineTheme { ManageDataDialog({}, viewModel) } }
        compose.onNodeWithText(text(R.string.data_delete_place)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Test address")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(fixturePlace().name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(fixturePlace().name).performClick()
        compose.onNodeWithText(text(R.string.data_delete_connected_trips)).assertIsOn()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.data_delete_connected_trips)).assertIsOn()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Test address")
    }

    @Test fun dateRangeOpensPickerOnlyOnRequestAndPreservesOptionsOnRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PathlineTheme { ManageDataDialog({}, viewModel) } }
        compose.onNodeWithText(text(R.string.data_delete_range)).performClick()
        compose.onNodeWithText(text(R.string.action_apply)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.data_edge_cap)).assertIsDisplayed()
        compose.onNodeWithText(LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))).performClick()
        compose.onNodeWithText(text(R.string.action_apply)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.data_edge_cap)).assertIsSelected()
        compose.onNodeWithText(text(R.string.data_edge_whole)).performClick()
        compose.onNodeWithText(text(R.string.data_delete_empty_places)).performClick()
        compose.onNodeWithText(text(R.string.data_review_deletion)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.data_delete_action)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.data_delete_action)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onNodeWithText(text(R.string.data_edge_whole)).assertIsSelected()
        compose.onNodeWithText(text(R.string.data_delete_empty_places)).assertIsOn()
    }

    @Test fun systemBackDismissesEachDeletionSheetAndKeepsManageOpen() {
        var closed = false
        compose.setContent { PathlineTheme { ManageDataDialog({ closed = true }, viewModel) } }
        compose.onNodeWithText(text(R.string.data_delete_range)).performClick()
        compose.onNodeWithText(text(R.string.data_edge_cap)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.data_edge_cap)).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertFalse(closed) }
        compose.onNodeWithText(text(R.string.data_delete_place)).performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertFalse(closed) }
        compose.onNodeWithText(text(R.string.data_manage)).assertIsDisplayed()
    }

    @Test fun sharedRangePickerCapsFutureInitialDatesToToday() {
        val today = LocalDate.now().toEpochDay()
        var selection: Pair<Long, Long>? = null
        compose.setContent { PathlineTheme {
            DateRangePickerDialog(today + 1, today + 3, onConfirm = { start, end -> selection = start to end },
                onDismiss = {})
        } }
        compose.onNodeWithText(text(R.string.action_plot)).performClick()
        compose.waitUntil(5_000) { selection != null }
        assertEquals(today to today, selection)
    }

    @Test fun cancelledPredictiveBackKeepsOptionsAndCommittedBackDismissesWithoutDeleting() {
        var closed by mutableStateOf(false)
        compose.setContent { PathlineTheme {
            if (!closed) DeletePlaceDialog(fixturePlace(), onDismiss = { closed = true },
                onDeleted = { fail("Back must not delete") }, viewModel)
        } }
        compose.onNodeWithText(text(R.string.data_delete_connected_trips)).performClick()
        // ComponentDialog owns a separate dispatcher from the activity underneath it.
        val dispatcher = compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().last()
                .findViewTreeOnBackPressedDispatcherOwner()!!.onBackPressedDispatcher
        }
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.onNodeWithText(text(R.string.data_delete_connected_trips)).assertIsOff()
        compose.runOnIdle { assertFalse(closed) }
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitUntil(5_000) { closed }
        runBlocking { assertEquals(1, db.placeDao().count()) }
    }

    @Test fun optionRecalculationKeepsPreviousEstimateVisibleAndDisablesStaleConfirmation() {
        var selected by mutableStateOf(false)
        val recomputed = CompletableDeferred<DataDeletionSummary>()
        compose.setContent { PathlineTheme {
            DataDeletionConfirmation("Delete", "Preview", viewModel, onConfirm = {}, onDeleted = {}, onDismiss = {},
                previewKey = selected, loadPreview = {
                    if (selected) recomputed.await() else DataDeletionSummary(10, 1, 0, 1)
                }) { enabled ->
                Checkbox(selected, { selected = it }, enabled = enabled, modifier = Modifier.testTag("option"))
            }
        } }
        val before = context.getString(R.string.data_summary_counts, 10, 1, 0, 1)
        val after = context.getString(R.string.data_summary_counts, 20, 1, 2, 1)
        val delete = hasText(text(R.string.data_delete_action)) and hasClickAction()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(before).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("option").performClick()
        compose.onNodeWithText(before).assertExists()
        compose.onNode(delete).assertIsNotEnabled()
        recomputed.complete(DataDeletionSummary(20, 1, 2, 1))
        compose.waitUntil(5_000) { compose.onAllNodesWithText(after).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(delete).assertIsEnabled()
    }
}
