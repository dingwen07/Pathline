package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VisitNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingVisitSurvivesRestorationWaitsForDataAndCanBeHighlightedAgain() {
        val restoration = StateRestorationTester(compose)
        var visits by mutableStateOf(emptyList<Long>())
        var requestVisit: () -> Unit = {}
        var handled = 0
        restoration.setContent {
            var request by rememberSaveable(stateSaver = VisitFocusRequestSaver) {
                mutableStateOf<VisitFocusRequest?>(VisitFocusRequest(100, 45))
            }
            requestVisit = { request = VisitFocusRequest(100, 45) }
            val listState = rememberLazyListState()
            val highlighted = rememberVisitListHighlight(
                listState, request, visits.indexOf(request?.visitId),
            ) {
                assertEquals(100L, it.dayEpoch)
                assertEquals(45L, it.visitId)
                handled++
                request = null
            }
            PathlineTheme {
                LazyColumn(state = listState, modifier = Modifier.height(240.dp)) {
                    items(visits, key = { it }) { id ->
                        Text("Visit $id", Modifier.semantics { selected = id == highlighted }.padding(24.dp))
                    }
                }
            }
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(0, handled) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visits = (1L..50L).toList() }
        compose.mainClock.advanceTimeUntil(5_000) {
            handled == 1 && compose.onAllNodes(hasText("Visit 45") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Visit 45").assertIsDisplayed().assertIsSelected()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText("Visit 45").assertIsNotSelected()
        compose.runOnIdle { requestVisit() }
        compose.mainClock.advanceTimeUntil(5_000) {
            handled == 2 && compose.onAllNodes(hasText("Visit 45") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Visit 45").assertIsDisplayed().assertIsSelected()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText("Visit 45").assertIsNotSelected()
        compose.runOnIdle { assertEquals(2, handled) }
    }

    @Test fun visitHistoryRowClosesAndOpensItsExactDayAndVisit() {
        val visit = fixtureVisit().copy(id = 42, dayEpoch = 20_000)
        var dismissed = false
        var opened: Pair<Long, Long>? = null
        compose.setContent {
            PathlineTheme {
                PlaceDetailContent(
                    place = fixturePlace(), projectedPlace = null,
                    visits = listOf(visit), visitMarkers = emptyList(), mapProfileId = "test",
                    onDismiss = { dismissed = true },
                    onOpenVisit = { day, id -> opened = day to id },
                )
            }
        }
        compose.onNodeWithText(Format.date(visit.dayEpoch)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(true, dismissed)
            assertEquals(visit.dayEpoch to visit.id, opened)
        }
    }
}
