package ca.airspacemonitor

import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.data.AppSettings
import ca.airspacemonitor.data.DistanceUnit
import ca.airspacemonitor.domain.Aircraft
import ca.airspacemonitor.domain.GeoMath
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.MatchedAircraft
import ca.airspacemonitor.domain.Tier
import ca.airspacemonitor.service.AlertUtterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertUtteranceTest {

    private fun matched(
        callsign: String? = "C172",
        registration: String? = null,
        hex: String = "abcdef",
        type: String? = null,
        tier: Tier = Tier.WARNING,
        altitudeFt: Double? = 1200.0,
        verticalRateFpm: Double? = null,
        speedKt: Double? = 90.0,
        bearingDeg: Double = 22.0,
        distanceKm: Double = 2.0,
        lat: Double? = null,
        lon: Double? = null,
    ) = MatchedAircraft(
        aircraft = Aircraft(
            hex = hex,
            callsign = callsign,
            registration = registration,
            type = type,
            lat = lat,
            lon = lon,
            altBaroFt = altitudeFt,
            verticalRateFpm = verticalRateFpm,
            groundSpeedKt = speedKt,
        ),
        distanceKm = distanceKm,
        bearingDeg = bearingDeg,
        bearingCompass = GeoMath.compass8(bearingDeg),
        altMslFt = altitudeFt,
        aglFt = null,
        tier = tier,
    )

    private val imperial = AppSettings(altitudeUnit = AltitudeUnit.FT, distanceUnit = DistanceUnit.KM)
    private val metric = AppSettings(altitudeUnit = AltitudeUnit.M, distanceUnit = DistanceUnit.NM)

    @Test
    fun `warning tier announces the layer and full detail`() {
        val text = AlertUtterance.build(
            matched(verticalRateFpm = 300.0, lat = 44.98653, lon = -73.0),
            imperial,
            GeoPoint(45.0, -73.0),
        )
        assertEquals(
            "Warning. C 1 7 2, 1.5 kilometers south from you, at 167 kilometers per hour, " +
                "altitude 1200 feet, climbing at 300 feet per minute, bearing north north east.",
            text,
        )
    }

    @Test
    fun `distance and direction from the phone are spoken`() {
        val text = AlertUtterance.build(
            matched(lat = 44.98653, lon = -73.0),
            imperial,
            GeoPoint(45.0, -73.0),
        )
        assertTrue("south from you in $text", text.contains("1.5 kilometers south from you"))
    }

    @Test
    fun `centroid reference speaks from the zone center`() {
        val centroid = AppSettings(
            altitudeUnit = AltitudeUnit.FT,
            distanceUnit = DistanceUnit.KM,
            voiceRefMode = ca.airspacemonitor.data.VoiceRefMode.CENTROID,
        )
        val text = AlertUtterance.build(matched(lat = 44.98653, lon = -73.0), centroid, GeoPoint(45.0, -73.0))
        assertTrue(text.contains("1.5 kilometers south from the zone center"))
    }

    @Test
    fun `from you clause is skipped without a position`() {
        val text = AlertUtterance.build(matched(lat = 44.98653, lon = -73.0), imperial, GeoPoint(45.0, -73.0))
        assertTrue(text.contains("from you"))
        val noFix = AlertUtterance.build(matched(lat = 44.98653, lon = -73.0), imperial)
        assertFalse(noFix.contains("from you"))
        val noAircraftPos = AlertUtterance.build(matched(), imperial, GeoPoint(45.0, -73.0))
        assertFalse(noAircraftPos.contains("from you"))
    }

    @Test
    fun `watch tier announces the watch layer`() {
        val text = AlertUtterance.build(matched(tier = Tier.WATCH), imperial)
        assertTrue(text.startsWith("Watch layer."))
    }

    @Test
    fun `ident falls back to registration then hex`() {
        val withReg = AlertUtterance.build(matched(callsign = null, registration = "C-FXYZ"), imperial)
        assertTrue(withReg.contains("C - F X Y Z"))
        val withHex = AlertUtterance.build(matched(callsign = null, registration = null), imperial)
        assertTrue(withHex.contains("A B C D E F"))
    }

    @Test
    fun `level clause replaces tiny climb and descent`() {
        val level = AlertUtterance.build(matched(verticalRateFpm = 50.0), imperial)
        assertTrue(level.contains(", level"))
        assertFalse(level.contains("climbing"))
        assertFalse(level.contains("descending"))
    }

    @Test
    fun `negative rate is spoken as descending`() {
        val text = AlertUtterance.build(matched(verticalRateFpm = -400.0), imperial)
        assertTrue(text.contains(", descending at 400 feet per minute"))
    }

    @Test
    fun `metric units announce meters per second`() {
        val text = AlertUtterance.build(matched(verticalRateFpm = 0.0, altitudeFt = 1000.0), metric)
        // 1000 ft = 305 m; 0 fpm = level.
        assertTrue(text.contains("altitude 305 meters"))
        assertTrue(text.contains(", level"))
    }

    @Test
    fun `nautical settings speak nautical miles and knots`() {
        val text = AlertUtterance.build(
            matched(lat = 44.967, lon = -73.0, speedKt = 90.0),
            metric,
            GeoPoint(45.0, -73.0),
        )
        assertTrue("nautical miles from you in $text", text.contains("2.0 nautical miles south from you"))
        assertTrue(text.contains("at 90 knots"))
    }

    @Test
    fun `null clauses are omitted`() {
        val text = AlertUtterance.build(matched(altitudeFt = null, verticalRateFpm = null, speedKt = null), metric)
        assertEquals("Warning. C 1 7 2, bearing north north east.", text)
    }

    @Test
    fun `metric vertical rate converts fpm to meters per second`() {
        // 591 fpm ~ 3.0 m/s.
        val text = AlertUtterance.build(matched(verticalRateFpm = 591.0), metric)
        assertTrue("3.0 meters per second in $text", text.contains("3.0 meters per second"))
    }

    // ---- Spoken clearances -------------------------------------------------

    private fun departure(ident: String?, hex: String = "abc123", tier: Tier = Tier.WARNING, type: String? = null) =
        ca.airspacemonitor.domain.AircraftTracker.Departure(hex = hex, lastTier = tier, ident = ident, type = type)

    @Test
    fun `known type designator replaces the callsign`() {
        val text = AlertUtterance.build(matched(type = "B738"), imperial)
        assertTrue("Boeing 737 in $text", text.startsWith("Warning. Boeing 737"))
        assertFalse(text.contains("B 7 3 8"))
    }

    @Test
    fun `unknown type designator falls back to the callsign`() {
        val text = AlertUtterance.build(matched(type = "ZZ99"), imperial)
        assertTrue(text.contains("C 1 7 2"))
    }

    @Test
    fun `type names map and are case-insensitive`() {
        assertEquals("Cessna 172", ca.airspacemonitor.service.AircraftTypeNames.spoken("c172"))
        assertEquals("Robinson R44", ca.airspacemonitor.service.AircraftTypeNames.spoken("R44"))
        assertEquals("Boeing 737", ca.airspacemonitor.service.AircraftTypeNames.spoken("B738"))
        assertNull(ca.airspacemonitor.service.AircraftTypeNames.spoken("ZZ99"))
        assertNull(ca.airspacemonitor.service.AircraftTypeNames.spoken(null))
        assertNull(ca.airspacemonitor.service.AircraftTypeNames.spoken("  "))
    }

    @Test
    fun `single clearance speaks the ident and zone`() {
        val text = AlertUtterance.buildCleared(Tier.WARNING, listOf(departure("C172")))
        assertEquals("Clear. C 1 7 2 has left the warning zone.", text)
    }

    @Test
    fun `clearance without ident falls back to hex`() {
        val text = AlertUtterance.buildCleared(Tier.WATCH, listOf(departure(null)))
        assertEquals("Clear. A B C 1 2 3 has left the watch layer.", text)
    }

    @Test
    fun `single clearance speaks the type name when known`() {
        val text = AlertUtterance.buildCleared(Tier.WARNING, listOf(departure("C172", type = "B738")))
        assertEquals("Clear. Boeing 737 has left the warning zone.", text)
    }

    @Test
    fun `multiple clearances are grouped into one sentence`() {
        val text = AlertUtterance.buildCleared(Tier.WARNING, listOf(departure("C172"), departure("ABC123", hex = "zzzz")))
        assertEquals("Clear. 2 aircraft have left the warning zone.", text)
    }
}