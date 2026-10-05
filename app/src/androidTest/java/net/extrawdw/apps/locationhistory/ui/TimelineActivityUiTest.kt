package net.extrawdw.apps.locationhistory.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.backup.ActivityGpxShare
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.domain.TripPlayback
import net.extrawdw.apps.locationhistory.domain.TimedRoutePoint
import net.extrawdw.apps.locationhistory.domain.SegmentType
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TimelineActivityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun gpxExportSharesAReadableFileAttachmentWithTemporaryReadAccess() {
        val trip = fixtureTrip(false)
        val samples = List(3) { fixtureSample(it).copy(timestampMs = trip.startMs + it * 60_000) }
        val uri = ActivityGpxShare.prepare(context, trip, samples)!!
        try {
            val intent = ActivityGpxShare.intent(uri)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/gpx+xml", intent.type)
            assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals("content", uri.scheme)
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(0).endsWith(".gpx"))
            }
            val xml = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertEquals(3, "<trkpt ".toRegex().findAll(xml).count())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun activityNotesSurviveRestorationAndSave() {
        val restoration = StateRestorationTester(compose)
        var savedNote: String? = null
        restoration.setContent {
            PathlineTheme {
                ActivityDetailDialog(
                    trip = fixtureTrip(true), paths = fixturePaths(),
                    playback = fixturePlayback(), projectPath = ::projectPath,
                    loadAnnotations = { _, _ -> AnnotationData("Original note", listOf("Work"), emptyMap()) },
                    onConfirm = { true }, onSave = { note, _ -> savedNote = note }, onExport = {}, onSplit = {}, onDismiss = {},
                )
            }
        }
        compose.onNode(hasSetTextAction() and hasText("Original note"))
            .performScrollTo().performTextReplacement("Edited activity")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction() and hasText("Edited activity")).assertTextContains("Edited activity")
        compose.onNodeWithText(context.getString(R.string.action_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Edited activity", savedNote) }
    }

    @Test fun splitSuggestionsAndUserChangesDrivePreviewAndSavedTypes() {
        var preview: SplitPreview? = null
        var saved: SplitPreview? = null
        var classifications = 0
        val walking = SegmentType.Moving(TransportMode.WALKING)
        compose.setContent {
            PathlineTheme {
                SplitEditorPanel(
                    samples = List(6) { fixtureSample(it) }, initialType = SegmentType.Stationary,
                    classifySplit = { _, _ -> classifications++; walking to SegmentType.Moving(TransportMode.CAR) },
                    onSplitPreview = { preview = it },
                    onSplit = { index, before, after -> saved = SplitPreview(index, before, after) },
                    onConvert = {}, onCancel = {},
                )
            }
        }
        compose.runOnIdle { assertEquals(SplitPreview(3, walking, SegmentType.Moving(TransportMode.CAR)), preview) }
        val label = context.getString(R.string.type_picker, context.getString(R.string.field_type), context.getString(TransportMode.CAR.labelRes))
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(TransportMode.CYCLING.labelRes)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_move_split_later)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(SplitPreview(4, walking, SegmentType.Moving(TransportMode.CYCLING)), preview)
            assertEquals(1, classifications)
        }
        compose.onNodeWithText(context.getString(R.string.action_split_here)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(preview, saved) }
    }

    @Test fun activityProgressDefaultsToCompleteAndSurvivesRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PathlineTheme {
                ActivityDetailDialog(
                    trip = fixtureTrip(true), paths = fixturePaths(), playback = fixturePlayback(),
                    projectPath = ::projectPath,
                    loadAnnotations = { _, _ -> AnnotationData("", emptyList(), emptyMap()) },
                    onConfirm = { true }, onSave = { _, _ -> }, onExport = {}, onSplit = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.activity_elapsed, "20:00", "20:00"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.activity_progress))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
        val elapsed = context.getString(R.string.activity_elapsed, "10:00", "20:00")
        compose.onNodeWithText(elapsed).assertIsDisplayed()
        val altitude = Format.altitude(context, 80.0)
        compose.onNodeWithText(altitude).performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(altitude).assertIsDisplayed()
        compose.onNodeWithText(elapsed).performScrollTo().assertIsDisplayed()
    }

    @Test fun graphInspectionIsIndependentAndSliderResumesGraphFollowing() {
        val mapPoint = AtomicReference<Wgs84Coordinate?>()
        val routeProjections = AtomicInteger()
        compose.setContent {
            PathlineTheme {
                ActivityDetailDialog(
                    trip = fixtureTrip(false), paths = fixturePaths(), playback = fixturePlayback(),
                    projectPath = { points ->
                        if (points.size == 1) mapPoint.set(points.single()) else routeProjections.incrementAndGet()
                        projectPath(points)
                    },
                    loadAnnotations = { _, _ -> AnnotationData("", emptyList(), emptyMap()) },
                    onConfirm = { true }, onSave = { _, _ -> }, onExport = {}, onSplit = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.activity_progress))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }
        compose.waitUntil { routeProjections.get() > 0 }
        val projectionsBeforeInspection = routeProjections.get()
        val chart = compose.onNodeWithContentDescription(context.getString(R.string.activity_graph))
        chart.performScrollTo().performTouchInput { click(center) }
        compose.waitUntil { mapPoint.get() != null }
        val centerPoint = mapPoint.get()!!
        assertEquals(1.305 + 0.005 * 480.0 / 1080.0, centerPoint.latitude, 0.00001)
        compose.onNodeWithText(Format.altitude(context, 80.0)).assertIsDisplayed()
        chart.performTouchInput { swipe(center, Offset(width * 0.2f, centerY)) }
        compose.waitUntil { mapPoint.get()!!.latitude < centerPoint.latitude }
        compose.onNodeWithText(context.getString(R.string.activity_elapsed, "5:00", "20:00"))
            .performScrollTo().assertIsDisplayed()
        assertEquals(projectionsBeforeInspection, routeProjections.get())

        // Seeking on the main slider takes over graph selection again after independent inspection.
        compose.onNodeWithContentDescription(context.getString(R.string.activity_progress))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
        compose.onNodeWithText(context.getString(R.string.activity_elapsed, "10:00", "20:00")).assertIsDisplayed()
        compose.onNodeWithText(Format.altitude(context, 80.0)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Format.speed(context, 7.0 / 3.0)).assertIsDisplayed()
    }

    @Test fun graphSeriesDefaultOnToggleIndependentlyAndSurviveRestoration() {
        val restoration = StateRestorationTester(compose)
        val trip = fixtureTrip(false)
        restoration.setContent {
            PathlineTheme {
                ActivityMetricsChart(fixturePlayback(), trip.startMs, trip.endMs,
                    progressTime = { trip.startMs + 600_000 }, onProgressTimeChange = {})
            }
        }
        val altitude = compose.onNode(hasText(context.getString(R.string.activity_altitude)) and isToggleable())
        val speed = compose.onNode(hasText(context.getString(R.string.activity_speed)) and isToggleable())
        val graph = compose.onNodeWithContentDescription(context.getString(R.string.activity_graph))
        val altitudeReading = compose.onNodeWithText(Format.altitude(context, 80.0))
        val speedReading = compose.onNodeWithText(Format.speed(context, 7.0 / 3.0))
        altitude.assertIsOn()
        speed.assertIsOn()
        altitudeReading.assertIsDisplayed()
        speedReading.assertIsDisplayed()
        speed.performClick().assertIsOff()
        speedReading.assertDoesNotExist()
        altitudeReading.assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        speed.assertIsOff()
        altitude.assertIsOn()
        altitude.performClick().assertIsOff()
        graph.assertDoesNotExist()
        speed.performClick().assertIsOn()
        graph.assertIsDisplayed()
        speedReading.assertIsDisplayed()
        altitudeReading.assertDoesNotExist()
    }

    private fun fixtureTrip(confirmed: Boolean) = TripEntity(
        id = 91, fromVisitId = null, toVisitId = null, startMs = 1_791_072_000_000,
        endMs = 1_791_073_200_000, dayEpoch = LocalDate.of(2026, 10, 4).toEpochDay(),
        mode = TransportMode.WALKING, modeConfidence = 0.6f, encodedPolyline = "",
        distanceMeters = 1200.0, confirmed = confirmed,
    )

    private fun fixturePaths() = listOf(listOf(
        GoogleMapCoordinate(1.30, 103.80), GoogleMapCoordinate(1.305, 103.803),
        GoogleMapCoordinate(1.31, 103.801),
    ))

    private fun projectPath(points: List<Wgs84Coordinate>) =
        listOf(points.map { GoogleMapCoordinate(it.latitude, it.longitude) })

    private fun fixturePlayback(): TripPlayback {
        val start = fixtureTrip(true).startMs
        val points = fixturePaths().single()
        val route = TripPlayback(listOf(0L, 120_000L, 1_200_000L).mapIndexed { index, elapsed ->
            TimedRoutePoint(start + elapsed, Wgs84Coordinate(points[index].latitude, points[index].longitude),
                speedMetersPerSecond = null)
        })
        return TripPlayback((0L..1_200_000L step 60_000L).map { elapsed ->
            val fraction = if (elapsed <= 120_000) elapsed / 120_000.0 else (elapsed - 120_000) / 1_080_000.0
            val altitude = if (elapsed <= 120_000) 10 + 30 * fraction else 40 + 90 * fraction
            val speed = if (elapsed <= 120_000) fraction else 1 + 3 * fraction
            TimedRoutePoint(start + elapsed, route.positionAt(start + elapsed)!!,
                altitude, speed)
        })
    }

    private fun fixtureSample(index: Int) = LocationSampleEntity(
        timestampMs = index * 60_000L, dayEpoch = 0, latitude = 1.3, longitude = 103.8,
        altitude = null, accuracy = 5f, verticalAccuracyMeters = null,
        bearing = null, bearingAccuracyDegrees = null, speed = 0f,
        speedAccuracyMetersPerSecond = null, provider = "fused", isMock = false,
        elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null,
        isCharging = null, networkTransport = null, networkTypeName = null,
        cellSignalDbm = null, hasCellService = true, wifiSsid = null, wifiBssid = null,
        screenOn = null, arActivity = null, arConfidence = null,
        devicePhysicalState = DevicePhysicalState.STATIONARY, devicePhysicalStateConfidence = 0.8f,
    )
}
