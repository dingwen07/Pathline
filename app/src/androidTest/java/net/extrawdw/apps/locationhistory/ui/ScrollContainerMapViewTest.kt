package net.extrawdw.apps.locationhistory.ui

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.GoogleMapOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScrollContainerMapViewTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var scroll: ScrollState
    private val mapEvents = mutableListOf<Int>()

    @Before
    fun setUp() {
        compose.setContent {
            scroll = rememberScrollState()
            Column(
                Modifier.fillMaxWidth().height(360.dp)
                    .testTag("form")
                    .verticalScroll(scroll),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(180.dp).testTag("map"),
                    factory = { context ->
                        ScrollContainerMapView(context, GoogleMapOptions()).apply {
                            // Exercise the real MapView/Compose touch boundary without map tiles,
                            // API credentials, or a live renderer. The child stands in for the SDK.
                            addView(
                                View(context).apply {
                                    setOnTouchListener { _, event ->
                                        mapEvents += event.actionMasked
                                        true
                                    }
                                },
                                FrameLayout.LayoutParams(-1, -1),
                            )
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().height(800.dp))
            }
        }
    }

    @Test
    fun mapDragReceivesMovesWithoutScrollingTheForm() {
        compose.onNodeWithTag("map").performTouchInput { swipeUp() }

        compose.runOnIdle {
            assertEquals(0, scroll.value)
            assertTrue(MotionEvent.ACTION_MOVE in mapEvents)
            assertTrue(MotionEvent.ACTION_UP in mapEvents)
            assertFalse(MotionEvent.ACTION_CANCEL in mapEvents)
        }
        assertFormStillScrolls()
    }

    @Test
    fun multiTouchStaysOnMapUntilTheLastPointerIsReleasedOrCancelled() {
        compose.onNodeWithTag("map").performTouchInput {
            down(0, Offset(centerX - 20f, centerY))
            down(1, Offset(centerX + 20f, centerY))
            moveTo(0, Offset(centerX - 40f, centerY - 40f))
            moveTo(1, Offset(centerX + 40f, centerY + 40f))
            up(1)
            moveTo(0, Offset(centerX - 40f, centerY - 80f))
            cancel()
        }

        compose.runOnIdle {
            assertEquals(0, scroll.value)
            assertTrue(MotionEvent.ACTION_POINTER_DOWN in mapEvents)
            assertTrue(MotionEvent.ACTION_POINTER_UP in mapEvents)
            assertTrue(MotionEvent.ACTION_CANCEL in mapEvents)
        }
        assertFormStillScrolls()
    }

    private fun assertFormStillScrolls() {
        compose.onNodeWithTag("form").performTouchInput {
            // Start below the map so this gesture belongs to the form.
            swipe(Offset(centerX, height - 20f), Offset(centerX, height - 140f))
        }
        compose.runOnIdle { assertTrue(scroll.value > 0) }
    }
}
