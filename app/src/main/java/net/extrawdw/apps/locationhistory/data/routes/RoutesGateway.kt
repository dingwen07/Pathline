package net.extrawdw.apps.locationhistory.data.routes

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.extrawdw.apps.locationhistory.BuildConfig
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import java.net.HttpURLConnection
import java.net.URL

/**
 * A single Google Routes `computeRoutes` request between two Pathline saved places. Travel mode and
 * preferences map straight onto the Routes API; only the parameters that are cheap to expose are
 * carried (no geometry, no arbitrary coordinates -- the provider only ever passes saved places).
 */
data class TravelTimeRequest(
    val travelMode: String,
    val departureTimeMs: Long?,
    val arrivalTimeMs: Long?,
    /** Preferred transit sub-modes (BUS/SUBWAY/...); only sent for [travelMode] == TRANSIT. */
    val transitModes: List<String>,
    /** LESS_WALKING / FEWER_TRANSFERS; only sent for TRANSIT. */
    val transitRoutingPreference: String?,
    /** TRAFFIC_UNAWARE / TRAFFIC_AWARE / TRAFFIC_AWARE_OPTIMAL; only sent for DRIVE / TWO_WHEELER. */
    val drivingRoutingPreference: String?,
    val avoidTolls: Boolean,
    val avoidHighways: Boolean,
    val avoidFerries: Boolean,
    val computeAlternatives: Boolean,
    val languageCode: String?,
    val regionCode: String?,
)

/** One route summary returned by [RoutesGateway]; transit-only fields are null for other modes. */
data class TravelTimeEstimate(
    val routeIndex: Int,
    val originPlaceId: Long,
    val destinationPlaceId: Long,
    val travelMode: String,
    val durationSeconds: Long,
    /** Duration ignoring live traffic (driving); null when Google omits it. */
    val staticDurationSeconds: Long?,
    val distanceMeters: Int?,
    val routeDepartureTimeMs: Long?,
    val routeArrivalTimeMs: Long?,
    val firstTransitDepartureTimeMs: Long?,
    val lastTransitArrivalTimeMs: Long?,
    val transitModes: String?,
    val stepTravelModes: String?,
    val localizedDuration: String?,
    val localizedDistance: String?,
    val localizedFare: String?,
)

/**
 * Thin client over Google Routes `computeRoutes` for travel-time estimates between two saved places.
 * Network failures surface as [IOException]; the caller (the ContentProvider) maps those to an empty
 * result so a flaky network never crashes a consumer or leaks across the binder boundary.
 */
