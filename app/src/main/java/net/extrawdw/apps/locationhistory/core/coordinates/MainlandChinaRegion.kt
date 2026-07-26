package net.extrawdw.apps.locationhistory.core.coordinates

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.extrawdw.apps.locationhistory.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

interface MainlandRegionClassifier {
    fun contains(latitude: Double, longitude: Double): Boolean
    fun mightContain(latitude: Double, longitude: Double, marginDegrees: Double = 0.02): Boolean
}

/**
 * Deterministic mainland compatibility mask generated from geoBoundaries CHN ADM1. This is a
 * provider-behavior boundary, not a legal/geodetic assertion.
 *
 * Source: geoBoundaries CHN-ADM1-43563684 at commit 9469f09
 * Source SHA-256: 3a00467a0db9b4136facb5f2f3d0edbfd96adb15651cfdf63991da9281030e85
 * License metadata for this boundary marks it Public Domain.
 */
@Singleton
class MainlandChinaRegion @Inject constructor(
    @ApplicationContext context: Context,
) : MainlandRegionClassifier {
    private val geometry: RegionGeometry = context.resources.openRawResource(
        R.raw.mainland_china_adm1
    ).bufferedReader().use { RegionGeometry.parse(it.readText()) }

    override fun contains(latitude: Double, longitude: Double): Boolean =
        geometry.contains(latitude, longitude)

    /** Cheap inverse-path rejection, expanded to cover the sub-kilometre GCJ displacement. */
    override fun mightContain(
        latitude: Double,
        longitude: Double,
        marginDegrees: Double,
    ): Boolean =
        geometry.bounds.contains(latitude, longitude, marginDegrees)

    internal data class Bounds(
        val minLatitude: Double,
        val minLongitude: Double,
        val maxLatitude: Double,
        val maxLongitude: Double,
    ) {
        fun contains(latitude: Double, longitude: Double, margin: Double = 0.0): Boolean =
            latitude >= minLatitude - margin && latitude <= maxLatitude + margin &&
                    longitude >= minLongitude - margin && longitude <= maxLongitude + margin

        companion object {
            fun ofRings(rings: List<Ring>): Bounds {
                var minLat = Double.POSITIVE_INFINITY
                var minLon = Double.POSITIVE_INFINITY
                var maxLat = Double.NEGATIVE_INFINITY
                var maxLon = Double.NEGATIVE_INFINITY
                rings.forEach { ring ->
                    for (i in ring.points.indices step 2) {
                        val lon = ring.points[i]
                        val lat = ring.points[i + 1]
                        minLat = minOf(minLat, lat)
                        minLon = minOf(minLon, lon)
                        maxLat = maxOf(maxLat, lat)
                        maxLon = maxOf(maxLon, lon)
                    }
                }
                return Bounds(minLat, minLon, maxLat, maxLon)
            }

            fun ofFeatures(features: List<Feature>): Bounds = Bounds(
                minLatitude = features.minOf { it.bounds.minLatitude },
                minLongitude = features.minOf { it.bounds.minLongitude },
                maxLatitude = features.maxOf { it.bounds.maxLatitude },
                maxLongitude = features.maxOf { it.bounds.maxLongitude },
            )
        }
    }

    internal data class Ring(val points: DoubleArray)

    internal data class Polygon(val rings: List<Ring>, val bounds: Bounds) {
        fun contains(latitude: Double, longitude: Double): Boolean {
            if (!bounds.contains(latitude, longitude)) return false
            val outer = ringPosition(rings.first(), latitude, longitude)
            if (outer == Position.OUTSIDE) return false
            if (outer == Position.EDGE) return true
            for (hole in rings.drop(1)) {
                if (ringPosition(hole, latitude, longitude) != Position.OUTSIDE) return false
            }
            return true
        }
    }

    internal data class Feature(
        val name: String,
        val polygons: List<Polygon>,
        val bounds: Bounds,
    )

    internal data class RegionGeometry(
        val features: List<Feature>,
        val bounds: Bounds,
    ) {
        fun contains(latitude: Double, longitude: Double): Boolean {
            if (!latitude.isFinite() || !longitude.isFinite()) return false
            if (!bounds.contains(latitude, longitude)) return false
            return features.any { feature ->
                feature.bounds.contains(latitude, longitude) &&
                        feature.polygons.any { it.contains(latitude, longitude) }
            }
        }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String): RegionGeometry {
                val root = json.parseToJsonElement(text).jsonObject
                val features = root.getValue("features").jsonArray.map(::parseFeature)
                val names = features.mapTo(linkedSetOf()) { it.name }
                check(names == EXPECTED_MAINLAND_UNITS) {
                    "Unexpected mainland boundary units: $names"
                }
                return RegionGeometry(features, Bounds.ofFeatures(features))
            }

            private fun parseFeature(element: kotlinx.serialization.json.JsonElement): Feature {
                val feature = element.jsonObject
                val name = feature.getValue("properties").jsonObject
                    .getValue("shapeName").jsonPrimitive.content
                val geometry = feature.getValue("geometry").jsonObject
                val coordinates = geometry.getValue("coordinates").jsonArray
                val polygons = when (geometry.getValue("type").jsonPrimitive.content) {
                    "Polygon" -> listOf(parsePolygon(coordinates))
                    "MultiPolygon" -> coordinates.map { parsePolygon(it.jsonArray) }
                    else -> error("Unsupported geometry for $name")
                }
                return Feature(
                    name = name,
                    polygons = polygons,
                    bounds = Bounds.ofRings(polygons.flatMap { it.rings }),
                )
            }

            private fun parsePolygon(coordinates: JsonArray): Polygon {
                val rings = coordinates.map { ringElement ->
                    val pairs = ringElement.jsonArray
                    val values = DoubleArray(pairs.size * 2)
                    pairs.forEachIndexed { index, pairElement ->
                        val pair = pairElement.jsonArray
                        values[index * 2] = pair[0].jsonPrimitive.content.toDouble()
                        values[index * 2 + 1] = pair[1].jsonPrimitive.content.toDouble()
                    }
                    Ring(values)
                }
                require(rings.isNotEmpty()) { "Polygon has no outer ring" }
                return Polygon(rings, Bounds.ofRings(rings))
            }
        }
    }

    private enum class Position { OUTSIDE, INSIDE, EDGE }

    private companion object {
        // The source uses these exact historical labels (including its Guangdong/Ningxia spelling).
        val EXPECTED_MAINLAND_UNITS: Set<String> = linkedSetOf(
            "Hainan Province",
            "Guangxi Zhuang Autonomous Region",
            "Fujian Province",
            "Yunnan Province",
            "Guizhou Province",
            "Jiangxi Province",
            "Hunan Province",
            "Zhejiang Province",
            "Shanghai Municipality",
            "Chongqing Municipality",
            "Hubei Province",
            "Sichuan Province",
            "Anhui Province",
            "Jiangsu Province",
            "Henan Province",
            "Tibet Autonomous Region",
            "Shandong Province",
            "Qinghai Province",
            "Ningxia Ningxia Hui Autonomous Region",
            "Shaanxi Province",
            "Tianjin Municipality",
            "Shanxi Province",
            "Beijing Municipality",
            "Gansu Province",
            "Hebei Province",
            "Liaoning Province",
            "Jilin Province",
            "Xinjiang Uyghur Autonomous Region",
            "Inner Mongolia Autonomous Region",
            "Heilongjiang Province",
            "Guangzhou Province",
        )

        fun ringPosition(ring: Ring, latitude: Double, longitude: Double): Position {
            val points = ring.points
            if (points.size < 6) return Position.OUTSIDE
            var inside = false
            var j = points.size - 2
            var i = 0
            while (i < points.size) {
                val xi = points[i]
                val yi = points[i + 1]
                val xj = points[j]
                val yj = points[j + 1]
                if (onSegment(longitude, latitude, xj, yj, xi, yi)) return Position.EDGE
                val crosses = (yi > latitude) != (yj > latitude) &&
                        longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi
                if (crosses) inside = !inside
                j = i
                i += 2
            }
            return if (inside) Position.INSIDE else Position.OUTSIDE
        }

        fun onSegment(
            px: Double,
            py: Double,
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
        ): Boolean {
            val cross = (px - ax) * (by - ay) - (py - ay) * (bx - ax)
            if (abs(cross) > 1e-12) return false
            return px >= minOf(ax, bx) - 1e-12 && px <= maxOf(ax, bx) + 1e-12 &&
                    py >= minOf(ay, by) - 1e-12 && py <= maxOf(ay, by) + 1e-12
        }
    }
}
