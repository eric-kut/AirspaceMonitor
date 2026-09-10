package ca.trafficwatcher

import ca.trafficwatcher.domain.CeilingRef
import ca.trafficwatcher.domain.CeilingUnit
import ca.trafficwatcher.domain.Pipeline
import ca.trafficwatcher.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AltitudeFilterTest {

    private fun outcome(
        baro: Double?,
        geom: Double?,
        ceilingValue: Double,
        ceilingUnit: CeilingUnit,
        ceilingRef: CeilingRef,
        terrainElevM: Double? = null,
    ) = Pipeline.altitudeOutcome(baro, geom, ceilingValue, ceilingUnit, ceilingRef, terrainElevM)

    @Test
    fun `alt_baro ground with no geom falls back to NoAltitude and is skipped`() {
        // "ground" arrives as null after deserialization, like a truly absent field.
        assertEquals(Pipeline.AltitudeOutcome.NoAltitude, outcome(null, null, 1000.0, CeilingUnit.FT, CeilingRef.ASL))
    }

    @Test
    fun `null baro and null geom skip`() {
        assertEquals(Pipeline.AltitudeOutcome.NoAltitude, outcome(null, null, 1000.0, CeilingUnit.FT, CeilingRef.ASL))
    }

    @Test
    fun `null baro but numeric geom is used`() {
        val r = outcome(null, 900.0, 1000.0, CeilingUnit.FT, CeilingRef.ASL)
        assertTrue(r is Pipeline.AltitudeOutcome.Pass)
        assertEquals(900.0, (r as Pipeline.AltitudeOutcome.Pass).mslFt, 1e-9)
    }

    @Test
    fun `geom takes precedence over baro`() {
        val r = outcome(10000.0, 100.0, 1000.0, CeilingUnit.FT, CeilingRef.ASL)
        assertTrue(r is Pipeline.AltitudeOutcome.Pass)
        assertEquals(100.0, (r as Pipeline.AltitudeOutcome.Pass).mslFt, 1e-9)
    }

    @Test
    fun `baro used when geom absent`() {
        val r = outcome(500.0, null, 1000.0, CeilingUnit.FT, CeilingRef.ASL)
        assertTrue(r is Pipeline.AltitudeOutcome.Pass)
        assertEquals(500.0, (r as Pipeline.AltitudeOutcome.Pass).mslFt, 1e-9)
    }

    @Test
    fun `aircraft above ceiling is rejected`() {
        assertEquals(Pipeline.AltitudeOutcome.AboveCeiling, outcome(1500.0, null, 1000.0, CeilingUnit.FT, CeilingRef.ASL))
    }

    @Test
    fun `aircraft exactly at ceiling passes (boundary inclusive)`() {
        assertTrue(outcome(1000.0, null, 1000.0, CeilingUnit.FT, CeilingRef.ASL) is Pipeline.AltitudeOutcome.Pass)
    }

    @Test
    fun `ceiling in meters converts to feet`() {
        val ceilingFt = Units.metersToFeet(100.0) // ~328.084 ft
        val at = outcome(328.0, null, 100.0, CeilingUnit.M, CeilingRef.ASL)
        val above = outcome(330.0, null, 100.0, CeilingUnit.M, CeilingRef.ASL)
        assertTrue(at is Pipeline.AltitudeOutcome.Pass)
        assertTrue(above is Pipeline.AltitudeOutcome.AboveCeiling)
        assertEquals(ceilingFt, 328.084, 0.01)
    }

    @Test
    fun `AGL subtracts terrain elevation`() {
        // terrain 100 m = 328.084 ft; ceiling 400 ft AGL; alt MSL 700 ft -> AGL 371.916 ft
        val pass = outcome(700.0, null, 400.0, CeilingUnit.FT, CeilingRef.AGL, terrainElevM = 100.0)
        assertTrue(pass is Pipeline.AltitudeOutcome.Pass)
        assertEquals(371.916, (pass as Pipeline.AltitudeOutcome.Pass).aglFt!!, 0.01)

        val above = outcome(800.0, null, 400.0, CeilingUnit.FT, CeilingRef.AGL, terrainElevM = 100.0)
        assertTrue(above is Pipeline.AltitudeOutcome.AboveCeiling)
    }

    @Test
    fun `AGL with meters ceiling converts consistently`() {
        // terrain 200 m; ceiling 100 m AGL; aircraft at 280 m MSL -> AGL 80 m (passes)
        val mslFt = Units.metersToFeet(280.0)
        val r = outcome(mslFt, null, 100.0, CeilingUnit.M, CeilingRef.AGL, terrainElevM = 200.0)
        assertTrue(r is Pipeline.AltitudeOutcome.Pass)
        val aglM = Units.feetToMeters((r as Pipeline.AltitudeOutcome.Pass).aglFt!!)
        assertEquals(80.0, aglM, 0.01)

        val above = outcome(Units.metersToFeet(310.0), null, 100.0, CeilingUnit.M, CeilingRef.AGL, terrainElevM = 200.0)
        assertTrue(above is Pipeline.AltitudeOutcome.AboveCeiling)
    }

    @Test
    fun `AGL without terrain is MissingTerrain`() {
        assertEquals(Pipeline.AltitudeOutcome.MissingTerrain, outcome(500.0, null, 400.0, CeilingUnit.FT, CeilingRef.AGL, null))
    }

    @Test
    fun `unit conversions round-trip`() {
        assertEquals(100.0, Units.feetToMeters(Units.metersToFeet(100.0)), 1e-9)
        assertEquals(5.0, Units.kmToNm(Units.nmToKm(5.0)), 1e-9)
        assertEquals(185.2, Units.knotsToKmh(100.0), 1e-9)
    }
}