@Singleton
class RoutesGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pathline's own signing-cert SHA-1 (hex, no separators). The MAPS_API_KEY is Android-app
     * restricted, so a raw web-service call to Routes must present this app's identity via the
     * `X-Android-Package` / `X-Android-Cert` headers -- otherwise Google answers 403
     * API_KEY_ANDROID_APP_BLOCKED ("Requests from this Android client application <empty> are
     * blocked"). The provider runs in Pathline's process, so this cert is one of the key's allowlist.
     */
    private val androidCertSha1: String by lazy {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: error("No signing certificate for ${context.packageName}")
        MessageDigest.getInstance("SHA-1").digest(signer.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    suspend fun travelTimes(
        origin: PlaceEntity,
        destination: PlaceEntity,
        request: TravelTimeRequest,
        nowMs: Long,
    ): List<TravelTimeEstimate> = withContext(Dispatchers.IO) {
        val key = BuildConfig.MAPS_API_KEY
        check(key.isNotBlank()) { "Google Maps API key is not configured" }

        val body = requestBody(origin, destination, request, nowMs)
        val response = postRoutes(key, body.toString())
        parseResponse(response, origin.id, destination.id, request, nowMs)
    }

    private fun requestBody(
        origin: PlaceEntity,
        destination: PlaceEntity,
        request: TravelTimeRequest,
        nowMs: Long,
    ): JsonObject = buildJsonObject {
        put("origin", waypoint(origin.latitude, origin.longitude))
        put("destination", waypoint(destination.latitude, destination.longitude))
        put("travelMode", JsonPrimitive(request.travelMode))
        put("computeAlternativeRoutes", JsonPrimitive(request.computeAlternatives))

        val transit = request.travelMode == "TRANSIT"
        val driving = request.travelMode == "DRIVE" || request.travelMode == "TWO_WHEELER"

        // arrivalTime is only valid for transit; the provider rejects it for other modes.
        if (transit && request.arrivalTimeMs != null) {
            put(
                "arrivalTime",
                JsonPrimitive(Instant.ofEpochMilli(request.arrivalTimeMs).toString())
            )
        } else if (request.departureTimeMs != null) {
            put(
                "departureTime",
                JsonPrimitive(Instant.ofEpochMilli(request.departureTimeMs).toString())
            )
        } else if (transit) {
            // Transit needs an anchor time; default to now. Other modes are left time-free so a
            // traffic-unaware estimate is returned without a (rejected) past departureTime.
            put("departureTime", JsonPrimitive(Instant.ofEpochMilli(nowMs).toString()))
        }

        if (driving && request.drivingRoutingPreference != null) {
            put("routingPreference", JsonPrimitive(request.drivingRoutingPreference))
        }
        if (driving && (request.avoidTolls || request.avoidHighways || request.avoidFerries)) {
            put("routeModifiers", buildJsonObject {
                if (request.avoidTolls) put("avoidTolls", JsonPrimitive(true))
                if (request.avoidHighways) put("avoidHighways", JsonPrimitive(true))
                if (request.avoidFerries) put("avoidFerries", JsonPrimitive(true))
            })
        }
        if (transit && (request.transitModes.isNotEmpty() || request.transitRoutingPreference != null)) {
            put("transitPreferences", buildJsonObject {
                if (request.transitModes.isNotEmpty()) {
                    put("allowedTravelModes", buildJsonArray {
                        request.transitModes.forEach { add(JsonPrimitive(it)) }
                    })
                }
                request.transitRoutingPreference?.let {
                    put(
                        "routingPreference",
                        JsonPrimitive(it)
                    )
                }
            })
        }

        if (request.languageCode != null) put("languageCode", JsonPrimitive(request.languageCode))
        if (request.regionCode != null) put("regionCode", JsonPrimitive(request.regionCode))
    }

    private fun waypoint(lat: Double, lng: Double): JsonObject = buildJsonObject {
        put("location", buildJsonObject {
            put("latLng", buildJsonObject {
                put("latitude", JsonPrimitive(lat))
                put("longitude", JsonPrimitive(lng))
            })
        })
    }

    private fun postRoutes(apiKey: String, body: String): String {
        val conn = (URL(ROUTES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
            // Identify the calling app so the Android-restricted key authorizes this web-service call.
            setRequestProperty("X-Android-Package", context.packageName)
            setRequestProperty("X-Android-Cert", androidCertSha1)
        }
        // disconnect() must run on every path -- a leaked connection holds a socket/fd, and under
        // the binder thread pool that adds up fast. (Review finding #3.)
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("Routes API failed with HTTP $status: $response")
            }
            return response
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(
        response: String,
        originPlaceId: Long,
        destinationPlaceId: Long,
        request: TravelTimeRequest,
        nowMs: Long,
    ): List<TravelTimeEstimate> {
        // A malformed 200 body must not throw out of the provider; treat unparseable as "no routes".
        val routes = runCatching {
            json.parseToJsonElement(response).jsonObject["routes"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return routes.mapIndexedNotNull { index, routeElement ->
            val route = routeElement.jsonObject
            val durationSeconds = route["duration"]?.stringContent()?.durationSeconds()
                ?: return@mapIndexedNotNull null
            val baseDepartureMs = when {
                request.travelMode == "TRANSIT" && request.arrivalTimeMs != null ->
                    request.arrivalTimeMs - durationSeconds * 1000L

                request.departureTimeMs != null -> request.departureTimeMs
                else -> nowMs
            }
            val baseArrivalMs = when {
                request.travelMode == "TRANSIT" && request.arrivalTimeMs != null -> request.arrivalTimeMs
                else -> baseDepartureMs + durationSeconds * 1000L
            }
            val steps = route["legs"]?.jsonArray.orEmpty()
                .flatMap { leg -> leg.jsonObject["steps"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }
            val stepModes = steps.mapNotNull { it["travelMode"]?.stringContent() }.distinct()
            val vehicleTypes = steps.mapNotNull {
                it["transitDetails"]?.jsonObject
                    ?.get("transitLine")?.jsonObject
                    ?.get("vehicle")?.jsonObject
                    ?.get("type")?.stringContent()
            }.distinct()
            val stopDetails = steps.mapNotNull {
                it["transitDetails"]?.jsonObject?.get("stopDetails")?.jsonObject
            }
            TravelTimeEstimate(
                routeIndex = index,
                originPlaceId = originPlaceId,
                destinationPlaceId = destinationPlaceId,
                travelMode = request.travelMode,
                durationSeconds = durationSeconds,
                staticDurationSeconds = route["staticDuration"]?.stringContent()?.durationSeconds(),
                distanceMeters = route["distanceMeters"]?.intContent(),
                routeDepartureTimeMs = baseDepartureMs,
                routeArrivalTimeMs = baseArrivalMs,
                // Earliest boarding / latest alighting by actual time -- step order in the response
                // is not guaranteed monotonic, so take min/max rather than first/last by position.
                firstTransitDepartureTimeMs = stopDetails
                    .mapNotNull { it["departureTime"]?.stringContent()?.instantMsOrNull() }
                    .minOrNull(),
                lastTransitArrivalTimeMs = stopDetails
                    .mapNotNull { it["arrivalTime"]?.stringContent()?.instantMsOrNull() }
                    .maxOrNull(),
                transitModes = vehicleTypes.joinToStringOrNull(),
                stepTravelModes = stepModes.joinToStringOrNull(),
                localizedDuration = route.localizedText("duration"),
                localizedDistance = route.localizedText("distance"),
                localizedFare = route.moneyText(),
            )
        }
    }

    private fun JsonObject.localizedText(key: String): String? =
        this["localizedValues"]?.jsonObject?.get(key)?.jsonObject?.get("text")?.stringContent()

    private fun JsonObject.moneyText(): String? {
        val money =
            this["travelAdvisory"]?.jsonObject?.get("transitFare")?.jsonObject ?: return null
        val currency = money["currencyCode"]?.stringContent() ?: return null
        val units = money["units"]?.stringContent()?.toLongOrNull() ?: 0L
        val nanos = money["nanos"]?.stringContent()?.toIntOrNull() ?: 0
        val amount = units + nanos / 1_000_000_000.0
        return "$currency ${String.format(Locale.US, "%.2f", amount)}"
    }

    private fun JsonElement.stringContent(): String? =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun JsonElement.intContent(): Int? =
        stringContent()?.toIntOrNull()

    private fun String.durationSeconds(): Long? =
        removeSuffix("s").toDoubleOrNull()?.toLong()

    private fun String.instantMsOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

    private fun List<String>.joinToStringOrNull(): String? =
        if (isEmpty()) null else joinToString(",")

    private companion object {
        const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
        const val TIMEOUT_MS = 8_000
        const val FIELD_MASK =
            "routes.duration," +
                    "routes.staticDuration," +
                    "routes.distanceMeters," +
                    "routes.localizedValues.duration," +
                    "routes.localizedValues.distance," +
                    "routes.travelAdvisory.transitFare," +
                    "routes.legs.steps.travelMode," +
                    "routes.legs.steps.transitDetails.stopDetails.departureTime," +
                    "routes.legs.steps.transitDetails.stopDetails.arrivalTime," +
                    "routes.legs.steps.transitDetails.transitLine.vehicle.type"
    }
}
