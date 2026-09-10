package ca.airspacemonitor

import ca.airspacemonitor.domain.Aircraft
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.CeilingUnit
import ca.airspacemonitor.domain.GeoMath
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.GeofenceMode
import ca.airspacemonitor.domain.Pipeline
import ca.airspacemonitor.domain.Profile
import ca.airspacemonitor.domain.Tier
import ca.airspacemonitor.domain.WatchMode
import ca.airspacemonitor.domain.WatchVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TierPipelineTest {

    // Ottawa reference; AGL ceilings with terrain 0 m so AGL == MSL for tests.
    // Base geofence = mandatory WARNING zone (5 km / 1500 ft AGL by default).
    private val center = GeoPoint(45.4215, -75.6972)

    private fun profile(
        watch: WatchVolume? = null,
        radiusKm: Double = 5.0,
        ceilingFt: Double = 1500.0,
    ) = Profile(
        name = "p",
        geofenceMode = GeofenceMode.FIXED_CIRCLE,
        centerLat = center.lat,
        centerLon = center.lon,
        radiusKm = radiusKm,
        ceilingValue = ceilingFt,
        ceilingUnit = CeilingUnit.FT,
        ceilingRef = CeilingRef.AGL,
        terrainElevM = 0.0,
        watch = watch,
    )

    private fun offsetWatch(hKm: Double = 5.0, vFt: Double = 300.0) = WatchVolume(
        mode = WatchMode.OFFSET,
        offsetHkm = hKm,
        offsetV = vFt,
        offsetVUnit = CeilingUnit.FT,
    )

    private fun aircraftAt(kmFromCenter: Double, altFt: Double): Aircraft {
        val p = GeoMath.destinationPoint(center, 45.0, kmFromCenter)
        return Aircraft(
            hex = "a1",
            lat = p.lat,
            lon = p.lon,
            altBaroFt = altFt,
        )
    }

    @Test
    fun `aircraft inside warning zone and below ceiling is WARNING`() {
        val result = Pipeline.filter(listOf(aircraftAt(2.0, 1000.0)), profile(offsetWatch()), center)
        assertEquals(1, result.matched.size)
        assertEquals(Tier.WARNING, result.matched.first().tier)
    }

    @Test
    fun `aircraft between warning and offset extent is WATCH`() {
        // Offset radius = 5 km warning + 5 km offset = 10 km.
        val result = Pipeline.filter(listOf(aircraftAt(7.0, 1000.0)), profile(offsetWatch()), center)
        assertEquals(1, result.matched.size)
        assertEquals(Tier.WATCH, result.matched.first().tier)
    }

    @Test
    fun `aircraft beyond the offset extent is not matched`() {
        val result = Pipeline.filter(listOf(aircraftAt(12.0, 1000.0)), profile(offsetWatch()), center)
        assertTrue(result.matched.isEmpty())
    }

    @Test
    fun `aircraft above the effective watch ceiling is not matched`() {
        // Watch ceiling = 1500 + 300 = 1800 ft.
        val result = Pipeline.filter(listOf(aircraftAt(2.0, 2000.0)), profile(offsetWatch()), center)
        assertTrue(result.matched.isEmpty())
    }

    @Test
    fun `aircraft above warning ceiling but below watch ceiling is WATCH`() {
        // 1600 ft: above the 1500 ft warning ceiling, below the 1800 ft watch ceiling.
        val result = Pipeline.filter(listOf(aircraftAt(2.0, 1600.0)), profile(offsetWatch()), center)
        assertEquals(1, result.matched.size)
        assertEquals(Tier.WATCH, result.matched.first().tier)
    }

    @Test
    fun `no watch layer means outside the warning zone is not matched`() {
        val result = Pipeline.filter(listOf(aircraftAt(7.0, 1000.0)), profile(watch = null), center)
        assertTrue(result.matched.isEmpty())
    }

    @Test
    fun `offset vertical margin converts meters to feet`() {
        // 100 m offset = ~328 ft; watch ceiling = 1500 + 328 = 1828 ft.
        val w = WatchVolume(mode = WatchMode.OFFSET, offsetHkm = 5.0, offsetV = 100.0, offsetVUnit = CeilingUnit.M)
        val inside = Pipeline.filter(listOf(aircraftAt(2.0, 1550.0)), profile(w), center)
        assertEquals(Tier.WATCH, inside.matched.first().tier)
        val above = Pipeline.filter(listOf(aircraftAt(2.0, 1900.0)), profile(w), center)
        assertTrue(above.matched.isEmpty())
    }

    @Test
    fun `follow phone watch layer uses the phone fix`() {
        val phone = GeoMath.destinationPoint(center, 90.0, 6.0)
        val p = profile(
            WatchVolume(
                mode = WatchMode.FOLLOW_PHONE,
                radiusKm = 3.0,
                ceilingValue = 1500.0,
                ceilingUnit = CeilingUnit.FT,
                ceilingRef = CeilingRef.AGL,
            ),
        )
        // 1 km from the phone (6.9 km from center, outside the 5 km warning) -> WATCH.
        val near = GeoMath.destinationPoint(phone, 45.0, 1.0)
        // 2 km from the center -> WARNING wins over watch.
        val inWarning = aircraftAt(2.0, 1000.0)
        // 5 km NE of the phone (~10.2 km from center): outside both layers.
        val far = GeoMath.destinationPoint(phone, 45.0, 5.0)
        val result = Pipeline.filter(
            listOf(
                Aircraft(hex = "near", lat = near.lat, lon = near.lon, altBaroFt = 1000.0),
                inWarning,
                Aircraft(hex = "far", lat = far.lat, lon = far.lon, altBaroFt = 1000.0),
            ),
            p,
            center,
            phoneReference = phone,
        )
        val byHex = result.matched.associateBy { it.aircraft.hex }
        assertEquals(Tier.WATCH, byHex["near"]?.tier)
        assertEquals(Tier.WARNING, byHex["a1"]?.tier)
        assertTrue(byHex["far"]?.let { true } != true)
    }

    @Test
    fun `follow phone watch without a fix matches nothing outside the warning zone`() {
        val p = profile(
            WatchVolume(
                mode = WatchMode.FOLLOW_PHONE,
                radiusKm = 3.0,
                ceilingValue = 1500.0,
                ceilingUnit = CeilingUnit.FT,
                ceilingRef = CeilingRef.AGL,
            ),
        )
        val result = Pipeline.filter(listOf(aircraftAt(7.0, 1000.0)), p, center, phoneReference = null)
        assertTrue(result.matched.isEmpty())
    }

    @Test
    fun `polygon watch layer matches only inside the polygon`() {
        // Warning zone 3 km; watch polygon covering an arc 4-8 km east.
        val ringPoint = { km: Double, bearing: Double -> GeoMath.destinationPoint(center, bearing, km) }
        val poly = listOf(
            ringPoint(4.0, 0.0).let { GeoMath.destinationPoint(it, 90.0, 2.0) },
            ringPoint(8.0, 0.0).let { GeoMath.destinationPoint(it, 90.0, 2.0) },
            ringPoint(8.0, 0.0).let { GeoMath.destinationPoint(it, 270.0, 2.0) },
            ringPoint(4.0, 0.0).let { GeoMath.destinationPoint(it, 270.0, 2.0) },
        )
        val p = profile(
            WatchVolume(
                mode = WatchMode.POLYGON,
                polygon = poly,
                ceilingValue = 1500.0,
                ceilingUnit = CeilingUnit.FT,
                ceilingRef = CeilingRef.AGL,
            ),
            radiusKm = 3.0,
        )
        val east = GeoMath.destinationPoint(center, 0.0, 6.0)
        val west = GeoMath.destinationPoint(center, 180.0, 6.0)
        val result = Pipeline.filter(
            listOf(
                Aircraft(hex = "east", lat = east.lat, lon = east.lon, altBaroFt = 1000.0),
                Aircraft(hex = "west", lat = west.lat, lon = west.lon, altBaroFt = 1000.0),
            ),
            p,
            center,
        )
        val byHex = result.matched.associateBy { it.aircraft.hex }
        assertEquals(Tier.WATCH, byHex["east"]?.tier)
        assertTrue(byHex["west"]?.let { true } != true)
    }

    @Test
    fun `warning tier respects AGL reference with terrain`() {
        val p = profile(offsetWatch()).copy(terrainElevM = 300.0)
        // 1200 ft MSL - 984 ft (300 m terrain) = 216 ft AGL -> below 1500 ft -> WARNING.
        val low = Pipeline.filter(listOf(aircraftAt(2.0, 1200.0)), p, center)
        assertEquals(Tier.WARNING, low.matched.first().tier)
        // 2600 ft MSL - 984 ft = 1616 ft AGL -> above the 1500 ft warning ceiling,
        // below the 1800 ft watch ceiling -> WATCH.
        val high = Pipeline.filter(listOf(aircraftAt(2.0, 2600.0)), p, center)
        assertEquals(Tier.WATCH, high.matched.first().tier)
    }

    @Test
    fun `polygon warning zone matches by point in polygon`() {
        val poly = listOf(
            GeoPoint(45.40, -75.72),
            GeoPoint(45.44, -75.72),
            GeoPoint(45.44, -75.67),
            GeoPoint(45.40, -75.67),
        )
        val centroid = GeoPoint(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lon } / poly.size)
        val p = profile(radiusKm = 1.0).copy(geofenceMode = GeofenceMode.POLYGON, polygon = poly, centerLat = null, centerLon = null)
        val inside = GeoMath.destinationPoint(centroid, 90.0, 0.5)
        val outside = GeoMath.destinationPoint(centroid, 90.0, 3.5)
        val result = Pipeline.filter(
            listOf(
                Aircraft(hex = "in", lat = inside.lat, lon = inside.lon, altBaroFt = 1000.0),
                Aircraft(hex = "out", lat = outside.lat, lon = outside.lon, altBaroFt = 1000.0),
            ),
            p,
            centroid,
        )
        val byHex = result.matched.associateBy { it.aircraft.hex }
        assertEquals(Tier.WARNING, byHex["in"]?.tier)
        assertTrue(byHex["out"]?.let { true } != true)
    }

    @Test
    fun `offset watch over a polygon warning matches the buffered band`() {
        // Square warning polygon ~1.95 km half-width east-west.
        val poly = listOf(
            GeoPoint(45.40, -75.72),
            GeoPoint(45.44, -75.72),
            GeoPoint(45.44, -75.67),
            GeoPoint(45.40, -75.67),
        )
        val centroid = GeoPoint(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lon } / poly.size)
        val p = profile(offsetWatch(hKm = 1.0))
            .copy(geofenceMode = GeofenceMode.POLYGON, polygon = poly, centerLat = null, centerLon = null)
        fun at(kmEast: Double) = GeoMath.destinationPoint(centroid, 90.0, kmEast)

        // Inside the polygon -> WARNING (not WATCH).
        val inside = at(0.5)
        val inResult = Pipeline.filter(
            listOf(Aircraft(hex = "in", lat = inside.lat, lon = inside.lon, altBaroFt = 1000.0)),
            p, centroid,
        )
        assertEquals(Tier.WARNING, inResult.matched.first().tier)

        // 2.5 km east: outside the polygon (1.95 km) but inside the buffered watch
        // polygon (2.95 km) -> WATCH.
        val band = at(2.5)
        val bandResult = Pipeline.filter(
            listOf(Aircraft(hex = "band", lat = band.lat, lon = band.lon, altBaroFt = 1000.0)),
            p, centroid,
        )
        assertEquals(Tier.WATCH, bandResult.matched.first().tier)

        // 4.5 km east: past the buffered edge -> not matched.
        val outside = at(4.5)
        val outResult = Pipeline.filter(
            listOf(Aircraft(hex = "out", lat = outside.lat, lon = outside.lon, altBaroFt = 1000.0)),
            p, centroid,
        )
        assertTrue(outResult.matched.isEmpty())
    }

    @Test
    fun `query radius covers the buffered polygon watch layer`() {
        val poly = listOf(
            GeoPoint(45.40, -75.72),
            GeoPoint(45.44, -75.72),
            GeoPoint(45.44, -75.67),
            GeoPoint(45.40, -75.67),
        )
        val centroid = GeoPoint(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lon } / poly.size)
        val p = profile(offsetWatch(hKm = 1.0))
            .copy(geofenceMode = GeofenceMode.POLYGON, polygon = poly, centerLat = null, centerLon = null)
        val buffered = GeoMath.offsetPolygon(poly, 1.0)!!
        val bufferedRadius = GeoMath.polygonBoundingCircle(buffered).second
        val r = Pipeline.queryRadiusKm(p, centroid)
        assertTrue(r >= bufferedRadius)
        assertTrue(r < bufferedRadius + 1.0)
    }

    // ---- Query radius: must cover the watch layer, not just the warning zone ----

    @Test
    fun `query radius covers the offset ring`() {
        val p = profile(offsetWatch(hKm = 5.0))
        // Warning radius 5 km + offset 5 km = 10 km.
        assertEquals(10.0, Pipeline.queryRadiusKm(p, center), 0.01)
        // Without a watch layer it is just the warning radius.
        assertEquals(5.0, Pipeline.queryRadiusKm(p.copy(watch = null), center), 0.01)
    }

    @Test
    fun `query radius covers a watch circle centered away from the warning zone`() {
        val watchCenter = GeoMath.destinationPoint(center, 90.0, 8.0)
        val p = profile(
            WatchVolume(
                mode = WatchMode.FIXED_CIRCLE,
                radiusKm = 3.0,
                centerLat = watchCenter.lat,
                centerLon = watchCenter.lon,
                ceilingValue = 1500.0,
                ceilingUnit = CeilingUnit.FT,
                ceilingRef = CeilingRef.AGL,
            ),
        )
        assertEquals(11.0, Pipeline.queryRadiusKm(p, center), 0.01)
    }

    @Test
    fun `query radius covers a follow phone watch layer`() {
        val phone = GeoMath.destinationPoint(center, 90.0, 6.0)
        val p = profile(
            WatchVolume(
                mode = WatchMode.FOLLOW_PHONE,
                radiusKm = 4.0,
                ceilingValue = 1500.0,
                ceilingUnit = CeilingUnit.FT,
                ceilingRef = CeilingRef.AGL,
            ),
        )
        assertEquals(10.0, Pipeline.queryRadiusKm(p, center, phone), 0.01)
    }
}