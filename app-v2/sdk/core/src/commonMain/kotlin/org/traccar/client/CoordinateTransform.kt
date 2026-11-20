package org.traccar.client

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val latitude: Double, val longitude: Double)

// Krasovsky 1940 ellipsoid used by GCJ-02
private const val SEMI_MAJOR_AXIS = 6378245.0
private const val ECCENTRICITY_SQUARED = 0.00669342162296594323

// ~1e-9 degrees is about 0.1 mm, far below GPS accuracy
private const val TOLERANCE = 1e-9
private const val MAX_ITERATIONS = 30

/**
 * Rough bounding box of mainland China. GCJ-02 offsets are only applied inside it, so points outside
 * stay unchanged. It also covers parts of neighbouring countries, so callers should only convert
 * coordinates their provider reports as GCJ-02 (AMap `coordType`).
 */
internal fun isOutsideChina(latitude: Double, longitude: Double): Boolean =
    !(longitude >= 72.004 && longitude <= 137.8347 && latitude >= 0.8293 && latitude <= 55.8271)

/** GCJ-02 offset (in degrees) for a WGS-84 point, without the China range check. */
private fun gcj02Offset(latitude: Double, longitude: Double): LatLon {
    val x = longitude - 105.0
    val y = latitude - 35.0
    var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    dLat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    dLat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
    dLat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
    var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    dLon += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    dLon += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
    dLon += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0

    val radLat = latitude / 180.0 * PI
    val sinLat = sin(radLat)
    val magic = 1 - ECCENTRICITY_SQUARED * sinLat * sinLat
    val sqrtMagic = sqrt(magic)
    dLat = dLat * 180.0 / (SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED) / (magic * sqrtMagic) * PI)
    dLon = dLon * 180.0 / (SEMI_MAJOR_AXIS / sqrtMagic * cos(radLat) * PI)
    return LatLon(dLat, dLon)
}

internal fun wgs84ToGcj02(latitude: Double, longitude: Double): LatLon {
    if (isOutsideChina(latitude, longitude)) return LatLon(latitude, longitude)
    val offset = gcj02Offset(latitude, longitude)
    return LatLon(latitude + offset.latitude, longitude + offset.longitude)
}

/**
 * Converts a GCJ-02 coordinate to WGS-84 by iteratively inverting the forward transform.
 * Points outside China and non-finite input are returned unchanged.
 */
fun gcj02ToWgs84(latitude: Double, longitude: Double): LatLon {
    if (!latitude.isFinite() || !longitude.isFinite() || isOutsideChina(latitude, longitude)) {
        return LatLon(latitude, longitude)
    }
    // The range check is decided once on the input; iterating with the raw offset keeps the
    // inversion continuous when the estimate crosses the edge of the box.
    var lat = latitude
    var lon = longitude
    repeat(MAX_ITERATIONS) {
        val offset = gcj02Offset(lat, lon)
        val dLat = latitude - (lat + offset.latitude)
        val dLon = longitude - (lon + offset.longitude)
        lat += dLat
        lon += dLon
        if (abs(dLat) < TOLERANCE && abs(dLon) < TOLERANCE) return LatLon(lat, lon)
    }
    return LatLon(lat, lon)
}
