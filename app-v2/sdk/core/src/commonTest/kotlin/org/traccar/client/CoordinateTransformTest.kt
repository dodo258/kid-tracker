package org.traccar.client

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateTransformTest {

    private fun distanceMeters(a: LatLon, b: LatLon): Double {
        val r = 6371008.8
        val lat1 = a.latitude * PI / 180
        val lat2 = b.latitude * PI / 180
        val dLat = lat2 - lat1
        val dLon = (b.longitude - a.longitude) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    private val chinaCities = listOf(
        LatLon(39.9087, 116.3975), // Beijing
        LatLon(31.2304, 121.4737), // Shanghai
        LatLon(22.5431, 114.0579), // Shenzhen
        LatLon(30.5728, 104.0668), // Chengdu
        LatLon(43.8256, 87.6168), // Urumqi
        LatLon(29.6520, 91.1721), // Lhasa
        LatLon(45.8038, 126.5350), // Harbin
        LatLon(18.2528, 109.5119), // Sanya
    )

    @Test
    fun roundTripInsideChinaIsWithinOneMeter() {
        val points = chinaCities + (18..53).flatMap { lat ->
            (74..134).map { lon -> LatLon(lat + 0.37, lon + 0.61) }
        }
        for (wgs in points) {
            val gcj = wgs84ToGcj02(wgs.latitude, wgs.longitude)
            val back = gcj02ToWgs84(gcj.latitude, gcj.longitude)
            val error = distanceMeters(wgs, back)
            assertTrue(error < 1.0, "round trip error $error m at $wgs")
        }
    }

    @Test
    fun conversionMovesPointsInsideChina() {
        for (gcj in chinaCities) {
            val shift = distanceMeters(gcj, gcj02ToWgs84(gcj.latitude, gcj.longitude))
            assertTrue(shift in 50.0..1000.0, "unexpected shift $shift m at $gcj")
        }
    }

    @Test
    fun pointsOutsideChinaAreUnchanged() {
        val outside = listOf(
            LatLon(51.5074, -0.1278), // London
            LatLon(40.7128, -74.0060), // New York
            LatLon(35.6762, 139.6503), // Tokyo
            LatLon(-33.8688, 151.2093), // Sydney
            LatLon(-23.5505, -46.6333), // Sao Paulo
            LatLon(64.1466, -21.9426), // Reykjavik
            LatLon(0.0, 0.0),
        )
        for (point in outside) {
            assertEquals(point, gcj02ToWgs84(point.latitude, point.longitude))
            assertEquals(point, wgs84ToGcj02(point.latitude, point.longitude))
        }
    }

    @Test
    fun edgesAndInvalidInputDoNotThrow() {
        val eps = 1e-7
        val edges = listOf(0.8293, 55.8271).flatMap { lat ->
            listOf(lat - eps, lat, lat + eps).flatMap { edgeLat ->
                (72..137).map { lon -> LatLon(edgeLat, lon + 0.5) }
            }
        } + listOf(72.004, 137.8347).flatMap { lon ->
            listOf(lon - eps, lon, lon + eps).flatMap { edgeLon ->
                (1..55).map { lat -> LatLon(lat + 0.5, edgeLon) }
            }
        } + listOf(
            LatLon(90.0, 180.0),
            LatLon(-90.0, -180.0),
            LatLon(90.0, 116.0),
            LatLon(0.8293, 72.004),
            LatLon(55.8271, 137.8347),
        )
        for (point in edges) {
            val wgs = gcj02ToWgs84(point.latitude, point.longitude)
            assertTrue(wgs.latitude.isFinite() && wgs.longitude.isFinite(), "non-finite result at $point")
            assertTrue(distanceMeters(point, wgs) < 1000.0, "result too far at $point")
        }

        val invalid = listOf(
            LatLon(Double.NaN, 116.0),
            LatLon(39.9, Double.NaN),
            LatLon(Double.POSITIVE_INFINITY, 116.0),
            LatLon(39.9, Double.NEGATIVE_INFINITY),
        )
        for (point in invalid) {
            val result = gcj02ToWgs84(point.latitude, point.longitude)
            assertEquals(point.latitude, result.latitude)
            assertEquals(point.longitude, result.longitude)
        }
    }
}
