package net.extrawdw.apps.locationhistory.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.MainActivity
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.api.PathlineProvider
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantEntity
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import net.extrawdw.apps.locationhistory.security.MapsApiKeyVault
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Full application reset: run only on a disposable emulator, never against a user's history. */
class RecordedDataResetUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, PathlineProvider.DaoEntryPoint::class.java)
    private fun text(id: Int) = context.getString(id)

    @Test fun fullResetReturnsToWelcomeWithRestoreAvailableAndNoOldPlaceGrants() {
        val settings = entry.settingsRepository()
        runBlocking {
            settings.setTrackingEnabled(false)
            settings.setOnboardingComplete(true)
            val place = entry.placeDao().insert(fixturePlace().copy(id = 0))
            entry.annotationStore().setNote(AnnotationTarget.PLACE, place, "Reset fixture")
            entry.apiPlaceGrantDao().insertIgnore(listOf(ApiPlaceGrantEntity("reset.test", place, 0, 0)))
        }
        openReset()
        compose.onNodeWithText(text(R.string.data_reset_app_settings)).assertIsOff()
        compose.onNodeWithText(text(R.string.data_reset_maps_settings)).assertIsOff()
        compose.onNodeWithText(text(R.string.data_reset_logs)).assertIsOff()
        compose.onNodeWithText(text(R.string.data_reset_onboarding)).assertIsOn()
        confirmReset()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.action_restore_backup)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.onboarding_welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_restore_backup)).assertIsDisplayed()
        runBlocking {
            assertFalse(settings.onboardingComplete.first())
            assertFalse(settings.settings.first().trackingEnabled)
            assertFalse(settings.settings.first().apiAccessEnabled)
            assertNull(settings.backupConfig.first().treeUri)
            assertNull(settings.gpxConfig.first().treeUri)
            assertEquals(0, entry.placeDao().count())
            assertEquals(0, entry.visitDao().count())
            assertEquals(0, entry.tripDao().count())
            assertEquals(0L, entry.locationSampleDao().count())
            assertTrue(entry.locationSampleDao().deletedRanges(Long.MIN_VALUE, Long.MAX_VALUE).isEmpty())
            assertTrue(entry.apiPlaceGrantDao().grantedPlaceIds("reset.test").isEmpty())
            assertTrue(entry.searchDao().matchPlaces("Adaptive").isEmpty())
        }
    }

    @Test fun resettingAppSettingsPreservesMapsAndCanDeleteLogsWithoutReturningToOnboarding() {
        val settings = entry.settingsRepository()
        val vault = MapsApiKeyVault(context)
        val oldLog = File(context.noBackupFilesDir, "logs/session-reset-fixture.log")
        oldLog.parentFile!!.mkdirs()
        oldLog.writeText("Log to remove")
        vault.store("reset-test-key-not-a-real-api-key")
        runBlocking {
            settings.setTrackingEnabled(false)
            settings.setOnboardingComplete(true)
            settings.setPowerProfile(PowerProfile.HIGH_ACCURACY)
            settings.setGoogleCloudProjectId("preserved-project")
            settings.setAutomaticNearbyDailyLimit(0)
            settings.setIncludeMapsPlatformInBackup(true)
            settings.setRouteApiEnabled(false)
            entry.apiAccessDao().insert(ApiAccessEventEntity(packageName = "reset.test", dataType = "visits",
                startMs = 0, endMs = 1, rowCount = 1, timestampMs = System.currentTimeMillis()))
        }
        openReset()
        compose.onNodeWithText(text(R.string.data_reset_app_settings)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.data_reset_logs)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.data_reset_onboarding)).performScrollTo().performClick()
        confirmReset()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.data_deleted)).fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertTrue(settings.onboardingComplete.first())
            val current = settings.settings.first()
            assertEquals(PowerProfile.BALANCED, current.powerProfile)
            assertEquals("preserved-project", current.googleCloudProjectId)
            assertEquals(0, current.automaticNearbyDailyLimit)
            assertTrue(current.includeMapsPlatformInBackup)
            assertFalse(current.routeApiEnabled)
            assertTrue(entry.apiAccessDao().all().isEmpty())
        }
        assertNotNull(vault.apiKey())
        assertFalse(oldLog.exists())
    }

    @Test fun resettingMapsSettingsPreservesOtherSettingsAndUnselectedLogs() {
        val settings = entry.settingsRepository()
        val vault = MapsApiKeyVault(context)
        val oldLog = File(context.noBackupFilesDir, "logs/session-reset-keep.log")
        oldLog.parentFile!!.mkdirs()
        oldLog.writeText("Keep this log")
        vault.store("reset-test-key-not-a-real-api-key")
        runBlocking {
            settings.setTrackingEnabled(false)
            settings.setOnboardingComplete(true)
            settings.setPowerProfile(PowerProfile.HIGH_ACCURACY)
            settings.setGoogleCloudProjectId("reset-project")
            settings.setAutomaticNearbyDailyLimit(0)
            entry.apiAccessDao().insert(ApiAccessEventEntity(packageName = "reset.keep", dataType = "visits",
                startMs = 0, endMs = 1, rowCount = 1, timestampMs = System.currentTimeMillis()))
        }
        openReset()
        compose.onNodeWithText(text(R.string.data_reset_maps_settings)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.data_reset_onboarding)).performScrollTo().performClick()
        confirmReset()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.data_deleted)).fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertTrue(settings.onboardingComplete.first())
            val current = settings.settings.first()
            assertEquals(PowerProfile.HIGH_ACCURACY, current.powerProfile)
            assertEquals("", current.googleCloudProjectId)
            assertEquals(30, current.automaticNearbyDailyLimit)
            assertTrue(entry.apiAccessDao().all().any { it.packageName == "reset.keep" })
        }
        assertNull(vault.apiKey())
        assertTrue(oldLog.exists())
    }

    private fun openReset() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text(R.string.nav_settings)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(text(R.string.nav_settings)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.data_manage_action)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.data_reset_all)).performClick()
    }

    private fun confirmReset() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text(R.string.data_delete_action)).filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.data_delete_action)).performClick()
    }
}
