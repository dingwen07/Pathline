package net.extrawdw.apps.locationhistory.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders location samples as a Google Maps tile overlay.
 *
 * A marker per point gets expensive once long ranges have tens of thousands of samples. Tiles keep
 * the map overlay count small and cap redundant work in screen space: when zoomed out, multiple
 * samples falling into the same tiny screen-space cell are visually indistinguishable, so only the
 * first one is painted. Zooming in shrinks the real-world area represented by that cell and reveals
 * the points again.
 */
internal class DotTileProvider(
    points: List<LatLng>,
    dotDiameterPx: Float,
    screenDensity: Float,
    private val cameraZoom: Float,
    color: Int,
) : TileProvider {
    private val projectedPoints = points.mapNotNull(::project)
    private val tileSizePx = (BASE_TILE_SIZE * screenDensity)
        .roundToInt()
        .coerceAtLeast(BASE_TILE_SIZE)
    private val targetRadiusPx = (dotDiameterPx / 2f).coerceAtLeast(0.5f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val zoomIndexes = object : LinkedHashMap<Int, Map<Long, IntArray>>(
        MAX_CACHED_ZOOMS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Long, IntArray>>): Boolean =
            size > MAX_CACHED_ZOOMS
    }

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        if (projectedPoints.isEmpty() || zoom < 0 || zoom > MAX_ZOOM) return TileProvider.NO_TILE
        val tileCount = 1 shl zoom
        if (y !in 0 until tileCount) return TileProvider.NO_TILE

        val index = zoomIndex(zoom, tileCount)
        val candidateKeys = candidateTileKeys(x, y, tileCount)
        if (candidateKeys.none { index.containsKey(it) }) return TileProvider.NO_TILE

        val radiusPx = radiusForTileZoom(zoom)
        val bitmap = Bitmap.createBitmap(tileSizePx, tileSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val capGridSize = tileSizePx * DENSITY_CAP_GRID_MULTIPLIER
        val touchedCells = BooleanArray(capGridSize * capGridSize)
        var drew = false

        for (key in candidateKeys) {
            val pointIndexes = index[key] ?: continue
            for (pointIndex in pointIndexes) {
                val point = projectedPoints[pointIndex]
                var tileLocalX = point.x * tileCount - x
                if (tileLocalX < -1.0) tileLocalX += tileCount.toDouble()
                if (tileLocalX > 2.0) tileLocalX -= tileCount.toDouble()

                val px = (tileLocalX * tileSizePx).toFloat()
                val py = ((point.y * tileCount - y) * tileSizePx).toFloat()
                if (px < -radiusPx || px > tileSizePx + radiusPx ||
                    py < -radiusPx || py > tileSizePx + radiusPx
                ) {
                    continue
                }

                val cellX = floor(px * DENSITY_CAP_GRID_MULTIPLIER).toInt()
                    .coerceIn(0, capGridSize - 1)
                val cellY = floor(py * DENSITY_CAP_GRID_MULTIPLIER).toInt()
                    .coerceIn(0, capGridSize - 1)
                val cellKey = cellY * capGridSize + cellX
                if (touchedCells[cellKey]) continue
                touchedCells[cellKey] = true

                canvas.drawCircle(px, py, radiusPx, paint)
                drew = true
            }
        }

        if (!drew) return TileProvider.NO_TILE
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return Tile(tileSizePx, tileSizePx, bytes)
    }

    private fun radiusForTileZoom(tileZoom: Int): Float {
        val tileScaleOnScreen = 2.0.pow(cameraZoom.toDouble() - tileZoom.toDouble())
            .coerceIn(MIN_TILE_SCALE, MAX_TILE_SCALE)
        return (targetRadiusPx / tileScaleOnScreen).toFloat().coerceAtLeast(0.5f)
    }

    private fun zoomIndex(zoom: Int, tileCount: Int): Map<Long, IntArray> = synchronized(zoomIndexes) {
        zoomIndexes[zoom] ?: buildZoomIndex(tileCount).also { zoomIndexes[zoom] = it }
    }

    private fun buildZoomIndex(tileCount: Int): Map<Long, IntArray> {
        val mutableBuckets = HashMap<Long, MutableList<Int>>()
        for (i in projectedPoints.indices) {
            val point = projectedPoints[i]
            val tileX = floor(point.x * tileCount).toInt().floorMod(tileCount)
            val tileY = floor(point.y * tileCount).toInt().coerceIn(0, tileCount - 1)
            val key = tileKey(tileX, tileY)
            mutableBuckets.getOrPut(key) { ArrayList() }.add(i)
        }
        return mutableBuckets.mapValues { (_, values) -> values.toIntArray() }
    }

    private fun candidateTileKeys(x: Int, y: Int, tileCount: Int): LongArray {
        val tileX = x.floorMod(tileCount)
        val out = LongArray(9)
        var count = 0
        for (dx in -1..1) {
            val candidateX = (tileX + dx).floorMod(tileCount)
            for (dy in -1..1) {
                val candidateY = y + dy
                if (candidateY !in 0 until tileCount) continue
                val key = tileKey(candidateX, candidateY)
                var duplicate = false
                for (i in 0 until count) {
                    if (out[i] == key) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate) out[count++] = key
            }
        }
        return out.copyOf(count)
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private data class ProjectedPoint(val x: Double, val y: Double)

    private companion object {
        const val BASE_TILE_SIZE = 256
        const val MAX_ZOOM = 30
        const val MAX_CACHED_ZOOMS = 4
        const val DENSITY_CAP_GRID_MULTIPLIER = 2
        const val MIN_TILE_SCALE = 0.25
        const val MAX_TILE_SCALE = 4.0

        fun project(latLng: LatLng): ProjectedPoint? {
            val x = ((latLng.longitude + 180.0) / 360.0).mod(1.0)
            val sinLatitude = sin(latLng.latitude * PI / 180.0).coerceIn(-0.9999, 0.9999)
            val y = 0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)
            if (!x.isFinite() || !y.isFinite()) return null
            return ProjectedPoint(x, y.coerceIn(0.0, 1.0))
        }

        fun tileKey(x: Int, y: Int): Long =
            (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
    }
}
