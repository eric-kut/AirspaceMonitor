package ca.trafficwatcher.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin geodesy helpers. Mean-earth-radius spherical model, which is plenty
 * accurate for a notification radius app (errors well under 0.5%).
 */
object GeoMath {

    private const val EARTH_RADIUS_KM = 6371.0088

    /** Great-circle distance between two points, in kilometers. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun haversineKm(from: GeoPoint, to: GeoPoint): Double =
        haversineKm(from.lat, from.lon, to.lat, to.lon)

    /** Initial bearing from [from] to [to], normalized to [0, 360). */
    fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    private val COMPASS_8 = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compass8(bearingDeg: Double): String {
        val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
        val index = ((normalized + 22.5) / 45.0).toInt() % 8
        return COMPASS_8[index]
    }

    /**
     * Even-odd ray-casting point-in-polygon. Vertices must not cross the
     * antimeridian. Points lying exactly on an edge are counted as inside.
     */
    fun pointInPolygon(p: GeoPoint, polygon: List<GeoPoint>): Boolean {
        require(polygon.size >= 3) { "polygon needs at least 3 vertices" }
        // Boundary check first so edge points have deterministic behavior.
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if (onSegment(p, a, b)) return true
        }
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersects = (pi.lat > p.lat) != (pj.lat > p.lat)
            if (intersects) {
                val latAtCrossing = (pj.lon - pi.lon) * (p.lat - pi.lat) / (pj.lat - pi.lat) + pi.lon
                if (p.lon < latAtCrossing) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun onSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Boolean {
        val cross = (p.lat - a.lat) * (b.lon - a.lon) - (p.lon - a.lon) * (b.lat - a.lat)
        if (abs(cross) > 1e-9) return false
        val withinBox = p.lat in (minOf(a.lat, b.lat) - 1e-9)..(maxOf(a.lat, b.lat) + 1e-9) &&
            p.lon in (minOf(a.lon, b.lon) - 1e-9)..(maxOf(a.lon, b.lon) + 1e-9)
        return withinBox
    }

    /**
     * Destination point given a start, an initial bearing and a distance,
     * using the spherical direct formula.
     */
    fun destinationPoint(start: GeoPoint, bearing: Double, distanceKm: Double): GeoPoint {
        val angular = distanceKm / EARTH_RADIUS_KM
        val theta = Math.toRadians(bearing)
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(theta))
        val lon2 = lon1 + atan2(sin(theta) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0)
    }

    /**
     * Bounding circle of a polygon: vertex-mean centroid plus the distance to the
     * farthest vertex. The API query adds a 10% buffer on top (done by the caller).
     */
    fun polygonBoundingCircle(polygon: List<GeoPoint>): Pair<GeoPoint, Double> {
        require(polygon.size >= 3) { "polygon needs at least 3 vertices" }
        val centroid = GeoPoint(
            polygon.sumOf { it.lat } / polygon.size,
            polygon.sumOf { it.lon } / polygon.size,
        )
        val maxVertexKm = polygon.maxOf { haversineKm(centroid, it) }
        return centroid to maxVertexKm
    }
}