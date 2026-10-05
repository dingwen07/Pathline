package net.extrawdw.apps.locationhistory.service

import android.location.Location
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.enrich.DeviceContext
import net.extrawdw.apps.locationhistory.data.enrich.MotionBurst

/** Maps measured facts only; absent movement evidence stays unknown, never inferred stationary. */
internal fun Location.toLocationSample(
    context: DeviceContext,
    state: DevicePhysicalState = DevicePhysicalState.UNKNOWN,
    confidence: Float = 0f,
    arActivity: String? = null,
    arConfidence: Int? = null,
    burst: MotionBurst? = null,
    stepDelta: Int? = null,
): LocationSampleEntity = LocationSampleEntity(
    timestampMs = time,
    dayEpoch = TimeBuckets.dayEpoch(time),
    latitude = latitude,
    longitude = longitude,
    altitude = if (hasAltitude()) altitude else null,
    accuracy = if (hasAccuracy()) accuracy else null,
    verticalAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    bearing = if (hasBearing()) bearing else null,
    bearingAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    speed = if (hasSpeed()) speed else null,
    speedAccuracyMetersPerSecond = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    provider = provider,
    isMock = isMock,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    satelliteCount = extras?.getInt("satellites")?.takeIf { it > 0 },
    batteryPct = context.batteryPct,
    isCharging = context.isCharging,
    networkTransport = context.networkTransport,
    networkTypeName = context.networkTypeName,
    cellSignalDbm = context.cellSignalDbm,
    hasCellService = context.hasCellService,
    wifiSsid = context.wifiSsid,
    wifiBssid = context.wifiBssid,
    screenOn = context.screenOn,
    arActivity = arActivity,
    arConfidence = arConfidence,
    devicePhysicalState = state,
    devicePhysicalStateConfidence = confidence,
    motionVariance = burst?.accelVariance,
    stepCadenceHz = burst?.stepCadenceHz,
    gravityAngleDeltaDeg = burst?.gravityAngleDeltaDeg,
    pressureHpa = burst?.pressureHpa,
    stepDelta = stepDelta,
)
