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
     * Outward buffer of [polygon] by [offsetKm]: per-edge normal offset in a local
     * equirectangular projection (km) around the vertex-mean centroid, consistent
     * with the flat model used by [pointInPolygon]. Every corner joins at the
     * intersection of the two offset edge lines: at reflex corners (notch bottom)
     * that intersection is the exact buffer boundary; at convex corners it is the
     * standard miter approximation of the round join, clamped to
     * MITER_LIMIT * offset so sharp spikes stay bounded.
     *
     * Returns null when the result would be degenerate: a zero-area input, or an
     * offset so large it swallows a concave feature (notch) or thin neck — detected
     * as a self-intersection in the output. Convex polygons never degenerate, so
     * they always buffer. Callers fall back to bounding-circle behavior on null.
     * offsetKm <= 0 returns a copy of the input.
     */
    fun offsetPolygon(polygon: List<GeoPoint>, offsetKm: Double): List<GeoPoint>? {
        if (polygon.size < 3) return null
        if (offsetKm <= 1e-9) return polygon.toList()

        // Collapse consecutive duplicates (double taps in the editor) — they have
        // no direction, so edge normals would be NaN.
        val cleaned = ArrayList<GeoPoint>(polygon.size)
        for (p in polygon) {
            val last = cleaned.lastOrNull()
            if (last == null || haversineKm(last, p) > 1e-9) cleaned.add(p)
        }
        if (cleaned.size < 3) return null

        val lat0 = cleaned.sumOf { it.lat } / cleaned.size
        val lon0 = cleaned.sumOf { it.lon } / cleaned.size
        val cosLat0 = cos(Math.toRadians(lat0))
        val degToKmY = Math.PI / 180.0 * EARTH_RADIUS_KM
        val degToKmX = degToKmY * cosLat0

        fun toX(lon: Double) = (lon - lon0) * degToKmX
        fun toY(lat: Double) = (lat - lat0) * degToKmY
        fun toLon(x: Double) = lon0 + x / degToKmX
        fun toLat(y: Double) = lat0 + y / degToKmY

        val xs = cleaned.map { toX(it.lon) }
        val ys = cleaned.map { toY(it.lat) }
        val n = cleaned.size

        // Signed shoelace area: positive = counter-clockwise in the projected plane.
        var area2 = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area2 += xs[i] * ys[j] - xs[j] * ys[i]
        }
        if (abs(area2) < 1e-12) return null
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

        val ox = ArrayList<Double>(n)
        val oy = ArrayList<Double>(n)
        for (i in 0 until n) {
            val prevEdge = (i - 1 + n) % n
            val curVertex = i
            val nextVertex = (i + 1) % n
            val (nx1, ny1) = edgeNormal(prevEdge)
            val (nx2, ny2) = edgeNormal(curVertex)
            val d1x = xs[curVertex] - xs[prevEdge]
            val d1y = ys[curVertex] - ys[prevEdge]
            val d2x = xs[nextVertex] - xs[curVertex]
            val d2y = ys[nextVertex] - ys[curVertex]
            // Cross product of incoming and outgoing edge directions: sign tells
            // convex vs reflex relative to the ring orientation.
            val cross = d1x * d2y - d1y * d2x
            val convex = if (ccw) cross > 1e-12 else cross < -1e-12
            if (abs(cross) <= 1e-12) {
                // Collinear vertex: single point offset along the shared normal.
                ox.add(xs[curVertex] + nx2 * offsetKm)
                oy.add(ys[curVertex] + ny2 * offsetKm)
            } else {
                // Join at the intersection of the two offset edge lines. At a
                // reflex corner this is the exact buffer boundary (no clamp); at a
                // convex corner it is the miter approximation of the round join,
                // clamped so sharp-angle spikes stay within MITER_LIMIT * offset.
                val p1x = xs[prevEdge] + nx1 * offsetKm
                val p1y = ys[prevEdge] + ny1 * offsetKm
                val p2x = xs[curVertex] + nx2 * offsetKm
                val p2y = ys[curVertex] + ny2 * offsetKm
                val denom = d1x * d2y - d1y * d2x
                val t = ((p2x - p1x) * d2y - (p2y - p1y) * d2x) / denom
                var ix = p1x + t * d1x
                var iy = p1y + t * d1y
                if (convex) {
                    val mx = ix - xs[curVertex]
                    val my = iy - ys[curVertex]
                    val mLen = sqrt(mx * mx + my * my)
                    val maxLen = MITER_LIMIT * offsetKm
                    if (mLen > maxLen) {
                        ix = xs[curVertex] + mx / mLen * maxLen
                        iy = ys[curVertex] + my / mLen * maxLen
                    }
                }
                ox.add(ix)
                oy.add(iy)
            }
        }

        // Degeneracy check: a large offset can make opposite sides of a concavity
        // or thin neck cross, inverting the shape. Reject self-intersecting
        // results instead of guessing from the input alone (the old centroid
        // "inradius" heuristic wrongly rejected small and concave polygons that
        // buffer perfectly fine).
        val m = ox.size
        for (a in 0 until m) {
            for (b in a + 2 until m) {
                if (a == 0 && b == m - 1) continue // first and last edges share a vertex
                if (segmentsIntersect(
                        ox[a], oy[a], ox[(a + 1) % m], oy[(a + 1) % m],
                        ox[b], oy[b], ox[(b + 1) % m], oy[(b + 1) % m],
                    )
                ) {
                    return null
                }
            }
        }

        return List(m) { GeoPoint(toLat(oy[it]), toLon(ox[it])) }
    }

    /** True when segments (ax1,ay1)-(ax2,ay2) and (bx1,by1)-(bx2,by2) share any point. */
    private fun segmentsIntersect(
        ax1: Double, ay1: Double, ax2: Double, ay2: Double,
        bx1: Double, by1: Double, bx2: Double, by2: Double,
    ): Boolean {
        fun orientation(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Int {
            val v = (qx - px) * (ry - py) - (qy - py) * (rx - px)
            if (abs(v) < 1e-12) return 0
            return if (v > 0) 1 else -1
        }

        fun onSegment(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) =
            rx in minOf(px, qx)..maxOf(px, qx) && ry in minOf(py, qy)..maxOf(py, qy)

        val o1 = orientation(ax1, ay1, ax2, ay2, bx1, by1)
        val o2 = orientation(ax1, ay1, ax2, ay2, bx2, by2)
        val o3 = orientation(bx1, by1, bx2, by2, ax1, ay1)
        val o4 = orientation(bx1, by1, bx2, by2, ax2, ay2)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(ax1, ay1, ax2, ay2, bx1, by1)) return true
        if (o2 == 0 && onSegment(ax1, ay1, ax2, ay2, bx2, by2)) return true
        if (o3 == 0 && onSegment(bx1, by1, bx2, by2, ax1, ay1)) return true
        if (o4 == 0 && onSegment(bx1, by1, bx2, by2, ax2, ay2)) return true
        return false
    }
}