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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.enrich.DeviceStateCollector
import net.extrawdw.apps.locationhistory.data.enrich.MotionSensorReader
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.domain.VisitCandidate
import net.extrawdw.apps.locationhistory.ml.HeuristicClassifier
import net.extrawdw.apps.locationhistory.ml.StateFeatureInput
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recording brain. It reacts to the low-power signals (Activity Recognition transitions,
 * geofence exits) and to batched location deliveries, deciding when to record, when to drop into
 * the stationary/geofence state, and how to persist + classify each sample. It holds no wakelock —
 * everything is driven by system-delivered PendingIntents.
 *
 * Single-writer rule: every externally-invoked entry point (receivers, the foreground service,
 * workers, UI) serializes on [stateMutex], so concurrent broadcasts cannot interleave state
 * transitions or double-start the recorder. The mutex is NOT reentrant: locked wrappers delegate to
 * private *Core helpers, and internal code only ever calls the helpers, never another wrapper.
 */
@Singleton
class RecordingController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceStateCollector: DeviceStateCollector,
    private val motionSensorReader: MotionSensorReader,
    private val stepCounterMonitor: net.extrawdw.apps.locationhistory.data.enrich.StepCounterMonitor,
    private val classifier: HeuristicClassifier,
    private val recorderService: RecorderServiceController,
    private val recognitionManager: RecognitionManager,
    private val geofenceManager: GeofenceManager,
    private val significantMotionManager: SignificantMotionManager,
    private val workScheduler: WorkScheduler,
) {
    @Volatile
    private var currentState: DevicePhysicalState = DevicePhysicalState.UNKNOWN

    @Volatile
    private var lastArActivity: String? = null

    @Volatile
    private var lastArConfidence: Int? = null

    @Volatile
    private var serviceState: DevicePhysicalState? = null

    /** The pure decision core: fix/speed buffers, drift guard, cluster detector, backoff curve.
     *  See [RecordingHeuristics] — extracted so the rules are JVM-testable. */
    private val heuristics = RecordingHeuristics()

    /** Serializes all externally-invoked entry points (see class doc). Not reentrant. */
    private val stateMutex = Mutex()

    /** Long-lived scope for the significant-motion backoff re-arm (process-lifetime singleton). */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Consecutive unconfirmed significant-motion triggers in the current stay -> backoff growth. */
    @Volatile
    private var sigMotionFalseStreak = 0

    @Volatile
    private var sigMotionRearmJob: Job? = null

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    /** Begin the always-on, low-power heartbeat (Activity Recognition transitions). */
    fun enableTracking(): Boolean {
        val started = recognitionManager.start()
        AppLog.i(
            TAG,
            "enableTracking: AR started=$started perm=${recognitionManager.hasPermission()}"
        )
        return started
    }

    /**
     * Turn tracking on (from the UI). Arms the AR heartbeat, grabs an immediate fix and starts the
     * foreground recording session. The session then **stays alive** for the whole tracking period
     * — when the device is still we drop to a low-power location cadence rather than stopping the
     * service, so it never silently exits.
     */
    suspend fun startTracking() {
        stateMutex.withLock { startTrackingCore() }
    }

    private suspend fun startTrackingCore() = withContext(Dispatchers.Default) {
        // The bootstrap fix is classified here (ML inference), and the UI calls this from the main
        // dispatcher — so keep the whole sequence off the main thread.
        AppLog.i(TAG, "startTracking")
        enableTracking()
        val profile = settingsRepository.settings.first().powerProfile
        recorderService.start(currentState, profile)
        val fix = getCurrentLocation()
        if (fix != null) {
            // No raw coordinates in logs: session files can leave the device via the log export.
            val ageMs = System.currentTimeMillis() - fix.time
            AppLog.i(
                TAG,
                "startTracking: bootstrap fix acc=${if (fix.hasAccuracy()) fix.accuracy else null} ageMs=$ageMs"
            )
            handleLocationsCore(listOf(fix))
        } else {
            AppLog.w(TAG, "startTracking: no bootstrap fix available")
        }
    }

    suspend fun disableTracking(): Unit = stateMutex.withLock {
        AppLog.i(TAG, "disableTracking")
        recognitionManager.stop()
        geofenceManager.clearAll()
        significantMotionManager.disarm()
        recorderService.stop()
        // The fused PI location request is system-persistent state (it survives process death) and
        // is not tracked by the in-memory running flag — recorderService.stop() no-ops when the
        // flag is already false — so remove it here unconditionally.
        removeOrphanedLocationUpdates()
        workScheduler.cancelRecordingWatchdog()
    }

    /**
     * Turn recording on as an explicit user action (UI switch or notification action): clear any
     * autostart suppression, persist the enabled preference, start the foreground recorder and
     * (re)schedule the periodic workers. Mirrors the Settings toggle.
     */
    suspend fun enableTrackingFromUser() {
        stateMutex.withLock { enableTrackingFromUserCore() }
    }

    private suspend fun enableTrackingFromUserCore() {
        AppLog.i(TAG, "enableTrackingFromUser")
        clearAutostartSuppression()
        settingsRepository.setTrackingEnabled(true)
        startTrackingCore()
        workScheduler.schedulePeriodicTimelineMaintenance()
        workScheduler.scheduleRecordingWatchdog()
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
    suspend fun pauseRecordingFromTaskRemoval(): Boolean = stateMutex.withLock {
        if (!settingsRepository.settings.first().stopOnTaskRemoved) {
            AppLog.i(TAG, "task removed but stop-on-task-removed is off — keeping recording")
            return@withLock false
        }
        AppLog.w(
            TAG,
            "pauseRecordingFromTaskRemoval — app removed from Recents; suppressing autostart"
        )
        settingsRepository.setAutostartSuppressed(true)
        recorderService.suppressAutostart()
        recorderService.markStopped("task removed from Recents")
        true
    }

    /** Resume after a task-removal pause (notification action). Clears suppression and restarts. */
    suspend fun resumeRecordingFromUser() {
        stateMutex.withLock { resumeRecordingFromUserCore() }
    }

    private suspend fun resumeRecordingFromUserCore() {
        AppLog.i(TAG, "resumeRecordingFromUser")
        clearAutostartSuppression()
        if (!settingsRepository.settings.first().trackingEnabled) enableTrackingFromUserCore()
        else startTrackingCore()
    }

    /** Notification action: stop pausing on close (turn the feature off) and resume recording now. */
    suspend fun disableStopOnCloseAndResume(): Unit = stateMutex.withLock {
        AppLog.i(TAG, "disableStopOnCloseAndResume")
        settingsRepository.setStopOnTaskRemoved(false)
        resumeRecordingFromUserCore()
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
    suspend fun onAppForegrounded(): Unit = stateMutex.withLock {
        clearAutostartSuppression()
        resumeIfPreviouslyEnabledCore()
    }

    /**
     * Self-heal: if tracking was enabled, make sure the foreground recorder is actually running (it
     * may have been killed) and the AR heartbeat + geofences are armed. Honours an active
     * task-removal pause — when suppressed, it leaves recording stopped until the app is foregrounded.
     */
    suspend fun resumeIfPreviouslyEnabled() {
        stateMutex.withLock { resumeIfPreviouslyEnabledCore() }
    }

    private suspend fun resumeIfPreviouslyEnabledCore() {
        if (!settingsRepository.settings.first().trackingEnabled) {
            AppLog.i(TAG, "resume: tracking disabled, nothing to do")
            return
        }
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "resume: autostart suppressed (paused since task removal) — not starting")
            return
        }
        if (!recognitionManager.hasPermission()) {
            // Degrade, don't disable: location-only recording still works. enableTracking() below
            // skips AR registration internally while the permission is missing.
            AppLog.w(TAG, "resume: missing activity-recognition permission")
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
     * Make sure the foreground recorder is actually running when it should be, and tell the user when
     * it can't be brought back. Shared by two background-only triggers:
     *  - a package replacement (`ACTION_MY_PACKAGE_REPLACED`, which kills the process and stops the
     *    service) — one of the background FGS-start exemptions, alongside `BOOT_COMPLETED`, and
     *    `location` is not on the Android 14 boot-launch denylist, so the start usually succeeds;
     *  - the periodic [net.extrawdw.apps.locationhistory.work.RecordingWatchdogWorker], which catches a
     *    process kill / silent service death that `START_STICKY` didn't recover.
     *
     * Fast no-op when the recorder is already up, so the 15-minute watchdog doesn't re-arm AR/geofences
     * on every tick. When the recorder is down it re-arms the passive triggers (so a later AR/geofence
     * event can still revive it) and tries the foreground start; if the platform refuses it — e.g. a
     * plain worker has no background-start exemption — it posts an alert so the user can resume. A true
     * force-stop / OEM kill can't be recovered here: a stopped app's receivers and workers never run.
     */
    suspend fun ensureRecorderRunning(trigger: String): Unit = stateMutex.withLock {
        // Read the preference before the isRecording short-circuit: a recorder (or an orphaned PI
        // location request) alive while tracking is disabled is a zombie and must be torn down.
        if (!settingsRepository.settings.first().trackingEnabled) {
            if (recorderService.isRecording) {
                AppLog.w(TAG, "$trigger: tracking disabled but recorder running — stopping")
                recorderService.stop()
            } else {
                AppLog.i(TAG, "$trigger: tracking disabled, nothing to do")
            }
            removeOrphanedLocationUpdates()
            return@withLock
        }
        if (recorderService.isRecording) return@withLock
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "$trigger: autostart suppressed (paused since task removal) — skipping")
            return@withLock
        }
        if (!recognitionManager.hasPermission()) {
            // Degrade, don't disable: location-only recording still works. enableTracking() below
            // skips AR registration internally while the permission is missing.
            AppLog.w(TAG, "$trigger: missing activity-recognition permission")
        }
        AppLog.i(TAG, "$trigger: recorder down — re-arming and restarting")
        enableTracking()
        geofenceManager.restore()
        val profile = settingsRepository.settings.first().powerProfile
        if (recorderService.start(currentState, profile)) {
            repairStationaryFromStoredSamples()
        } else {
            // Background FGS start refused. AR/geofences are still armed (a passive trigger can revive
            // recording), but tell the user so they can resume now instead of recording staying off.
            AppLog.w(TAG, "$trigger: foreground start denied — notifying user")
            Notifications.notifyRecordingNeedsResume(context)
        }
    }

    /**
     * A single current fix, used to bootstrap recording and to anchor a stationary visit. Bounded:
     * a cold GPS fix indoors can take 20-30s — longer than a broadcast receiver's goAsync window —
     * and [stateMutex] is held while waiting, stalling every other entry point. On timeout the GMS
     * request is cancelled too, so the chip stops working on a fix nobody will use.
     */
    private suspend fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val cancellation = CancellationTokenSource()
        return withTimeoutOrNull(Constants.CURRENT_FIX_TIMEOUT_MS) {
            runCatching {
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, cancellation.token,
                ).await()
            }.getOrNull()
        } ?: run {
            cancellation.cancel()
            null
        }
    }

    /** Handle a batch of Activity Recognition transitions; react to the most recent one. */
    suspend fun handleActivityTransitions(events: List<Pair<Int, Int>>): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "AR transition ignored — recording paused (app removed from Recents)")
            return@withLock
        }
        val latest = events.lastOrNull() ?: return@withLock
        val (activityType, transitionType) = latest
        if (transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) return@withLock

        lastArActivity = arName(activityType)
        lastArConfidence = 90
        AppLog.i(TAG, "AR transition ENTER ${arName(activityType)}")
        when (activityType) {
            // A bare AR STILL must not collapse us to the low-power cadence while we're actually
            // travelling — a smooth light-rail ride makes AR flap STILL/WALKING/RUNNING, and each
            // STILL would otherwise undersample the whole leg. Trust STILL only when recent fixes
            // show we've settled; a genuine stop is still caught here once the fixes stop moving (and
            // by the stationary-cluster detector in handleLocations).
            DetectedActivity.STILL -> if (heuristics.recentlyMoving()) {
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
            DetectedActivity.IN_VEHICLE -> becameMoving(
                DevicePhysicalState.IN_VEHICLE,
                force = true
            )
        }
    }

    /** Leaving the dwell geofence means the user is on the move again (a real, forced departure). */
    suspend fun handleGeofenceExit(): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "geofence EXIT ignored — recording paused (app removed from Recents)")
            return@withLock
        }
        AppLog.i(TAG, "geofence EXIT")
        geofenceManager.clearAll()
        becameMoving(DevicePhysicalState.UNKNOWN, force = true)
    }

    /**
     * The significant-motion trigger fired while stationary — a fast, low-power *hint* that the user
     * may have started location-changing motion. It is NOT trusted as a confirmed departure: the
     * one-shot hardware sensor fires on transients (a phone bumped on a desk, HVAC, a passing truck)
     * that don't actually move the user, and blindly ramping GPS on each one pins the recorder in the
     * costly UNKNOWN network-scanning cadence (the idle-battery drain seen in the 06-05 dump).
     *
     * Instead we *verify* before leaving low power, via the same drift guard used elsewhere
     * ([becameMoving] with `force = false`): a real walk shakes the phone (`motionVariance`) or, for a
     * smoother ride, a fresh fix shows GPS speed/displacement. A transient shows neither and is
     * suppressed. An unconfirmed trigger re-arms the sensor with a growing backoff so a chronically
     * noisy surface can't flap us awake. ~1 min to wake on a real departure is acceptable; the
     * Doze-exit path ([handleDeviceIdleModeChanged]) and the next classified fix are additional
     * backstops.
     */
    suspend fun handleSignificantMotion(): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) {
            AppLog.i(
                TAG,
                "significant motion ignored — recording paused (app removed from Recents)"
            )
            return@withLock
        }
        // Already moving; stale trigger.
        if (currentState != DevicePhysicalState.STATIONARY) return@withLock
        AppLog.i(TAG, "significant motion — verifying departure")
        if (!becameMoving(DevicePhysicalState.UNKNOWN, force = false)) {
            // Unconfirmed: stay in the low-power stationary cadence and re-arm with backoff.
            rearmSignificantMotionWithBackoff()
        }
    }

    /**
     * Doze idle-mode changed (`PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`, delivered by the FGS).
     * Deep Doze is motion-gated by the platform's own significant-motion logic, so its verdict is a
     * higher-quality signal than our raw sensor:
     * - **Entering** idle = the system confirms durable stationarity. Our sensor is redundant (a real
     *   departure will break Doze and arrive on the exit branch), so disarm it to drop wakeups.
     * - **Exiting** idle is *usually* real motion, but maintenance windows and screen-on also exit, so
     *   confirm with a cheap accelerometer burst (no GPS): travel shakes the phone -> wake; otherwise
     *   just re-arm the sensor fresh so the next genuine move is caught immediately.
     */
    suspend fun handleDeviceIdleModeChanged(idle: Boolean): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) return@withLock
        if (idle) {
            AppLog.i(TAG, "device idle (Doze) — durably stationary; disarming significant motion")
            cancelSigMotionRearm()
            significantMotionManager.disarm()
            return@withLock
        }
        AppLog.i(TAG, "device exited Doze idle")
        if (currentState != DevicePhysicalState.STATIONARY) return@withLock
        val motionVariance = motionSensorReader.motionVariance()
        if (motionVariance >= net.extrawdw.apps.locationhistory.core.Constants.DRIFT_MOTION_VARIANCE_CEILING) {
            AppLog.i(TAG, "Doze exit with motion (var=$motionVariance) — treating as departure")
            becameMoving(DevicePhysicalState.UNKNOWN, force = true)
        } else {
            // Likely a maintenance-window / screen-on exit, not a departure. Restore the fast sensor.
            resetSigMotionBackoff()
            cancelSigMotionRearm()
            significantMotionManager.arm { handleSignificantMotion() }
        }
    }

    /** Network changes are recorded as context on the next fix and may be a movement cue. */
    suspend fun handleNetworkChanged(): Unit = stateMutex.withLock {
        val day = locationRepository.mostRecent()?.timestampMs?.let { TimeBuckets.dayEpoch(it) }
            ?: TimeBuckets.dayEpoch(System.currentTimeMillis())
        workScheduler.enqueueTimelineMaintenance(day, "network_changed")
        refreshServiceState()
    }

    /** Persist + classify a batch of delivered fixes. */
    suspend fun handleLocations(locations: List<Location>) {
        stateMutex.withLock { handleLocationsCore(locations) }
    }

    private suspend fun handleLocationsCore(locations: List<Location>) {
        if (locations.isEmpty()) return
        // Safety net for an orphaned PI location request: the request survives process death, so
        // fixes can keep arriving with tracking off (e.g. a stop() that early-returned on the
        // in-memory flag). Never persist them; tear the request down instead.
        if (!settingsRepository.settings.first().trackingEnabled) {
            AppLog.w(
                TAG,
                "handleLocations: ${locations.size} fixes while tracking disabled — removing orphaned location request"
            )
            removeOrphanedLocationUpdates()
            return
        }
        AppLog.i(TAG, "handleLocations: ${locations.size} fixes (state=$currentState)")
        val context = deviceStateCollector.snapshot()
        // One IMU+barometer burst per delivered batch; its summary is stamped on every sample in
        // the batch as evidence for the span-level timeline classifier.
        val burst = motionSensorReader.burstSummary()
        val motionVariance = burst.accelVariance
        // One step-counter delta per batch, stamped on the batch's LAST sample (others null) so
        // summing non-null stepDelta over a span counts each step exactly once.
        val stepDelta = stepCounterMonitor.stepsSinceLastBatch()
        val affectedDays = linkedSetOf<Long>()
        val toPersist = ArrayList<LocationSampleEntity>(locations.size)

        val sorted = locations.sortedBy { it.time }
        for (location in sorted) {
            val speed = if (location.hasSpeed()) location.speed else null
            speed?.let { heuristics.pushSpeed(it) }
            val (mean, max, variance) = heuristics.speedStats()

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
                    if (!isLikelyDrift(location, motionVariance)) becameMoving(
                        classification.state,
                        force = true
                    )
                } else {
                    currentState = classification.state
                }
            }
            heuristics.pushFix(
                RecentFix(
                    t = location.time,
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    speedMps = if (location.hasSpeed()) location.speed else null,
                    stationary = classification.state == DevicePhysicalState.STATIONARY,
                ),
            )
            val sample = buildSample(
                location, context, burst, classification.state, classification.confidence,
                stepDelta = if (location === sorted.last()) stepDelta else null,
            )
            toPersist.add(sample)
            affectedDays.add(sample.dayEpoch)
        }
        // One transaction for the whole delivered batch — and it must land before the
        // stationary-cluster handling below, which reads mostRecent() to anchor the stay.
        locationRepository.recordAll(toPersist)

        // Keep the FGS notification + cadence in sync with the current state (fixes a stale
        // "Unknown" notification when the state was set by a geofence/AR event long ago).
        refreshServiceState()

        // AR can be silent — if recent fixes have settled into one spot, switch the recorder to
        // stationary cadence and ask maintenance to rebuild the derived timeline.
        val stationaryCandidate = heuristics.stationaryClusterCandidate()
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

    /** Repair a process-restart case where recent stored fixes already show a stationary cluster. */
    private suspend fun repairStationaryFromStoredSamples() {
        val recent = locationRepository.latest(12)
            .filter { it.includedInComputation }
            .sortedBy { it.timestampMs }
        if (recent.size < 3 || recent.last().devicePhysicalState != DevicePhysicalState.STATIONARY) return
        heuristics.replaceFixes(
            recent.map {
                RecentFix(
                    t = it.timestampMs,
                    lat = it.latitude,
                    lon = it.longitude,
                    accuracyMeters = it.accuracy,
                    speedMps = it.speed,
                    stationary = it.devicePhysicalState == DevicePhysicalState.STATIONARY,
                )
            },
        )
        heuristics.stationaryClusterCandidate()?.let {
            AppLog.i(TAG, "resume repair: restoring stationary recorder state from stored fixes")
            becameStationary(it, forceOpen = true)
        }
    }

    // --- state transitions --------------------------------------------------------------------

    private suspend fun becameStationary(
        candidateFromFixes: VisitCandidate? = null,
        forceOpen: Boolean = false
    ) {
        if (currentState == DevicePhysicalState.STATIONARY && !forceOpen) return
        currentState = DevicePhysicalState.STATIONARY
        AppLog.i(TAG, "becameStationary: keeping FGS alive at low-power cadence")
        // Keep the foreground service alive but drop to the low-power stationary location cadence,
        // so it never silently exits. (We deliberately do NOT stopSelf here.)
        if (recorderService.isRecording) {
            recorderService.start(
                DevicePhysicalState.STATIONARY,
                settingsRepository.settings.first().powerProfile
            )
            serviceState = DevicePhysicalState.STATIONARY
        }

        // Make sure we have at least one fix to anchor the visit, even on a cold start.
        if (locationRepository.mostRecent() == null) {
            getCurrentLocation()?.let { handleLocationsCore(listOf(it)) }
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
            geofenceManager.armDwellGeofence(
                candidate.centroidLatitude,
                candidate.centroidLongitude
            )
            // Fast, Doze-surviving departure trigger alongside the (laggy) geofence — fires the moment
            // the user starts location-changing motion. One-shot; re-armed on the next stationary entry.
            // A genuine new stay starts the backoff fresh (streak 0) so the sensor is fully responsive.
            cancelSigMotionRearm()
            resetSigMotionBackoff()
            significantMotionManager.arm { handleSignificantMotion() }
            heuristics.stationaryAnchor = candidate.centroidLatitude to candidate.centroidLongitude
            workScheduler.enqueueTimelineMaintenanceNow(
                TimeBuckets.dayEpoch(anchor.timestampMs),
                "became_stationary"
            )
        }
    }

    /** @return true if the state actually transitioned to moving; false if suppressed as drift. */
    private suspend fun becameMoving(state: DevicePhysicalState, force: Boolean = false): Boolean {
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
                    return false
                }
                AppLog.i(
                    TAG,
                    "becameMoving($state): fresh fix confirms real departure (stale drift overridden)"
                )
            }
        }
        currentState = state
        AppLog.i(TAG, "becameMoving: $state (force=$force)")
        val maintenanceDay =
            locationRepository.mostRecent()?.timestampMs?.let { TimeBuckets.dayEpoch(it) }
                ?: TimeBuckets.dayEpoch(System.currentTimeMillis())
        heuristics.stationaryAnchor = null
        cancelSigMotionRearm()
        resetSigMotionBackoff()
        geofenceManager.clearAll()
        significantMotionManager.disarm()
        workScheduler.enqueueTimelineMaintenanceNow(maintenanceDay, "became_moving")
        val profile = settingsRepository.settings.first().powerProfile
        recorderService.start(state, profile)
        serviceState = state
        return true
    }

    // --- significant-motion backoff -----------------------------------------------------------

    private fun resetSigMotionBackoff() {
        sigMotionFalseStreak = 0
    }

    private fun cancelSigMotionRearm() {
        sigMotionRearmJob?.cancel()
        sigMotionRearmJob = null
    }

    /**
     * Re-arm the one-shot significant-motion sensor after a delay that doubles with each consecutive
     * unconfirmed trigger in the current stay (30s -> 1m -> 2m -> ... capped at 30m). A phone resting
     * on a vibrating surface keeps tripping the sensor; without this it would flap us out of the
     * low-power cadence every ~40s all night. The streak resets on a confirmed departure, a new
     * stationary anchor, or a Doze-exit verdict. The delay coroutine is naturally deferred by Doze
     * (CPU suspended) — fine, since deep Doze hands departure detection to the idle-exit path anyway.
     */
    private fun rearmSignificantMotionWithBackoff() {
        val streak = sigMotionFalseStreak
        val delayMs = RecordingHeuristics.sigMotionBackoffMs(streak)
        sigMotionFalseStreak = streak + 1
        cancelSigMotionRearm()
        sigMotionRearmJob = controllerScope.launch {
            delay(delayMs)
            if (currentState == DevicePhysicalState.STATIONARY) {
                AppLog.i(
                    TAG,
                    "re-arming significant motion after ${delayMs}ms (unconfirmed streak=$streak)"
                )
                significantMotionManager.arm { handleSignificantMotion() }
            }
        }
    }

    /** Drift check against the last *stored* fix (may be stale on the low-power cadence). */
    private suspend fun isLikelyDrift(motionVariance: Float): Boolean {
        if (currentState != DevicePhysicalState.STATIONARY) return false
        // No anchor -> nothing to measure displacement against, so it cannot be called drift;
        // assuming drift here would suppress every departure until an anchor appears.
        if (heuristics.stationaryAnchor == null) return false
        val last = locationRepository.mostRecent() ?: return false
        return heuristics.isDriftAt(
            currentState, last.latitude, last.longitude, last.speed ?: 0f, motionVariance,
        )
    }

    /** Drift check against a specific (fresh) fix. */
    private fun isLikelyDrift(location: Location, motionVariance: Float): Boolean =
        heuristics.isDriftAt(
            currentState, location.latitude, location.longitude,
            if (location.hasSpeed()) location.speed else 0f, motionVariance,
        )

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Remove the fused PI location request directly. The request is system-persistent state (it
     * survives process death and service teardown), so its removal must never depend on the
     * in-memory running flag — every "tracking is off" path calls this unconditionally.
     */
    private fun removeOrphanedLocationUpdates() {
        runCatching {
            fusedClient.removeLocationUpdates(LocationRecorderService.locationPendingIntent(context))
        }
    }

    private fun buildSample(
        location: Location,
        context: net.extrawdw.apps.locationhistory.data.enrich.DeviceContext,
        burst: net.extrawdw.apps.locationhistory.data.enrich.MotionBurst,
        state: DevicePhysicalState,
        confidence: Float,
        stepDelta: Int?,
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
        motionVariance = burst.accelVariance,
        stepCadenceHz = burst.stepCadenceHz,
        gravityAngleDeltaDeg = burst.gravityAngleDeltaDeg,
        pressureHpa = burst.pressureHpa,
        stepDelta = stepDelta,
    )

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
        const val TAG = "Recorder"
    }
}
