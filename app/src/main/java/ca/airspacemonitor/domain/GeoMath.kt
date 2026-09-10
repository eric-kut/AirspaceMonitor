package ca.airspacemonitor.domain

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

    private const val MITER_LIMIT = 2.0

    /**
     * Outward buffer of [polygon] by [offsetKm]: per-edge normal offset with miter
     * joins in a local equirectangular projection (km) around the vertex-mean
     * centroid, consistent with the flat model used by [pointInPolygon]. Returns
     * null when the result would be degenerate (offset >= polygon inradius) —
     * callers should fall back to the bounding-circle behavior. offsetKm <= 0
     * returns a copy of the input.
     */
    fun offsetPolygon(polygon: List<GeoPoint>, offsetKm: Double): List<GeoPoint>? {
        if (polygon.size < 3) return null
        if (offsetKm <= 1e-9) return polygon.toList()

        val lat0 = polygon.sumOf { it.lat } / polygon.size
        val lon0 = polygon.sumOf { it.lon } / polygon.size
        val cosLat0 = cos(Math.toRadians(lat0))
        val degToKmY = Math.PI / 180.0 * EARTH_RADIUS_KM
        val degToKmX = degToKmY * cosLat0

        fun toX(lon: Double) = (lon - lon0) * degToKmX
        fun toY(lat: Double) = (lat - lat0) * degToKmY
        fun toLon(x: Double) = lon0 + x / degToKmX
        fun toLat(y: Double) = lat0 + y / degToKmY

        val xs = polygon.map { toX(it.lon) }
        val ys = polygon.map { toY(it.lat) }
        val n = polygon.size

        // Degenerate guard: offset at least as large as the inradius collapses the shape.
        val inradius = (0 until n).minOf {
            pointToSegmentKm(0.0, 0.0, xs[it], ys[it], xs[(it + 1) % n], ys[(it + 1) % n])
        }
        if (offsetKm >= inradius) return null

        // Signed shoelace area: positive = counter-clockwise in the projected plane.
        var area2 = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area2 += xs[i] * ys[j] - xs[j] * ys[i]
        }
        val ccw = area2 > 0

        fun edgeNormal(i: Int): Pair<Double, Double> {
            val a = i
            val b = (i + 1) % n
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val len = sqrt(dx * dx + dy * dy)
            val nx = dy / len
            val ny = -dx / len
            // For CCW polygons (dy, -dx) points outward; flip for CW.
            return if (ccw) nx to ny else -nx to -ny
        }

        val out = ArrayList<GeoPoint>(n)
        for (i in 0 until n) {
            val prevVertex = i
            val curVertex = (i + 1) % n
            val (nx1, ny1) = edgeNormal(prevVertex)
            val (nx2, ny2) = edgeNormal(curVertex)
            // Offset lines: p1 + t*d1 = p2 + s*d2, where each line passes through its
            // edge offset by the normal.
            val p1x = xs[prevVertex] + nx1 * offsetKm
            val p1y = ys[prevVertex] + ny1 * offsetKm
            val d1x = xs[curVertex] - xs[prevVertex]
            val d1y = ys[curVertex] - ys[prevVertex]
            val p2x = xs[curVertex] + nx2 * offsetKm
            val p2y = ys[curVertex] + ny2 * offsetKm
            val d2x = xs[(curVertex + 1) % n] - xs[curVertex]
            val d2y = ys[(curVertex + 1) % n] - ys[curVertex]
            val denom = d1x * d2y - d1y * d2x
            val jx: Double
            val jy: Double
            if (abs(denom) < 1e-12) {
                // Parallel consecutive edges (collinear vertex): just offset the vertex.
                jx = xs[curVertex] + nx2 * offsetKm
                jy = ys[curVertex] + ny2 * offsetKm
            } else {
                val t = ((p2x - p1x) * d2y - (p2y - p1y) * d2x) / denom
                var ix = p1x + t * d1x
                var iy = p1y + t * d1y
                // Clamp sharp-angle miters so spikes stay within MITER_LIMIT * offset.
                val mx = ix - xs[curVertex]
                val my = iy - ys[curVertex]
                val mLen = sqrt(mx * mx + my * my)
                val maxLen = MITER_LIMIT * offsetKm
                if (mLen > maxLen) {
                    ix = xs[curVertex] + mx / mLen * maxLen
                    iy = ys[curVertex] + my / mLen * maxLen
                }
                jx = ix
                jy = iy
            }
            out.add(GeoPoint(toLat(jy), toLon(jx)))
        }
        return out
    }

    /** Distance from point (px, py) to segment (ax, ay)-(bx, by), projected-plane km. */
    private fun pointToSegmentKm(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }
}