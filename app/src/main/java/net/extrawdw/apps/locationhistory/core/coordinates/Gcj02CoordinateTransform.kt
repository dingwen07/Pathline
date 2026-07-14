package net.extrawdw.apps.locationhistory.core.coordinates

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Conventional reverse-engineered GCJ-02 compatibility transform. The inverse is a bounded
 * iterative solution of this same forward kernel; it is not the common one-pass subtraction.
 */
@Singleton
class Gcj02CoordinateTransform @Inject constructor(
    private val mainland: MainlandRegionClassifier,
) : ChinaCoordinateTransform {

    override fun wgs84ToGcj02(value: Wgs84Coordinate): TransformResult<Gcj02Coordinate> {
        validate(value.latitude, value.longitude)?.let { return it }
        // Preserve the caller's original IEEE-754 values exactly outside the selected mask.
        if (!mainland.contains(value.latitude, value.longitude)) {
            return TransformResult.Success(Gcj02Coordinate(value.latitude, value.longitude))
        }
        return TransformResult.Success(forwardKernel(value.latitude, value.longitude))
    }

    override fun gcj02ToWgs84(value: Gcj02Coordinate): TransformResult<Wgs84Coordinate> {
        validate(value.latitude, value.longitude)?.let { return it }
        if (!mainland.mightContain(value.latitude, value.longitude)) {
            return TransformResult.Success(Wgs84Coordinate(value.latitude, value.longitude))
        }

        var estimateLat = value.latitude
        var estimateLon = value.longitude
        repeat(MAX_INVERSE_ITERATIONS) {
            // Deliberately ungated. Calling wgs84ToGcj02 here gives the wrong identity branch near
            // a polygon edge before the recovered WGS candidate has been classified.
            val projected = forwardKernel(estimateLat, estimateLon)
            val residualLat = projected.latitude - value.latitude
            val residualLon = projected.longitude - value.longitude
            estimateLat -= residualLat
            estimateLon -= residualLon
            if (maxOf(abs(residualLat), abs(residualLon)) <= INVERSE_TOLERANCE_DEGREES) {
                return if (mainland.contains(estimateLat, estimateLon)) {
                    TransformResult.Success(Wgs84Coordinate(estimateLat, estimateLon))
                } else {
                    // Hong Kong, Macao, Taiwan and neighboring points take the exact identity path.
                    TransformResult.Success(Wgs84Coordinate(value.latitude, value.longitude))
                }
            }
        }
        return TransformResult.Failure(TransformResult.Reason.NON_CONVERGENT)
    }

    internal fun forwardKernel(latitude: Double, longitude: Double): Gcj02Coordinate {
        var dLat = transformLatitude(longitude - 105.0, latitude - 35.0)
        var dLon = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radLat = Math.toRadians(latitude)
        var magic = sin(radLat)
        magic = 1.0 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / ((A * (1.0 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = dLon * 180.0 / (A / sqrtMagic * cos(radLat) * Math.PI)
        return Gcj02Coordinate(latitude + dLat, longitude + dLon)
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
                0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) *
                2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x +
                0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) *
                2.0 / 3.0
        return result
    }

    private fun validate(latitude: Double, longitude: Double): TransformResult.Failure? = when {
        !latitude.isFinite() || !longitude.isFinite() ->
            TransformResult.Failure(TransformResult.Reason.INVALID_INPUT)

        latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ->
            TransformResult.Failure(TransformResult.Reason.OUTSIDE_SUPPORTED_RANGE)

        else -> null
    }

    private companion object {
        const val A = 6_378_245.0
        const val EE = 0.00669342162296594323
        const val MAX_INVERSE_ITERATIONS = 30
        const val INVERSE_TOLERANCE_DEGREES = 1e-7
    }
}
