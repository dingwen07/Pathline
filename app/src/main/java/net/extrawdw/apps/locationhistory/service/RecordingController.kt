package net.extrawdw.apps.locationhistory.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.enrich.DeviceStateCollector
import net.extrawdw.apps.locationhistory.data.enrich.MotionSensorReader
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.domain.VisitCandidate
import net.extrawdw.apps.locationhistory.ml.Classifier
import net.extrawdw.apps.locationhistory.ml.StateFeatureInput
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recording brain. It reacts to the low-power signals (Activity Recognition transitions,
 * geofence exits) and to batched location deliveries, deciding when to record, when to drop into
 * the stationary/geofence state, and how to persist + classify each sample. It holds no wakelock —
 * everything is driven by system-delivered PendingIntents.
 */
@Singleton
class RecordingController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceStateCollector: DeviceStateCollector,
    private val motionSensorReader: MotionSensorReader,
    private val classifier: Classifier,
    private val recorderService: RecorderServiceController,
    private val recognitionManager: RecognitionManager,
    private val geofenceManager: GeofenceManager,
    private val workScheduler: WorkScheduler,
) {
    @Volatile private var currentState: DevicePhysicalState = DevicePhysicalState.UNKNOWN
    @Volatile private var lastArActivity: String? = null
    @Volatile private var lastArConfidence: Int? = null
    @Volatile private var serviceState: DevicePhysicalState? = null
    private val recentSpeeds = ArrayDeque<Float>()
    private val speedLock = Any()

    /** Recent fixes used to detect "became stationary" from the classifier when AR is silent. */
    private data class Fix(
        val t: Long,
        val lat: Double,
        val lon: Double,
        val accuracyMeters: Float?,
        val speedMps: Float?,
        val stationary: Boolean,
    )
    private val recentFixes = ArrayDeque<Fix>()
    private val fixLock = Any()
    @Volatile private var stationaryAnchor: Pair<Double, Double>? = null

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    /** Begin the always-on, low-power heartbeat (Activity Recognition transitions). */
    fun enableTracking(): Boolean {
        val started = recognitionManager.start()
        AppLog.i(TAG, "enableTracking: AR started=$started perm=${recognitionManager.hasPermission()}")
        return started
    }

    /**
     * Turn tracking on (from the UI). Arms the AR heartbeat, grabs an immediate fix and starts the
     * foreground recording session. The session then **stays alive** for the whole tracking period
     * — when the device is still we drop to a low-power location cadence rather than stopping the
     * service, so it never silently exits.
     */
    suspend fun startTracking() {
        AppLog.i(TAG, "startTracking")
        enableTracking()
        val profile = settingsRepository.settings.first().powerProfile
        recorderService.start(currentState, profile)
        val fix = getCurrentLocation()
        if (fix != null) {
            AppLog.i(TAG, "startTracking: bootstrap fix ${fix.latitude},${fix.longitude}")
            handleLocations(listOf(fix))
        } else {
            AppLog.w(TAG, "startTracking: no bootstrap fix available")
        }
    }

    suspend fun disableTracking() {
        AppLog.i(TAG, "disableTracking")
        recognitionManager.stop()
        geofenceManager.clearAll()
        recorderService.stop()
    }

    /**
     * Turn recording on as an explicit user action (UI switch or notification action): clear any
     * autostart suppression, persist the enabled preference, start the foreground recorder and
     * (re)schedule the periodic workers. Mirrors the Settings toggle.
     */
    suspend fun enableTrackingFromUser() {
        AppLog.i(TAG, "enableTrackingFromUser")
        clearAutostartSuppression()
        settingsRepository.setTrackingEnabled(true)
        startTracking()
        workScheduler.schedulePeriodicTimelineMaintenance()
        workScheduler.schedulePeriodicBackup()
    }

    /**
     * The user removed the app from Recents while recording. If the stop-on-task-removed feature is
     * enabled, *pause* recording: leave the "Background recording" preference ON, but set a hidden
     * autostart-suppression flag so the passive triggers (AR transitions / geofence exits) can't
     * relaunch the foreground service. The pause lasts until the app is next launched into the
     * foreground ([resumeIfPreviouslyEnabled]) or the user resumes it from the notification. The
     * foreground service stops itself, so we only mark it stopped here.
     *
     * Returns true if recording was actually paused, or false when the feature is disabled and the
     * caller should keep recording running.
     */
    suspend fun pauseRecordingFromTaskRemoval(): Boolean {
        if (!settingsRepository.settings.first().stopOnTaskRemoved) {
            AppLog.i(TAG, "task removed but stop-on-task-removed is off — keeping recording")
            return false
        }
        AppLog.w(TAG, "pauseRecordingFromTaskRemoval — app removed from Recents; suppressing autostart")
        settingsRepository.setAutostartSuppressed(true)
        recorderService.suppressAutostart()
        recorderService.markStopped("task removed from Recents")
        return true
    }

    /** Resume after a task-removal pause (notification action). Clears suppression and restarts. */
    suspend fun resumeRecordingFromUser() {
        AppLog.i(TAG, "resumeRecordingFromUser")
        clearAutostartSuppression()
        if (!settingsRepository.settings.first().trackingEnabled) enableTrackingFromUser()
        else startTracking()
    }

    /** Notification action: stop pausing on close (turn the feature off) and resume recording now. */
    suspend fun disableStopOnCloseAndResume() {
        AppLog.i(TAG, "disableStopOnCloseAndResume")
        settingsRepository.setStopOnTaskRemoved(false)
        resumeRecordingFromUser()
    }

    /** Lift the autostart-suppression flag (persisted + in-memory). */
    private suspend fun clearAutostartSuppression() {
        settingsRepository.setAutostartSuppressed(false)
        recorderService.clearAutostartSuppression()
    }

    /**
     * True while recording is paused after a task removal. Also restores the in-memory suppression
     * gate in [RecorderServiceController], which is reset on a fresh process — so a passive trigger
     * that revived the process can't slip a foreground start past the gate.
     */
    private suspend fun isAutostartSuppressed(): Boolean {
        val suppressed = settingsRepository.autostartSuppressed.first()
        if (suppressed) recorderService.suppressAutostart()
        return suppressed
    }

    /**
     * The app is in the foreground again: lift any task-removal pause, then run the normal self-heal.
     * This is the only place the pause is cleared — a background restart (boot, AR, geofence) keeps
     * it in effect, so recording stays paused "until the app is launched and in foreground".
     */
    suspend fun onAppForegrounded() {
        clearAutostartSuppression()
        resumeIfPreviouslyEnabled()
    }

    /**
     * Self-heal: if tracking was enabled, make sure the foreground recorder is actually running (it
     * may have been killed) and the AR heartbeat + geofences are armed. Honours an active
     * task-removal pause — when suppressed, it leaves recording stopped until the app is foregrounded.
     */
    suspend fun resumeIfPreviouslyEnabled() {
        if (!settingsRepository.settings.first().trackingEnabled) {
            AppLog.i(TAG, "resume: tracking disabled, nothing to do")
            return
        }
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "resume: autostart suppressed (paused since task removal) — not starting")
            return
        }
        if (!recognitionManager.hasPermission()) {
            AppLog.w(TAG, "resume: missing activity-recognition permission")
            return
        }
        AppLog.i(TAG, "resume: tracking on, recorderRunning=${recorderService.isRecording}")
        enableTracking()
        geofenceManager.restore()
        if (!recorderService.isRecording) {
            val profile = settingsRepository.settings.first().powerProfile
            recorderService.start(currentState, profile)
        }
        repairStationaryFromStoredSamples()
    }

    /**
     * Re-arm passive system triggers from background-only startup events. Package replacement can
     * arrive while the app is not eligible to start a location FGS, so the foreground recorder is
     * resumed later from the activity self-heal path.
     */
    suspend fun rearmPassiveSignalsIfPreviouslyEnabled() {
        if (!settingsRepository.settings.first().trackingEnabled) {
            AppLog.i(TAG, "passive rearm: tracking disabled, nothing to do")
            return
        }
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "passive rearm: autostart suppressed (paused since task removal) — skipping")
            return
        }
        if (!recognitionManager.hasPermission()) {
            AppLog.w(TAG, "passive rearm: missing activity-recognition permission")
            return
        }
        AppLog.i(TAG, "passive rearm: restoring AR/geofences without foreground recorder")
        enableTracking()
        geofenceManager.restore()
    }

    /** A single current fix, used to bootstrap recording and to anchor a stationary visit. */
    private suspend fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token,
            ).await()
        }.getOrNull()
    }

    /** Handle a batch of Activity Recognition transitions; react to the most recent one. */
    suspend fun handleActivityTransitions(events: List<Pair<Int, Int>>) {
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "AR transition ignored — recording paused (app removed from Recents)")
            return
        }
        val latest = events.lastOrNull() ?: return
        val (activityType, transitionType) = latest
        if (transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) return

        lastArActivity = arName(activityType)
        lastArConfidence = 90
        AppLog.i(TAG, "AR transition ENTER ${arName(activityType)}")
        when (activityType) {
            // A bare AR STILL must not collapse us to the low-power cadence while we're actually
            // travelling — a smooth light-rail ride makes AR flap STILL/WALKING/RUNNING, and each
            // STILL would otherwise undersample the whole leg. Trust STILL only when recent fixes
            // show we've settled; a genuine stop is still caught here once the fixes stop moving (and
            // by the stationary-cluster detector in handleLocations).
            DetectedActivity.STILL -> if (recentlyMoving()) {
                AppLog.i(TAG, "AR STILL ignored — recent fixes still moving")
            } else {
                becameStationary()
            }
            // Walking can be in-place jitter at a dwell, so it still goes through the drift guard.
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> becameMoving(DevicePhysicalState.WALKING)
            // Running/cycling/vehicle are unambiguous motion (not plausible as dwell jitter): trust AR
            // and leave the stay immediately. Otherwise boarding is suppressed by the stale stationary
            // fix (the device hasn't moved 80m *yet*), so the whole moving leg is undersampled.
            DetectedActivity.RUNNING -> becameMoving(DevicePhysicalState.RUNNING, force = true)
            DetectedActivity.ON_BICYCLE -> becameMoving(DevicePhysicalState.CYCLING, force = true)
            DetectedActivity.IN_VEHICLE -> becameMoving(DevicePhysicalState.IN_VEHICLE, force = true)
        }
    }

    /** Leaving the dwell geofence means the user is on the move again (a real, forced departure). */
    suspend fun handleGeofenceExit() {
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "geofence EXIT ignored — recording paused (app removed from Recents)")
            return
        }
        AppLog.i(TAG, "geofence EXIT")
        geofenceManager.clearAll()
        becameMoving(DevicePhysicalState.UNKNOWN, force = true)
    }

    /** Network changes are recorded as context on the next fix and may be a movement cue. */
    suspend fun handleNetworkChanged() {
        val day = locationRepository.mostRecent()?.timestampMs?.let { TimeBuckets.dayEpoch(it) }
            ?: TimeBuckets.dayEpoch(System.currentTimeMillis())
        workScheduler.enqueueTimelineMaintenance(day, "network_changed")
        refreshServiceState()
    }

    /** Persist + classify a batch of delivered fixes. */
    suspend fun handleLocations(locations: List<Location>) {
        if (locations.isEmpty()) return
        AppLog.i(TAG, "handleLocations: ${locations.size} fixes (state=$currentState)")
        val context = deviceStateCollector.snapshot()
        val motionVariance = motionSensorReader.motionVariance()
        val affectedDays = linkedSetOf<Long>()

        for (location in locations.sortedBy { it.time }) {
            val speed = if (location.hasSpeed()) location.speed else null
            speed?.let { pushSpeed(it) }
            val (mean, max, variance) = speedStats()

            val classification = classifier.classifyState(
                StateFeatureInput(
                    speedMps = speed,
                    speedMeanMps = mean,
                    speedMaxMps = max,
                    speedVariance = variance,
                    motionVariance = motionVariance,
                    horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    arActivity = lastArActivity,
                    arConfidence = lastArConfidence,
                    networkTransport = context.networkTransport,
                    hasCellService = context.hasCellService,
                    cellSignalDbm = context.cellSignalDbm,
                ),
            )
            if (classification.state != DevicePhysicalState.STATIONARY &&
                (classification.isConfident || (speed ?: 0f) >= 1.0f)
            ) {
                // Stationary exits should not wait solely for AR/geofence when the fixes themselves
                // show real motion; the drift guard still blocks jitter inside the dwell radius.
                if (currentState == DevicePhysicalState.STATIONARY) {
                    // Non-drift already checked against this fresh delivered fix; force past the
                    // stale-fix re-check inside becameMoving so it isn't re-suppressed.
                    if (!isLikelyDrift(location, motionVariance)) becameMoving(classification.state, force = true)
                } else {
                    currentState = classification.state
                }
            }
            pushFix(location, classification.state == DevicePhysicalState.STATIONARY)
            val sample = buildSample(location, context, classification.state, classification.confidence)
            locationRepository.record(sample)
            affectedDays.add(sample.dayEpoch)
        }

        // Keep the FGS notification + cadence in sync with the current state (fixes a stale
        // "Unknown" notification when the state was set by a geofence/AR event long ago).
        refreshServiceState()

        // AR can be silent — if recent fixes have settled into one spot, switch the recorder to
        // stationary cadence and ask maintenance to rebuild the derived timeline.
        val stationaryCandidate = stationaryClusterCandidate()
        if (stationaryCandidate != null && currentState != DevicePhysicalState.STATIONARY) {
            AppLog.i(TAG, "stationary detected from fixes (AR silent)")
            becameStationary(stationaryCandidate, forceOpen = true)
        }
        affectedDays.forEach { workScheduler.enqueueTimelineMaintenance(it, "samples") }
    }

    /** Re-tune the foreground service (and its notification) when the state actually changes. */
    private suspend fun refreshServiceState() {
        if (!recorderService.isRecording) return
        if (currentState == serviceState) return
        serviceState = currentState
        recorderService.start(currentState, settingsRepository.settings.first().powerProfile)
    }

    private fun pushFix(location: Location, stationary: Boolean) = synchronized(fixLock) {
        recentFixes.addLast(
            Fix(
                t = location.time,
                lat = location.latitude,
                lon = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                stationary = stationary,
            ),
        )
        val cutoff = location.time - FIX_WINDOW_MS
        while (recentFixes.isNotEmpty() && recentFixes.first().t < cutoff) recentFixes.removeFirst()
    }

    /**
     * True when fixes in the last [RECENT_MOTION_WINDOW_MS] show real movement (GPS speed or a spread
     * beyond the stay radius) — used to reject a premature AR STILL while travelling, e.g. a light-rail
     * ride that AR keeps mislabelling as STILL. Looks at a short recent window (not the full fix buffer)
     * so a genuine stop still flips this false within ~the window once the device settles.
     */
    private fun recentlyMoving(): Boolean = synchronized(fixLock) {
        if (recentFixes.isEmpty()) return false
        val cutoff = recentFixes.last().t - RECENT_MOTION_WINDOW_MS
        val recent = recentFixes.filter { it.t >= cutoff }
        if (recent.size < 2) return false
        val maxSpeed = recent.mapNotNull { it.speedMps }.maxOrNull() ?: 0f
        if (maxSpeed >= 1.5f) return true
        val spread = recent.maxOf { a ->
            recent.maxOf { b ->
                net.extrawdw.apps.locationhistory.core.Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
            }
        }
        spread > net.extrawdw.apps.locationhistory.core.Constants.STATIONARY_RADIUS_METERS
    }

    /** Returns a visit candidate when recent fixes have settled in one spot long enough, even if
     *  Activity Recognition never reported STILL. */
    private fun stationaryClusterCandidate(): VisitCandidate? = synchronized(fixLock) {
        if (recentFixes.size < 3) return null
        val minVisitMs = net.extrawdw.apps.locationhistory.core.Constants.MIN_VISIT_DURATION_MS
        val now = recentFixes.last().t
        val window = recentFixes.filter { now - it.t <= minVisitMs }
        if (window.size < 3) return null
        if (now - window.first().t < minVisitMs * 0.8) return null
        val goodAccuracy = window.count { (it.accuracyMeters ?: 30f) <=
            net.extrawdw.apps.locationhistory.core.Constants.SAMPLE_ACCURACY_GATE_METERS
        }
        if (goodAccuracy < window.size * 0.7) return null
        val meanSpeed = window.mapNotNull { it.speedMps }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        if (meanSpeed > 0.6) return null
        val cLat = window.sumOf { it.lat } / window.size
        val cLon = window.sumOf { it.lon } / window.size
        val withinRadius = window.all {
            net.extrawdw.apps.locationhistory.core.Geo.distanceMeters(cLat, cLon, it.lat, it.lon) <=
                net.extrawdw.apps.locationhistory.core.Constants.STATIONARY_RADIUS_METERS
        }
        val mostlyStationary = window.count { it.stationary } >= window.size * 0.8
        if (!withinRadius || !mostlyStationary) return null
        VisitCandidate(
            startMs = window.first().t,
            endMs = now,
            centroidLatitude = cLat,
            centroidLongitude = cLon,
            sampleCount = window.size,
        )
    }

    /** Repair a process-restart case where recent stored fixes already show a stationary cluster. */
    private suspend fun repairStationaryFromStoredSamples() {
        val recent = locationRepository.latest(12)
            .filter { it.includedInComputation }
            .sortedBy { it.timestampMs }
        if (recent.size < 3 || recent.last().devicePhysicalState != DevicePhysicalState.STATIONARY) return
        synchronized(fixLock) {
            recentFixes.clear()
            recent.forEach {
                recentFixes.addLast(
                    Fix(
                        t = it.timestampMs,
                        lat = it.latitude,
                        lon = it.longitude,
                        accuracyMeters = it.accuracy,
                        speedMps = it.speed,
                        stationary = it.devicePhysicalState == DevicePhysicalState.STATIONARY,
                    ),
                )
            }
        }
        stationaryClusterCandidate()?.let {
            AppLog.i(TAG, "resume repair: restoring stationary recorder state from stored fixes")
            becameStationary(it, forceOpen = true)
        }
    }

    // --- state transitions --------------------------------------------------------------------

    private suspend fun becameStationary(candidateFromFixes: VisitCandidate? = null, forceOpen: Boolean = false) {
        if (currentState == DevicePhysicalState.STATIONARY && !forceOpen) return
        currentState = DevicePhysicalState.STATIONARY
        AppLog.i(TAG, "becameStationary: keeping FGS alive at low-power cadence")
        // Keep the foreground service alive but drop to the low-power stationary location cadence,
        // so it never silently exits. (We deliberately do NOT stopSelf here.)
        if (recorderService.isRecording) {
            recorderService.start(DevicePhysicalState.STATIONARY, settingsRepository.settings.first().powerProfile)
            serviceState = DevicePhysicalState.STATIONARY
        }

        // Make sure we have at least one fix to anchor the visit, even on a cold start.
        if (locationRepository.mostRecent() == null) {
            getCurrentLocation()?.let { handleLocations(listOf(it)) }
        }
        val anchor = locationRepository.mostRecent()
        if (anchor != null) {
            val now = System.currentTimeMillis()
            val candidate = candidateFromFixes ?: VisitCandidate(
                startMs = anchor.timestampMs,
                endMs = now,
                centroidLatitude = anchor.latitude,
                centroidLongitude = anchor.longitude,
                sampleCount = 1,
            )
            geofenceManager.armDwellGeofence(candidate.centroidLatitude, candidate.centroidLongitude)
            stationaryAnchor = candidate.centroidLatitude to candidate.centroidLongitude
            workScheduler.enqueueTimelineMaintenanceNow(TimeBuckets.dayEpoch(anchor.timestampMs), "became_stationary")
        }
    }

    private suspend fun becameMoving(state: DevicePhysicalState, force: Boolean = false) {
        // Drift guard: while inside the dwell geofence, an Activity Recognition "walking" is
        // probably jitter. A real departure is caught by the geofence exit (force = true).
        if (!force) {
            // Weight device movement heavily: a real walk shakes the phone, GPS drift happens while it
            // sits still. Sample the accelerometer once so any physical motion (or GPS speed) overrides
            // the position-based drift check — a short walk at a noisy-GPS place must not read as drift.
            val motionVariance = motionSensorReader.motionVariance()
            if (isLikelyDrift(motionVariance)) {
                // The stored fix can be minutes stale on the low-power cadence and still at the anchor,
                // so confirm a possible departure against a fresh fix before suppressing.
                val fresh = getCurrentLocation()
                if (fresh == null || isLikelyDrift(fresh, motionVariance)) {
                    AppLog.i(TAG, "becameMoving($state) ignored — within stay radius (drift)")
                    return
                }
                AppLog.i(TAG, "becameMoving($state): fresh fix confirms real departure (stale drift overridden)")
            }
        }
        currentState = state
        AppLog.i(TAG, "becameMoving: $state (force=$force)")
        val maintenanceDay = locationRepository.mostRecent()?.timestampMs?.let { TimeBuckets.dayEpoch(it) }
            ?: TimeBuckets.dayEpoch(System.currentTimeMillis())
        stationaryAnchor = null
        geofenceManager.clearAll()
        workScheduler.enqueueTimelineMaintenanceNow(maintenanceDay, "became_moving")
        val profile = settingsRepository.settings.first().powerProfile
        recorderService.start(state, profile)
        serviceState = state
    }

    /**
     * GPS drift = a near-anchor fix while the phone is **physically still**; a real departure either
     * displaces beyond the (noise-widened) stay radius, carries GPS speed, or shakes the phone. This
     * is the gate on leaving the low-power stationary cadence, so a false positive keeps us
     * undersampling — hence physical movement ([motionVariance]) is weighted heavily: any real walk
     * shakes the device and is never suppressed, even at a noisy-GPS place with no GPS speed.
     */
    private fun isDriftAt(lat: Double, lon: Double, speedMps: Float, motionVariance: Float): Boolean {
        if (currentState != DevicePhysicalState.STATIONARY) return false
        val anchor = stationaryAnchor ?: return true
        if (speedMps >= net.extrawdw.apps.locationhistory.core.Constants.DRIFT_MOVING_SPEED_MPS) return false
        if (motionVariance >= net.extrawdw.apps.locationhistory.core.Constants.DRIFT_MOTION_VARIANCE_CEILING) return false
        val d = net.extrawdw.apps.locationhistory.core.Geo.distanceMeters(anchor.first, anchor.second, lat, lon)
        return d < stationaryNoiseRadius()
    }

    /** Drift check against the last *stored* fix (may be stale on the low-power cadence). */
    private suspend fun isLikelyDrift(motionVariance: Float): Boolean {
        if (currentState != DevicePhysicalState.STATIONARY) return false
        if (stationaryAnchor == null) return true
        val last = locationRepository.mostRecent() ?: return false
        return isDriftAt(last.latitude, last.longitude, last.speed ?: 0f, motionVariance)
    }

    /** Drift check against a specific (fresh) fix. */
    private fun isLikelyDrift(location: Location, motionVariance: Float): Boolean =
        isDriftAt(
            location.latitude, location.longitude,
            if (location.hasSpeed()) location.speed else 0f, motionVariance,
        )

    /**
     * Radius (m) within which a near-anchor fix counts as jitter, widened to the GPS noise actually
     * observed here (RMS spread of recent stationary fixes) so a noisy place tolerates more wobble —
     * floored at [Constants.DRIFT_DISPLACEMENT_METERS] and capped at [Constants.PLACE_MAX_RADIUS_METERS]
     * so a pathological place can't open a huge dead zone. Only stationary-flagged fixes are used, so a
     * departure trajectory never inflates it.
     */
    private fun stationaryNoiseRadius(): Double = synchronized(fixLock) {
        val base = net.extrawdw.apps.locationhistory.core.Constants.DRIFT_DISPLACEMENT_METERS
        val cap = net.extrawdw.apps.locationhistory.core.Constants.PLACE_MAX_RADIUS_METERS
        val stat = recentFixes.filter { it.stationary }
        if (stat.size < 3) return base
        val cLat = stat.sumOf { it.lat } / stat.size
        val cLon = stat.sumOf { it.lon } / stat.size
        val rms = kotlin.math.sqrt(
            stat.sumOf {
                val d = net.extrawdw.apps.locationhistory.core.Geo.distanceMeters(cLat, cLon, it.lat, it.lon)
                d * d
            } / stat.size,
        )
        (rms * NOISE_RMS_FACTOR).coerceIn(base, cap)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun buildSample(
        location: Location,
        context: net.extrawdw.apps.locationhistory.data.enrich.DeviceContext,
        state: DevicePhysicalState,
        confidence: Float,
    ): LocationSampleEntity = LocationSampleEntity(
        timestampMs = location.time,
        dayEpoch = TimeBuckets.dayEpoch(location.time),
        latitude = location.latitude,
        longitude = location.longitude,
        altitude = if (location.hasAltitude()) location.altitude else null,
        accuracy = if (location.hasAccuracy()) location.accuracy else null,
        verticalAccuracyMeters = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
        bearing = if (location.hasBearing()) location.bearing else null,
        bearingAccuracyDegrees = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else null,
        speed = if (location.hasSpeed()) location.speed else null,
        speedAccuracyMetersPerSecond = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
        provider = location.provider,
        isMock = location.isMock,
        elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        satelliteCount = location.extras?.getInt("satellites")?.takeIf { it > 0 },
        batteryPct = context.batteryPct,
        isCharging = context.isCharging,
        networkTransport = context.networkTransport,
        networkTypeName = context.networkTypeName,
        cellSignalDbm = context.cellSignalDbm,
        hasCellService = context.hasCellService,
        wifiSsid = context.wifiSsid,
        wifiBssid = context.wifiBssid,
        screenOn = context.screenOn,
        arActivity = lastArActivity,
        arConfidence = lastArConfidence,
        devicePhysicalState = state,
        devicePhysicalStateConfidence = confidence,
    )

    private fun pushSpeed(speed: Float) = synchronized(speedLock) {
        recentSpeeds.addLast(speed)
        while (recentSpeeds.size > SPEED_WINDOW) recentSpeeds.removeFirst()
    }

    private fun speedStats(): Triple<Float, Float, Float> = synchronized(speedLock) {
        if (recentSpeeds.isEmpty()) return Triple(0f, 0f, 0f)
        val mean = recentSpeeds.average().toFloat()
        val max = recentSpeeds.max()
        val variance = recentSpeeds.map { (it - mean) * (it - mean) }.average().toFloat()
        Triple(mean, max, variance)
    }

    private fun arName(activityType: Int): String = when (activityType) {
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.TILTING -> "TILTING"
        else -> "UNKNOWN"
    }

    private companion object {
        const val SPEED_WINDOW = 10
        const val TAG = "Recorder"
        const val FIX_WINDOW_MS = 6 * 60_000L
        const val RECENT_MOTION_WINDOW_MS = 90_000L
        const val NOISE_RMS_FACTOR = 2.5    // stationary-fix RMS spread -> drift radius
    }
}
