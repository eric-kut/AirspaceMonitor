package ca.airspacemonitor

import ca.airspacemonitor.domain.GeoMath
import ca.airspacemonitor.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    private val eps = 1e-9

    // ---- Haversine ---------------------------------------------------------

    @Test
    fun `haversine zero distance for same point`() {
        assertEquals(0.0, GeoMath.haversineKm(45.5, -75.7, 45.5, -75.7), eps)
    }

    @Test
    fun `haversine one degree of latitude is about 111_195 km`() {
        val d = GeoMath.haversineKm(45.0, -75.0, 46.0, -75.0)
        assertEquals(111.195, d, 0.01)
    }

    @Test
    fun `haversine one degree of longitude at the equator is about 111_195 km`() {
        val d = GeoMath.haversineKm(0.0, 0.0, 0.0, 1.0)
        assertEquals(111.195, d, 0.01)
    }

    @Test
    fun `haversine one degree of longitude at 45N is about 78_6 km`() {
        // Great-circle distance, slightly under the parallel-arc 78.71 km.
        val d = GeoMath.haversineKm(45.0, 0.0, 45.0, 1.0)
        assertEquals(78.626, d, 0.01)
    }

    @Test
    fun `haversine is symmetric`() {
        val a = GeoMath.haversineKm(45.4215, -75.6972, 45.5017, -73.5673)
        val b = GeoMath.haversineKm(45.5017, -73.5673, 45.4215, -75.6972)
        assertEquals(a, b, eps)
    }

    // ---- Bearing -----------------------------------------------------------

    @Test
    fun `bearing due north is 0`() {
        assertEquals(0.0, GeoMath.bearingDeg(GeoPoint(45.0, -75.0), GeoPoint(46.0, -75.0)), 1e-6)
    }

    @Test
    fun `bearing due east is 90`() {
        assertEquals(90.0, GeoMath.bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0)), 1e-6)
    }

    @Test
    fun `bearing due south is 180`() {
        assertEquals(180.0, GeoMath.bearingDeg(GeoPoint(46.0, -75.0), GeoPoint(45.0, -75.0)), 1e-6)
    }

    @Test
    fun `bearing due west is 270`() {
        assertEquals(270.0, GeoMath.bearingDeg(GeoPoint(0.0, 10.0), GeoPoint(0.0, 0.0)), 1e-6)
    }

    @Test
    fun `bearing normalized for antipodal-ish westward`() {
        val b = GeoMath.bearingDeg(GeoPoint(0.0, -170.0), GeoPoint(0.0, 170.0))
        assertEquals(270.0, b, 1e-6)
    }

    // ---- 8-point compass ---------------------------------------------------

    @Test
    fun `compass8 cardinal and intercardinal points`() {
        assertEquals("N", GeoMath.compass8(0.0))
        assertEquals("NE", GeoMath.compass8(45.0))
        assertEquals("E", GeoMath.compass8(90.0))
        assertEquals("SE", GeoMath.compass8(135.0))
        assertEquals("S", GeoMath.compass8(180.0))
        assertEquals("SW", GeoMath.compass8(225.0))
        assertEquals("W", GeoMath.compass8(270.0))
        assertEquals("NW", GeoMath.compass8(315.0))
    }

    @Test
    fun `compass8 wraps negative bearings`() {
        // 337.5° is exactly on the NW/N boundary; upper boundaries belong to the next sector.
        assertEquals("N", GeoMath.compass8(-22.5))
        assertEquals("NW", GeoMath.compass8(-22.51))
        assertEquals("N", GeoMath.compass8(-22.49))
        assertEquals("E", GeoMath.compass8(360.0 + 90.0))
    }

    // ---- Point in polygon --------------------------------------------------

    private val square = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0, 10.0),
        GeoPoint(10.0, 10.0),
        GeoPoint(10.0, 0.0),
    )

    @Test
    fun `PIP convex square interior point`() {
        assertTrue(GeoMath.pointInPolygon(GeoPoint(5.0, 5.0), square))
    }

    @Test
    fun `PIP convex square outside points`() {
        assertFalse(GeoMath.pointInPolygon(GeoPoint(15.0, 5.0), square))
        assertFalse(GeoMath.pointInPolygon(GeoPoint(-1.0, -1.0), square))
        assertFalse(GeoMath.pointInPolygon(GeoPoint(10.5, 10.0), square))
    }

    @Test
    fun `PIP point on edge counts as inside`() {
        assertTrue(GeoMath.pointInPolygon(GeoPoint(5.0, 0.0), square)) // bottom edge
        assertTrue(GeoMath.pointInPolygon(GeoPoint(10.0, 7.0), square)) // right edge
        assertTrue(GeoMath.pointInPolygon(GeoPoint(0.0, 0.0), square))  // vertex
    }

    private val concaveU = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0, 10.0),
        GeoPoint(4.0, 10.0),
        GeoPoint(4.0, 4.0),
        GeoPoint(6.0, 4.0),
        GeoPoint(6.0, 10.0),
        GeoPoint(10.0, 10.0),
        GeoPoint(10.0, 0.0),
    )

    @Test
    fun `PIP concave polygon bottom bar is inside`() {
        assertTrue(GeoMath.pointInPolygon(GeoPoint(5.0, 2.0), concaveU))
    }

    @Test
    fun `PIP concave polygon upper notch is outside`() {
        assertFalse(GeoMath.pointInPolygon(GeoPoint(5.0, 8.0), concaveU))
    }

    @Test
    fun `PIP concave polygon left column is inside`() {
        assertTrue(GeoMath.pointInPolygon(GeoPoint(2.0, 8.0), concaveU))
    }

    @Test
    fun `PIP concave polygon right column is inside`() {
        assertTrue(GeoMath.pointInPolygon(GeoPoint(8.0, 8.0), concaveU))
    }

    // ---- Destination point -------------------------------------------------

    @Test
    fun `destination point due north moves latitude by one degree`() {
        val dest = GeoMath.destinationPoint(GeoPoint(45.0, -75.0), 0.0, 111.195)
        assertEquals(46.0, dest.lat, 0.01)
        assertEquals(-75.0, dest.lon, 0.01)
    }

    @Test
    fun `destination point due east at the equator`() {
        val dest = GeoMath.destinationPoint(GeoPoint(0.0, 0.0), 90.0, 111.195)
        assertEquals(0.0, dest.lat, 0.01)
        assertEquals(1.0, dest.lon, 0.01)
    }

    @Test
    fun `destination point is consistent with haversine distance`() {
        val start = GeoPoint(45.4215, -75.6972)
        val dest = GeoMath.destinationPoint(start, 137.0, 12.3)
        assertEquals(12.3, GeoMath.haversineKm(start, dest), 0.01)
    }

    // ---- Bounding circle ---------------------------------------------------

    @Test
    fun `bounding circle centroid and max vertex distance`() {
        val polygon = listOf(
            GeoPoint(45.0, -75.0),
            GeoPoint(45.0, -74.9),
            GeoPoint(44.9, -74.9),
            GeoPoint(44.9, -75.0),
        )
        val (centroid, radius) = GeoMath.polygonBoundingCircle(polygon)
        assertEquals(44.95, centroid.lat, 1e-9)
        assertEquals(-74.95, centroid.lon, 1e-9)
        // Half-diagonal of the ~11.1 km x ~7.9 km rectangle, km
        assertEquals(6.75, radius, 0.2)
        // Every vertex within the bounding circle
        polygon.forEach { assertTrue(GeoMath.haversineKm(centroid, it) <= radius + 1e-9) }
    }

    // ---- Offset polygon (buffer) --------------------------------------------

    private val offsetSquare = listOf(
        GeoPoint(45.40, -75.72),
        GeoPoint(45.44, -75.72),
        GeoPoint(45.44, -75.67),
        GeoPoint(45.40, -75.67),
    )

    @Test
    fun `offset polygon grows each edge by the offset`() {
        val centroid = GeoPoint(45.42, -75.695)
        val buffered = GeoMath.offsetPolygon(offsetSquare, 1.0)!!
        // 0.5 km outside the original east edge -> inside the buffered polygon,
        // and outside the original (this is the watch band).
        val nearEast = GeoMath.destinationPoint(centroid, 90.0, 2.45)
        assertTrue(GeoMath.pointInPolygon(nearEast, buffered))
        assertTrue(!GeoMath.pointInPolygon(nearEast, offsetSquare))
        // 3 km east of the centroid: 1 km past the buffered east edge -> outside.
        val farEast = GeoMath.destinationPoint(centroid, 90.0, 3.0)
        assertTrue(!GeoMath.pointInPolygon(farEast, buffered))
        // Buffered result contains the whole original polygon.
        offsetSquare.forEach { assertTrue(GeoMath.pointInPolygon(it, buffered)) }
    }

    @Test
    fun `offset polygon corner sits at the miter distance from the original corner`() {
        val buffered = GeoMath.offsetPolygon(offsetSquare, 1.0)!!
        // 90-degree corner: miter point is offset * sqrt(2) from the original corner.
        val nearest = buffered.minOf { GeoMath.haversineKm(GeoPoint(45.44, -75.67), it) }
        assertEquals(1.4142, nearest, 0.05)
    }

    @Test
    fun `offset polygon handles clockwise winding`() {
        val cw = offsetSquare.reversed()
        val a = GeoMath.offsetPolygon(offsetSquare, 1.0)!!.sortedWith(compareBy({ it.lat }, { it.lon }))
        val b = GeoMath.offsetPolygon(cw, 1.0)!!.sortedWith(compareBy({ it.lat }, { it.lon }))
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (pa, pb) ->
            assertEquals(pa.lat, pb.lat, 1e-6)
            assertEquals(pa.lon, pb.lon, 1e-6)
        }
    }

    @Test
    fun `offset polygon zero offset returns the input`() {
        assertEquals(offsetSquare, GeoMath.offsetPolygon(offsetSquare, 0.0))
    }

    @Test
    fun `offset polygon returns null when offset exceeds the inradius`() {
        // Square half-width ~1.95 km; 2 km offset collapses it.
        assertEquals(null, GeoMath.offsetPolygon(offsetSquare, 2.0))
        assertEquals(null, GeoMath.offsetPolygon(offsetSquare, 5.0))
    }
}