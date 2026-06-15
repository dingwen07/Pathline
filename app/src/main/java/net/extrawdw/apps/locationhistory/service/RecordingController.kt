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
import net.extrawdw.apps.locationhistory.core.RecorderState
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.enrich.DeviceStateCollector
import net.extrawdw.apps.locationhistory.data.enrich.MotionSensorReader
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.domain.VisitCandidate
import net.extrawdw.apps.locationhistory.domain.HeuristicClassifier
import net.extrawdw.apps.locationhistory.domain.StateFeatureInput
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

    /** Latest active AR activity evidence for the per-sample classifier. Kept as the raw AR name
     *  (e.g. ON_BICYCLE) the classifier matches; the policy uses its own AR timeline. */
    private val arEvidence = LatestArEvidence()

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

    /** Fires the SENSING/CONFIRMING verify deadline when the look hasn't confirmed a departure. */
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
        deviceStateCollector.disarmWifiLossDetection()
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
            // SENSING/CONFIRMING self-revert on their deadline — repairing those would clobber the
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
    suspend fun handleActivityTransitions(events: List<Pair<Int, Int>>): Unit =
        stateMutex.withLock {
            if (isAutostartSuppressed()) {
                AppLog.i(TAG, "AR transition ignored — recording paused (app removed from Recents)")
                return@withLock
            }
            val nowMs = System.currentTimeMillis()
            // Feed the per-sample classifier the raw active AR name (it matches names like
            // ON_BICYCLE/ON_FOOT), while the policy gets the de-Android-ised ArActivity transitions.
            for ((type, transitionType) in events) {
                val name = arName(type)
                if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    arEvidence.enter(name, confidence = 90)
                    AppLog.i(TAG, "AR transition ENTER $name")
                } else {
                    arEvidence.exit(name)
                    AppLog.i(TAG, "AR transition EXIT $name")
                }
            }
            val transitions = events.mapNotNull { (type, transitionType) ->
                arActivityOf(type)?.let { it to (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) }
            }
            if (transitions.isEmpty()) return@withLock
            executeActions(policy.onArTransitions(transitions, nowMs))
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
        AppLog.i(TAG, "geofence EXIT -> sensing departure")
        // The geofence is a single-fix trigger (indoor multipath fakes it off a still phone), so it is
        // only a hint: the SENSING_DEPARTURE tier does the evidence-gathering the controller used
        // to do inline against the goAsync() budget. A stale / already-verifying exit no-ops in the
        // policy. The geofence stays registered through verification; a SENSING revert re-arms it
        // (execRevertToStationary) to reset the GMS in/out baseline so it fires on a future real exit.
        executeActions(policy.onGeofenceExit(System.currentTimeMillis()))
    }

    /**
     * The significant-motion trigger fired while stationary — a cheap, motion-gated *hint* that the user
     * started location-changing motion. It is NOT a confirmed departure: per the AOSP spec it fires on
     * the start of locomotion (walking/biking/vehicle), which includes a few in-house steps that never
     * leave the stay. So it enters the [RecorderState.SENSING_DEPARTURE] first look, which gathers a few
     * HIGH_ACCURACY fixes and escalates only on real displacement — replacing the old inline fresh-fix
     * pre-check. The consumed one-shot sensor is re-armed (with backoff) when SENSING reverts, so a
     * chronically tripping surface can't flap us.
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
        AppLog.i(TAG, "significant motion -> sensing departure")
        executeActions(policy.onSignificantMotion(System.currentTimeMillis()))
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
            executeActions(
                policy.onDozeIdle(
                    idle = true,
                    motionVariance = 0f,
                    nowMs = System.currentTimeMillis()
                )
            )
            cancelSigMotionRearm()
            significantMotionManager.disarm()
            return@withLock
        }
        AppLog.i(TAG, "device exited Doze idle")
        if (policy.state != RecorderState.STATIONARY) return@withLock
        val motionVariance = motionSensorReader.motionVariance()
        val actions = policy.onDozeIdle(
            idle = false,
            motionVariance = motionVariance,
            nowMs = System.currentTimeMillis()
        )
        if (actions.isEmpty()) {
            // Likely a maintenance-window / screen-on exit, not a departure. Restore the fast sensor.
            resetSigMotionBackoff()
            cancelSigMotionRearm()
            significantMotionManager.arm { handleSignificantMotion() }
        } else {
            AppLog.i(TAG, "Doze exit with motion (var=$motionVariance) -> sensing departure")
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

    /**
     * The connected Wi-Fi network dropped while parked — a near-free departure hint (the phone was
     * carried out of range / off a hotspot). Routed from [DeviceStateCollector]'s Wi-Fi-loss callback,
     * armed while STATIONARY. Like a geofence exit it is only a hint, so it enters the cheap
     * SENSING_DEPARTURE look; a stale signal while not parked / already verifying no-ops in the policy.
     */
    suspend fun handleWifiDisconnected(): Unit = stateMutex.withLock {
        // The ConnectivityManager Wi-Fi callback outlives disableTracking() (it is never unregistered,
        // only the listener is nulled), so a drop racing a disable can land here after recording stopped
        // — guard on the persisted preference so we never drive a stray verify transition while off.
        if (!settingsRepository.settings.first().trackingEnabled) {
            AppLog.i(TAG, "wifi disconnect ignored — tracking disabled")
            return@withLock
        }
        if (isAutostartSuppressed()) {
            AppLog.i(TAG, "wifi disconnect ignored — recording paused (app removed from Recents)")
            return@withLock
        }
        if (policy.state != RecorderState.STATIONARY) return@withLock
        AppLog.i(TAG, "wifi disconnected -> sensing departure")
        executeActions(policy.onWifiDisconnected(System.currentTimeMillis()))
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
            speed?.let { heuristics.pushSpeed(it, location.time) }
            // Pass the fix time (speed-bearing or not) so stale speeds age out even when Doppler stalls.
            val (mean, max, variance) = heuristics.speedStats(location.time)

            val ar = arEvidence.current()
            val classification = classifier.classifyState(
                StateFeatureInput(
                    speedMps = speed,
                    speedMeanMps = mean,
                    speedMaxMps = max,
                    speedVariance = variance,
                    motionVariance = motionVariance,
                    horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    arActivity = ar.activity,
                    arConfidence = ar.confidence,
                    networkTransport = context.networkTransport,
                    hasCellService = context.hasCellService,
                    cellSignalDbm = context.cellSignalDbm,
                    // Net translation corroborates/overrides an AR STILL: don't stamp STATIONARY on a
                    // slow/smooth ride AR mislabels STILL. (Reflects the buffer before this batch.)
                    translating = heuristics.recentlyMoving(),
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
        // stepDelta is motion evidence (real walking) the policy uses to hold off the self-demote —
        // unlike raw IMU energy, steps don't fire on a vibrating-but-stationary surface.
        executeActions(
            policy.onFixes(
                classifiedFixes,
                motionVariance,
                System.currentTimeMillis(),
                stepDelta ?: 0
            ),
        )
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

    /** The verify deadline fired (SENSING/CONFIRMING): ask the policy to revert if still unconfirmed. */
    private suspend fun onVerifyDeadline() = stateMutex.withLock {
        val actions = policy.onVerifyDeadline(System.currentTimeMillis())
        if (actions.isEmpty()) return@withLock
        if (!settingsRepository.settings.first().trackingEnabled) {
            AppLog.i(
                TAG,
                "verify deadline — policy reverted while tracking disabled; side effects skipped"
            )
            return@withLock
        }
        if (isAutostartSuppressed()) {
            AppLog.i(
                TAG,
                "verify deadline — policy reverted while autostart suppressed; side effects skipped"
            )
            return@withLock
        }
        executeActions(actions)
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
        if (action.armSigMotion) {
            // Fast, Doze-surviving departure trigger alongside the (laggy) geofence. Armed BEFORE the
            // anchor bail-out below — significant motion needs no position, so a cold-start STILL with
            // no fix yet still gets departure wakeups (the geofence does need an anchor). One-shot; a
            // genuine new stay starts the backoff fresh (streak 0) so the sensor is fully responsive.
            cancelSigMotionRearm()
            resetSigMotionBackoff()
            significantMotionManager.arm { handleSignificantMotion() }
        }
        // Wi-Fi loss is a near-free departure hint (carried out of range / off a hotspot). Armed while
        // parked, eagerly registering the callback so a cold STATIONARY start catches the first drop.
        deviceStateCollector.armWifiLossDetection { controllerScope.launch { handleWifiDisconnected() } }
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
        workScheduler.enqueueTimelineMaintenanceNow(
            TimeBuckets.dayEpoch(anchor.startMs),
            action.reason
        )
    }

    private suspend fun execEnterMoving(action: RecordingAction.EnterMoving) {
        AppLog.i(TAG, "enter MOVING (${action.reason}) — high-accuracy cadence")
        cancelVerify()
        cancelSigMotionRearm()
        resetSigMotionBackoff()
        geofenceManager.clearAll()
        significantMotionManager.disarm()
        deviceStateCollector.disarmWifiLossDetection()
        workScheduler.enqueueTimelineMaintenanceNow(maintenanceDay(), action.reason)
        refreshServiceState()
    }

    private suspend fun execBeginVerifying(action: RecordingAction.BeginVerifying) {
        AppLog.i(
            TAG,
            "verifying departure (${action.reason}) -> ${policy.state}, deadline ${action.windowMs}ms"
        )
        refreshServiceState()
        cancelVerify()
        verifyDeadlineJob = controllerScope.launch {
            delay(action.windowMs)
            onVerifyDeadline()
        }
    }

    private suspend fun execRevertToStationary(action: RecordingAction.RevertToStationary) {
        AppLog.i(TAG, "verify window elapsed (${action.reason}) — reverting to STATIONARY")
        refreshServiceState()
        // Re-arm the consumed one-shot significant-motion sensor with backoff growth so a chronically
        // noisy surface can't flap us awake every minute.
        rearmSignificantMotionWithBackoff()
        // Re-arm the dwell geofence at the stay anchor. A spurious EXIT that routed us through SENSING
        // left the EXIT-only geofence latched 'outside' (GMS won't fire EXIT again until a re-ENTER);
        // re-adding it (clearAll + addGeofences, setInitialTrigger 0) resets the in/out baseline from the
        // device's current in-radius position, so a future REAL departure fires EXIT again instead of
        // staying silent until an incidental re-enter.
        val anchor = heuristics.stationaryAnchor
            ?: locationRepository.mostRecent()?.let { it.latitude to it.longitude }
        anchor?.let { armDwellGeofenceAdaptive(it.first, it.second) }
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
        recorderService.start(
            policy.state,
            display,
            settingsRepository.settings.first().powerProfile
        )
    }

    /** The movement shown to the user: the precise mode while travelling, plain Stationary at a stay. */
    private fun currentDisplayState(): DevicePhysicalState = when (policy.state) {
        RecorderState.STATIONARY -> DevicePhysicalState.STATIONARY
        // Show a specific transport mode only when body motion corroborates it. We can enter MOVING
        // from a geofence exit or significant-motion hint while the user is actually still (indoor
        // drift): AR then still reads STILL, and a stale WALKING classification would mislabel the
        // notification. Fall back to UNKNOWN, which the recording notification renders as a generic
        // "Moving" for a moving cadence. This is display only — persisted samples use the per-fix
        // classifier verdict, never this value.
        RecorderState.MOVING ->
            latestMovingClassification?.takeUnless { arEvidence.isActivity(AR_STILL) }
                ?: DevicePhysicalState.UNKNOWN

        RecorderState.SENSING_DEPARTURE, RecorderState.CONFIRMING_DEPARTURE, RecorderState.UNKNOWN ->
            DevicePhysicalState.UNKNOWN
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

    /**
     * Arm the dwell geofence with a radius widened to the GPS noise actually seen at this stay, so an
     * indoor place that scatters 100 m+ fixes doesn't trip a tight boundary on every wobble. Floored
     * at [Constants.DWELL_GEOFENCE_RADIUS_METERS] and capped at [Constants.DWELL_GEOFENCE_MAX_RADIUS_METERS]
     * so a pathological place can't open a dead zone where a real departure goes unnoticed.
     */
    private suspend fun armDwellGeofenceAdaptive(latitude: Double, longitude: Double) {
        val radius =
            (heuristics.stationaryNoiseRadius().toFloat() + Constants.DWELL_GEOFENCE_MARGIN_METERS)
                .coerceIn(
                    Constants.DWELL_GEOFENCE_RADIUS_METERS,
                    Constants.DWELL_GEOFENCE_MAX_RADIUS_METERS
                )
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
    ): LocationSampleEntity {
        val ar = arEvidence.current()
        return LocationSampleEntity(
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
            arActivity = ar.activity,
            arConfidence = ar.confidence,
            devicePhysicalState = state,
            devicePhysicalStateConfidence = confidence,
            motionVariance = burst.accelVariance,
            stepCadenceHz = burst.stepCadenceHz,
            gravityAngleDeltaDeg = burst.gravityAngleDeltaDeg,
            pressureHpa = burst.pressureHpa,
            stepDelta = stepDelta,
        )
    }

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

        /** AR name (see [arName]) for a still device — used to suppress a stale moving badge. */
        const val AR_STILL = "STILL"
    }
}
