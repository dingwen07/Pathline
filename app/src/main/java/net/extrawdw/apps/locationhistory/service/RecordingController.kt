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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import net.extrawdw.apps.locationhistory.core.RecorderState
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
 * The recording brain — now a thin **interpreter** over the pure [RecordingPolicy]. It owns the I/O
 * and lifecycle (the foreground service, the AR heartbeat, geofences, the significant-motion sensor,
 * sample persistence) and the wall clock; the policy owns the state-machine logic. Each entry point
 * gathers evidence, persists what it must, hands the event to the policy, and executes the
 * [RecordingAction]s it returns. It holds no wakelock — everything is driven by system-delivered
 * PendingIntents.
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
    /** The pure decision core: fix/speed buffers, drift guard, cluster detector, AR timeline, the
     *  state machine itself. See [RecordingPolicy]/[RecordingHeuristics] — extracted so the rules are
     *  JVM-testable. [RecordingPolicy.state] is the single source of truth for the recorder state. */
    private val heuristics = RecordingHeuristics()
    private val policy = RecordingPolicy(heuristics)

    /** Latest non-stationary classification, shown as the notification movement badge while MOVING. */
    @Volatile
    private var latestMovingClassification: DevicePhysicalState? = null

    /** The most recent AR activity + a nominal confidence, fed to the per-sample classifier. Kept as
     *  the raw AR name (e.g. ON_BICYCLE) the classifier matches; the policy uses its own AR timeline. */
    @Volatile
    private var lastArActivity: String? = null

    @Volatile
    private var lastArConfidence: Int? = null

    /** Mirror of the (state, display) last pushed to the service, so we only retune on real change. */
    @Volatile
    private var serviceState: RecorderState? = null

    @Volatile
    private var serviceDisplay: DevicePhysicalState? = null

    /** Serializes all externally-invoked entry points (see class doc). Not reentrant. */
    private val stateMutex = Mutex()

    /** Long-lived scope for the significant-motion backoff re-arm and the verification deadline. */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Consecutive unconfirmed significant-motion triggers in the current stay -> backoff growth. */
    @Volatile
    private var sigMotionFalseStreak = 0

    @Volatile
    private var sigMotionRearmJob: Job? = null

    /** Fires the VERIFYING_DEPARTURE deadline when the burst hasn't confirmed a departure. */
    @Volatile
    private var verifyDeadlineJob: Job? = null

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
        // The bootstrap fix is classified here, and the UI calls this from the main dispatcher — so
        // keep the whole sequence off the main thread.
        AppLog.i(TAG, "startTracking")
        enableTracking()
        startService()
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
        cancelSigMotionRearm()
        cancelVerify()
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
        if (!recorderService.isRecording) startService()
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
     * Stays cheap when the recorder is already up — it doesn't re-arm AR/geofences on every tick —
     * but it still re-derives stationary state from stored fixes, so a recorder revived into a stale
     * UNKNOWN cadence (a START_STICKY restart with no AR STILL to follow) drops to low power within a
     * watchdog tick instead of holding UNKNOWN until the next departure. When the recorder is down it
     * re-arms the passive triggers (so a later AR/geofence event can still revive it) and tries the
     * foreground start; if the platform refuses it — e.g. a plain worker has no background-start
     * exemption — it posts an alert so the user can resume. A true force-stop / OEM kill can't be
     * recovered here: a stopped app's receivers and workers never run.
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
        if (recorderService.isRecording) {
            // Alive but maybe stuck in a stale cadence: a START_STICKY restart can revive the
            // recorder in UNKNOWN while the device is already parked, with no AR STILL to follow
            // (the Transition API only reports changes), so it would hold the costly UNKNOWN cadence
            // until the next departure. Re-derive the stay from stored fixes and drop to low power.
            // Gate strictly on UNKNOWN: MOVING resolves itself via the live cluster detector, and
            // VERIFYING_DEPARTURE self-reverts on its deadline — repairing those would clobber the
            // live buffer or abort an in-flight departure check.
            if (policy.state == RecorderState.UNKNOWN) repairStationaryFromStoredSamples()
            return@withLock
        }
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
        if (startService()) {
            repairStationaryFromStoredSamples()
        } else {
            // Background FGS start refused. AR/geofences are still armed (a passive trigger can revive
            // recording), but tell the user so they can resume now instead of recording staying off.
            AppLog.w(TAG, "$trigger: foreground start denied — notifying user")
            Notifications.notifyRecordingNeedsResume(context)
        }
    }

    /**
     * Repair a sticky-service restart that came back without intent extras. Android restarts the
     * foreground service with `intent == null`, so [LocationRecorderService] briefly defaults to
     * UNKNOWN until the controller can inspect stored fixes. If those fixes already prove a stay,
     * immediately retune to STATIONARY instead of burning hours at the UNKNOWN fallback cadence.
     */
    suspend fun repairStateAfterServiceRestart(): Unit = stateMutex.withLock {
        repairStationaryFromStoredSamples()
    }

    /**
     * A single current fix, used to bootstrap recording and to anchor a stationary visit. Bounded:
     * a cold GPS fix indoors can take 20-30s — longer than a broadcast receiver's goAsync window —
     * and [stateMutex] is held while waiting, stalling every other entry point. On timeout the GMS
     * request is cancelled too, so the chip stops working on a fix nobody will use.
     */
    private suspend fun getCurrentLocation(
        timeoutMs: Long = Constants.CURRENT_FIX_TIMEOUT_MS,
    ): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val cancellation = CancellationTokenSource()
        return withTimeoutOrNull(timeoutMs) {
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

    // --- event entry points (delegate to the policy, then execute its actions) -----------------

    /** Handle a batch of Activity Recognition transitions. */
    suspend fun handleActivityTransitions(events: List<Pair<Int, Int>>): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "AR transition ignored — recording paused (app removed from Recents)")
            return@withLock
        }
        // Feed the per-sample classifier the raw name of the latest ENTER (it matches names like
        // ON_BICYCLE/ON_FOOT), while the policy gets the de-Android-ised ArActivity transitions.
        events.lastOrNull { it.second == ActivityTransition.ACTIVITY_TRANSITION_ENTER }?.let { (type, _) ->
            lastArActivity = arName(type)
            lastArConfidence = 90
            AppLog.i(TAG, "AR transition ENTER ${arName(type)}")
        }
        val transitions = events.mapNotNull { (type, transitionType) ->
            arActivityOf(type)?.let { it to (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) }
        }
        if (transitions.isEmpty()) return@withLock
        executeActions(policy.onArTransitions(transitions, System.currentTimeMillis()))
    }

    /**
     * A dwell-geofence EXIT fired. The geofence triggers on a single boundary-crossing fix, so before
     * paying for the high-accuracy MOVING cadence we re-sample once and let the policy confirm the
     * displacement is real (GPS speed, a shaking phone, or a trustworthy fix clearly outside the
     * stay). A coarse, motionless outlier is indoor drift -> keep the low-power stay and re-arm the
     * geofence. Pre-refactor every exit promoted to MOVING, so drift pinned high-accuracy GPS for
     * 8-16 min at a time (the 06-13 idle drain).
     */
    suspend fun handleGeofenceExit(): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "geofence EXIT ignored — recording paused (app removed from Recents)")
            return@withLock
        }
        AppLog.i(TAG, "geofence EXIT")
        // Cheap confirmatory re-sample (the significant-motion pattern), but this runs inside the
        // geofence broadcast's goAsync() budget, so gather the IMU burst and the fix CONCURRENTLY and
        // cap the fix wait tighter than the foreground path. The pure policy gets both for the verdict.
        val (motionVariance, freshFix) = coroutineScope {
            val mv = async { motionSensorReader.motionVariance() }
            val ff = async { getCurrentLocation(Constants.GEOFENCE_CONFIRM_FIX_TIMEOUT_MS)?.toRecentFix() }
            mv.await() to ff.await()
        }
        val actions = policy.onGeofenceExit(freshFix, motionVariance, System.currentTimeMillis())
        if (actions.isEmpty()) {
            AppLog.i(TAG, "geofence EXIT — unconfirmed (drift); keeping stay, re-arming dwell geofence")
            rearmDwellGeofenceAtAnchor()
        } else {
            executeActions(actions) // EnterMoving clears the geofence itself
        }
    }

    /**
     * The significant-motion trigger fired while stationary — a fast, low-power *hint* that the user
     * may have started location-changing motion. It is NOT trusted as a confirmed departure: the
     * one-shot hardware sensor fires on transients (a phone bumped on a desk, HVAC, a passing truck)
     * that don't actually move the user.
     *
     * Cheap pre-filter before paying for a GPS burst: a real walk shakes the phone
     * ([MotionSensorReader.motionVariance]); a still phone is usually a transient but can also be a
     * smooth, mounted-vehicle departure, so a still phone is confirmed against one fresh fix's
     * displacement from the stay (the old drift guard) rather than bursting on every trigger. Only a
     * genuine motion/displacement hint enters VERIFYING_DEPARTURE; a transient just re-arms the sensor
     * with backoff so a chronically noisy surface can't flap us into the burst cadence.
     */
    suspend fun handleSignificantMotion(): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) {
            AppLog.i(
                TAG,
                "significant motion ignored — recording paused (app removed from Recents)"
            )
            return@withLock
        }
        if (policy.state != RecorderState.STATIONARY) return@withLock // stale trigger; already moving
        val motionVariance = motionSensorReader.motionVariance()
        val movingHint = if (motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING) {
            true // phone is shaking — a real walk; let the burst confirm vs. mere handling
        } else {
            // Still phone: confirm a possible smooth departure against one fresh fix before bursting.
            val fresh = getCurrentLocation()
            fresh != null && !heuristics.isWithinStay(
                fresh.latitude, fresh.longitude,
                if (fresh.hasSpeed()) fresh.speed else 0f, motionVariance,
            )
        }
        if (movingHint) {
            AppLog.i(TAG, "significant motion — verifying departure")
            executeActions(policy.onSignificantMotion(System.currentTimeMillis()))
        } else {
            AppLog.i(TAG, "significant motion — transient (no displacement); re-arming with backoff")
            rearmSignificantMotionWithBackoff()
        }
    }

    /**
     * Doze idle-mode changed (`PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`, delivered by the FGS).
     * Deep Doze is motion-gated by the platform's own significant-motion logic, so its verdict is a
     * higher-quality signal than our raw sensor:
     * - **Entering** idle = the system confirms durable stationarity, so the policy drops to the
     *   stationary cadence (if it wasn't already) and we disarm the now-redundant sensor.
     * - **Exiting** idle is *usually* real motion, but maintenance windows and screen-on also exit, so
     *   confirm with a cheap accelerometer burst (no GPS): travel shakes the phone -> wake; otherwise
     *   just re-arm the sensor fresh so the next genuine move is caught immediately.
     */
    suspend fun handleDeviceIdleModeChanged(idle: Boolean): Unit = stateMutex.withLock {
        if (isAutostartSuppressed()) return@withLock
        if (idle) {
            AppLog.i(TAG, "device idle (Doze) — durably stationary; disarming significant motion")
            executeActions(policy.onDozeIdle(idle = true, motionVariance = 0f, nowMs = System.currentTimeMillis()))
            cancelSigMotionRearm()
            significantMotionManager.disarm()
            return@withLock
        }
        AppLog.i(TAG, "device exited Doze idle")
        if (policy.state != RecorderState.STATIONARY) return@withLock
        val motionVariance = motionSensorReader.motionVariance()
        val actions = policy.onDozeIdle(idle = false, motionVariance = motionVariance, nowMs = System.currentTimeMillis())
        if (actions.isEmpty()) {
            // Likely a maintenance-window / screen-on exit, not a departure. Restore the fast sensor.
            resetSigMotionBackoff()
            cancelSigMotionRearm()
            significantMotionManager.arm { handleSignificantMotion() }
        } else {
            AppLog.i(TAG, "Doze exit with motion (var=$motionVariance) — treating as departure")
            executeActions(actions)
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
        AppLog.i(TAG, "handleLocations: ${locations.size} fixes (state=${policy.state})")
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
        val classifiedFixes = ArrayList<ClassifiedFix>(locations.size)

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
            if (classification.state != DevicePhysicalState.STATIONARY) {
                latestMovingClassification = classification.state
            }
            classifiedFixes.add(
                ClassifiedFix(
                    fix = RecentFix(
                        t = location.time,
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        speedMps = if (location.hasSpeed()) location.speed else null,
                        stationary = classification.state == DevicePhysicalState.STATIONARY,
                    ),
                    classifiedState = classification.state,
                    isConfident = classification.isConfident,
                ),
            )
            val sample = buildSample(
                location, context, burst, classification.state, classification.confidence,
                stepDelta = if (location === sorted.last()) stepDelta else null,
            )
            toPersist.add(sample)
            affectedDays.add(sample.dayEpoch)
        }
        // One transaction for the whole delivered batch — and it must land before any stationary
        // transition below, whose executor reads mostRecent() to anchor the stay.
        locationRepository.recordAll(toPersist)

        // The policy buffers the fixes and decides the state (stay/leave/settle/idle-timeout).
        executeActions(policy.onFixes(classifiedFixes, motionVariance, System.currentTimeMillis()))
        // Keep the FGS notification + cadence in sync even when no transition fired (e.g. the
        // movement badge was refined while staying MOVING).
        refreshServiceState()
        affectedDays.forEach { workScheduler.enqueueTimelineMaintenance(it, "samples") }
    }

    /**
     * Repair a process-restart case where recent stored fixes already show a stationary cluster. The
     * policy replaces its buffer with the stored fixes and, if they cluster, returns an
     * [RecordingAction.EnterStationary] — so a boot / START_STICKY restart while parked drops to the
     * low-power cadence immediately instead of holding UNKNOWN until the next departure.
     */
    private suspend fun repairStationaryFromStoredSamples() {
        val recent = locationRepository.latest(12)
            .filter { it.includedInComputation }
            .sortedBy { it.timestampMs }
        if (recent.isEmpty()) return
        val fixes = recent.map {
            RecentFix(
                t = it.timestampMs,
                lat = it.latitude,
                lon = it.longitude,
                accuracyMeters = it.accuracy,
                speedMps = it.speed,
                stationary = it.devicePhysicalState == DevicePhysicalState.STATIONARY,
            )
        }
        executeActions(policy.onResumeRepair(fixes, System.currentTimeMillis()))
    }

    /** The VERIFYING_DEPARTURE deadline fired: ask the policy to revert if still unconfirmed. */
    private suspend fun onVerifyDeadline() = stateMutex.withLock {
        if (!recorderService.isRecording) return@withLock
        executeActions(policy.onVerifyDeadline(System.currentTimeMillis()))
    }

    // --- action interpreter -------------------------------------------------------------------

    private suspend fun executeActions(actions: List<RecordingAction>) {
        for (action in actions) when (action) {
            is RecordingAction.EnterStationary -> execEnterStationary(action)
            is RecordingAction.EnterMoving -> execEnterMoving(action)
            is RecordingAction.BeginVerifying -> execBeginVerifying(action)
            is RecordingAction.RevertToStationary -> execRevertToStationary(action)
        }
    }

    private suspend fun execEnterStationary(action: RecordingAction.EnterStationary) {
        AppLog.i(TAG, "enter STATIONARY (${action.reason}) — low-power cadence")
        cancelVerify()
        // Keep the foreground service alive but drop to the low-power stationary cadence; never stopSelf.
        refreshServiceState()
        // Make sure we have at least one fix to anchor the visit, even on a cold start.
        if (locationRepository.mostRecent() == null) {
            getCurrentLocation()?.let { handleLocationsCore(listOf(it)) }
        }
        val anchor = action.candidate ?: locationRepository.mostRecent()?.let {
            VisitCandidate(
                startMs = it.timestampMs,
                endMs = System.currentTimeMillis(),
                centroidLatitude = it.latitude,
                centroidLongitude = it.longitude,
                sampleCount = 1,
            )
        } ?: return
        armDwellGeofenceAdaptive(anchor.centroidLatitude, anchor.centroidLongitude)
        // The policy anchors the drift guard from the cluster centroid; when it had no centroid
        // (Doze / idle-timeout) seed it from the resolved anchor here.
        if (action.candidate == null) {
            heuristics.stationaryAnchor = anchor.centroidLatitude to anchor.centroidLongitude
        }
        if (action.armSigMotion) {
            // Fast, Doze-surviving departure trigger alongside the (laggy) geofence. One-shot; a
            // genuine new stay starts the backoff fresh (streak 0) so the sensor is fully responsive.
            cancelSigMotionRearm()
            resetSigMotionBackoff()
            significantMotionManager.arm { handleSignificantMotion() }
        }
        workScheduler.enqueueTimelineMaintenanceNow(TimeBuckets.dayEpoch(anchor.startMs), action.reason)
    }

    private suspend fun execEnterMoving(action: RecordingAction.EnterMoving) {
        AppLog.i(TAG, "enter MOVING (${action.reason}) — high-accuracy cadence")
        cancelVerify()
        cancelSigMotionRearm()
        resetSigMotionBackoff()
        geofenceManager.clearAll()
        significantMotionManager.disarm()
        workScheduler.enqueueTimelineMaintenanceNow(maintenanceDay(), action.reason)
        refreshServiceState()
    }

    private suspend fun execBeginVerifying(action: RecordingAction.BeginVerifying) {
        AppLog.i(TAG, "verifying departure (${action.reason}) — burst cadence for ${Constants.DEPARTURE_VERIFY_WINDOW_MS}ms")
        refreshServiceState()
        cancelVerify()
        verifyDeadlineJob = controllerScope.launch {
            delay(Constants.DEPARTURE_VERIFY_WINDOW_MS)
            onVerifyDeadline()
        }
    }

    private suspend fun execRevertToStationary(action: RecordingAction.RevertToStationary) {
        AppLog.i(TAG, "verify window elapsed (${action.reason}) — reverting to STATIONARY")
        refreshServiceState()
        // The dwell geofence was left armed through verification; re-arm only the (consumed) one-shot
        // sensor, with backoff growth so a chronically noisy surface can't flap us awake every minute.
        rearmSignificantMotionWithBackoff()
        workScheduler.enqueueTimelineMaintenanceNow(maintenanceDay(), action.reason)
    }

    // --- service retune -----------------------------------------------------------------------

    /** Start the service (cold start path), recording the pushed (state, display) so a later
     *  [refreshServiceState] won't redundantly retune. Returns the FGS start result. */
    private suspend fun startService(): Boolean {
        val display = currentDisplayState()
        serviceState = policy.state
        serviceDisplay = display
        return recorderService.start(
            policy.state, display, settingsRepository.settings.first().powerProfile,
        )
    }

    /** Re-tune the foreground service (cadence + notification) when the state or display changes. */
    private suspend fun refreshServiceState() {
        if (!recorderService.isRecording) return
        val display = currentDisplayState()
        if (policy.state == serviceState && display == serviceDisplay) return
        serviceState = policy.state
        serviceDisplay = display
        recorderService.start(policy.state, display, settingsRepository.settings.first().powerProfile)
    }

    /** The movement shown to the user: the precise mode while travelling, plain Stationary at a stay. */
    private fun currentDisplayState(): DevicePhysicalState = when (policy.state) {
        RecorderState.STATIONARY -> DevicePhysicalState.STATIONARY
        RecorderState.MOVING -> latestMovingClassification ?: DevicePhysicalState.UNKNOWN
        RecorderState.VERIFYING_DEPARTURE, RecorderState.UNKNOWN -> DevicePhysicalState.UNKNOWN
    }

    private suspend fun maintenanceDay(): Long =
        locationRepository.mostRecent()?.timestampMs?.let { TimeBuckets.dayEpoch(it) }
            ?: TimeBuckets.dayEpoch(System.currentTimeMillis())

    // --- significant-motion backoff -----------------------------------------------------------

    private fun resetSigMotionBackoff() {
        sigMotionFalseStreak = 0
    }

    private fun cancelSigMotionRearm() {
        sigMotionRearmJob?.cancel()
        sigMotionRearmJob = null
    }

    private fun cancelVerify() {
        verifyDeadlineJob?.cancel()
        verifyDeadlineJob = null
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
            if (policy.state == RecorderState.STATIONARY) {
                AppLog.i(
                    TAG,
                    "re-arming significant motion after ${delayMs}ms (unconfirmed streak=$streak)"
                )
                significantMotionManager.arm { handleSignificantMotion() }
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Decouple a delivered [Location] into the heuristics' [RecentFix] for a one-off policy check
     *  (the geofence-exit confirmation); not persisted — that is [handleLocationsCore]'s job. */
    private fun Location.toRecentFix(): RecentFix = RecentFix(
        t = time,
        lat = latitude,
        lon = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
        stationary = false,
    )

    /** Re-arm the dwell geofence at the current stay anchor after a drift-only exit, so monitoring
     *  continues without promoting to the high-accuracy cadence. */
    private suspend fun rearmDwellGeofenceAtAnchor() {
        val anchor = heuristics.stationaryAnchor
            ?: locationRepository.mostRecent()?.let { it.latitude to it.longitude }
            ?: return
        armDwellGeofenceAdaptive(anchor.first, anchor.second)
    }

    /**
     * Arm the dwell geofence with a radius widened to the GPS noise actually seen at this stay, so an
     * indoor place that scatters 100 m+ fixes doesn't trip a tight boundary on every wobble. Floored
     * at [Constants.DWELL_GEOFENCE_RADIUS_METERS] and capped at [Constants.DWELL_GEOFENCE_MAX_RADIUS_METERS]
     * so a pathological place can't open a dead zone where a real departure goes unnoticed.
     */
    private suspend fun armDwellGeofenceAdaptive(latitude: Double, longitude: Double) {
        val radius = (heuristics.stationaryNoiseRadius().toFloat() + Constants.DWELL_GEOFENCE_MARGIN_METERS)
            .coerceIn(Constants.DWELL_GEOFENCE_RADIUS_METERS, Constants.DWELL_GEOFENCE_MAX_RADIUS_METERS)
        geofenceManager.armDwellGeofence(latitude, longitude, radius)
    }

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

    /** Map a GMS `DetectedActivity` to the policy's [ArActivity], or null for untracked activities. */
    private fun arActivityOf(activityType: Int): ArActivity? = when (activityType) {
        DetectedActivity.STILL -> ArActivity.STILL
        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ArActivity.WALKING
        DetectedActivity.RUNNING -> ArActivity.RUNNING
        DetectedActivity.ON_BICYCLE -> ArActivity.CYCLING
        DetectedActivity.IN_VEHICLE -> ArActivity.IN_VEHICLE
        else -> null
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
        const val TAG = "Recorder"
    }
}
