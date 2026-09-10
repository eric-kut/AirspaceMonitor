package ca.trafficwatcher.domain

/**
 * Client-side filtering pipeline applied to every poll cycle.
 *
 * Order (per spec): drop missing position -> geofence test -> altitude test.
 * The API query itself is already narrowed to a bounding circle; the polygon
 * test is the exact pass/fail criterion.
 */
object Pipeline {

    sealed interface AltitudeOutcome {
        /** Passed the ceiling check. [mslFt] is the MSL altitude used; [aglFt] only when AGL mode. */
        data class Pass(val mslFt: Double, val aglFt: Double?) : AltitudeOutcome

        /** No usable altitude (alt_baro "ground", absent, or missing fallback). */
        data object NoAltitude : AltitudeOutcome

        /** AGL mode but the profile has no terrain elevation (should be blocked by validation). */
        data object MissingTerrain : AltitudeOutcome

        data object AboveCeiling : AltitudeOutcome
    }

    /**
     * altMSL = alt_geom ?: alt_baro (numeric only; "ground"/null fall through to
     * NoAltitude). ASL: altMSL <= ceiling. AGL: (altMSL - terrain) <= ceiling,
     * with terrain converted to feet. Units converted consistently.
     */
    fun altitudeOutcome(
        altBaroFt: Double?,
        altGeomFt: Double?,
        ceilingValue: Double,
        ceilingUnit: CeilingUnit,
        ceilingRef: CeilingRef,
        terrainElevM: Double?,
    ): AltitudeOutcome {
        val altMslFt = altGeomFt ?: altBaroFt ?: return AltitudeOutcome.NoAltitude
        val ceilingFt = if (ceilingUnit == CeilingUnit.FT) ceilingValue else Units.metersToFeet(ceilingValue)
        val eps = 1e-6
        return when (ceilingRef) {
            CeilingRef.ASL ->
                if (altMslFt <= ceilingFt + eps) AltitudeOutcome.Pass(altMslFt, null)
                else AltitudeOutcome.AboveCeiling
            CeilingRef.AGL -> {
                val terrainM = terrainElevM ?: return AltitudeOutcome.MissingTerrain
                val aglFt = altMslFt - Units.metersToFeet(terrainM)
                if (aglFt <= ceilingFt + eps) AltitudeOutcome.Pass(altMslFt, aglFt) else AltitudeOutcome.AboveCeiling
            }
        }
    }

    data class FilterResult(
        val matched: List<MatchedAircraft>,
        val skippedNoPosition: Int,
        val skippedNoAltitude: Int,
        val skippedMissingTerrain: Int,
    )

    fun filter(
        aircraft: List<Aircraft>,
        profile: Profile,
        /** Center of the mandatory warning zone (GPS fix, fixed center or polygon bounding center). */
        reference: GeoPoint,
        /** Latest phone fix; only used by a FOLLOW_PHONE watch layer. */
        phoneReference: GeoPoint? = null,
    ): FilterResult {
        val matched = ArrayList<MatchedAircraft>()
        var noPos = 0
        var noAlt = 0
        var noTerrain = 0

        for (ac in aircraft) {
            val lat = ac.lat
            val lon = ac.lon
            if (lat == null || lon == null) {
                noPos++
                continue
            }
            val position = GeoPoint(lat, lon)

            // 1. Mandatory warning zone: shape + warning ceiling -> urgent tier.
            val inWarning = insideWarningZone(profile, reference, position) &&
                altitudeOutcome(
                    ac.altBaroFt, ac.altGeomFt,
                    profile.ceilingValue, profile.ceilingUnit, profile.ceilingRef,
                    profile.terrainElevM,
                ) is AltitudeOutcome.Pass

            // 2. Optional watch layer: own shape + own ceiling -> advisory tier.
            val inWatch = !inWarning &&
                watchPass(profile, reference, phoneReference, position, ac.altBaroFt, ac.altGeomFt)

            if (!inWarning && !inWatch) continue
            val tier = if (inWarning) Tier.WARNING else Tier.WATCH

            // Reported altitudes: MSL always; AGL only when the tier's ceiling uses AGL.
            val ceilingRef = if (inWarning) profile.ceilingRef
            else profile.watch?.let { if (it.mode == WatchMode.OFFSET) profile.ceilingRef else it.ceilingRef }
                ?: profile.ceilingRef
            val altMslFt = ac.altGeomFt ?: ac.altBaroFt
            val aglFt = if (ceilingRef == CeilingRef.AGL) {
                altMslFt?.let { it - Units.metersToFeet(profile.terrainElevM ?: 0.0) }
            } else {
                null
            }

            val distanceKm = GeoMath.haversineKm(reference.lat, reference.lon, lat, lon)
            val bearing = GeoMath.bearingDeg(reference, position)
            matched.add(
                MatchedAircraft(
                    aircraft = ac,
                    distanceKm = distanceKm,
                    bearingDeg = bearing,
                    bearingCompass = GeoMath.compass8(bearing),
                    altMslFt = altMslFt,
                    aglFt = aglFt,
                    tier = tier,
                ),
            )
        }
        matched.sortBy { it.distanceKm }
        return FilterResult(matched, noPos, noAlt, noTerrain)
    }

    /** Shape test for the mandatory warning zone. */
    private fun insideWarningZone(profile: Profile, reference: GeoPoint, position: GeoPoint): Boolean =
        when (profile.geofenceMode) {
            GeofenceMode.FIXED_CIRCLE, GeofenceMode.FOLLOW_PHONE ->
                GeoMath.haversineKm(reference.lat, reference.lon, position.lat, position.lon) <=
                    (profile.radiusKm ?: 0.0) + 1e-9
            GeofenceMode.POLYGON -> {
                val poly = profile.polygon
                poly != null && poly.size >= 3 && GeoMath.pointInPolygon(position, poly)
            }
        }

    /**
     * True when the aircraft sits inside the optional watch layer (shape test)
     * and below its ceiling. OFFSET layers wrap the warning zone: a circle
     * around the same reference widened by [WatchVolume.offsetHkm], with the
     * warning ceiling raised by [WatchVolume.offsetV].
     */
    private fun watchPass(
        profile: Profile,
        reference: GeoPoint,
        phoneReference: GeoPoint?,
        position: GeoPoint,
        altBaroFt: Double?,
        altGeomFt: Double?,
    ): Boolean {
        val watch = profile.watch ?: return false
        val inside = when (watch.mode) {
            WatchMode.OFFSET -> {
                val radius = effectiveWarningRadiusKm(profile) + watch.offsetHkm
                GeoMath.haversineKm(reference.lat, reference.lon, position.lat, position.lon) <= radius + 1e-9
            }
            WatchMode.FOLLOW_PHONE -> {
                val ref = phoneReference ?: return false
                GeoMath.haversineKm(ref.lat, ref.lon, position.lat, position.lon) <= watch.radiusKm + 1e-9
            }
            WatchMode.FIXED_CIRCLE -> {
                val lat = watch.centerLat ?: return false
                val lon = watch.centerLon ?: return false
                GeoMath.haversineKm(lat, lon, position.lat, position.lon) <= watch.radiusKm + 1e-9
            }
            WatchMode.POLYGON -> {
                val poly = watch.polygon
                poly != null && poly.size >= 3 && GeoMath.pointInPolygon(position, poly)
            }
        }
        if (!inside) return false
        return when (watch.mode) {
            WatchMode.OFFSET -> {
                val warningCeilingFt = if (profile.ceilingUnit == CeilingUnit.FT) profile.ceilingValue
                else Units.metersToFeet(profile.ceilingValue)
                val offsetFt = if (watch.offsetVUnit == CeilingUnit.FT) watch.offsetV
                else Units.metersToFeet(watch.offsetV)
                altitudeOutcome(
                    altBaroFt, altGeomFt,
                    warningCeilingFt + offsetFt, CeilingUnit.FT,
                    profile.ceilingRef, profile.terrainElevM,
                ) is AltitudeOutcome.Pass
            }
            else -> altitudeOutcome(
                altBaroFt, altGeomFt,
                watch.ceilingValue, watch.ceilingUnit, watch.ceilingRef,
                profile.terrainElevM,
            ) is AltitudeOutcome.Pass
        }
    }

    /** Horizontal extent of the warning zone: circle radius or polygon bounding-circle radius. */
    private fun effectiveWarningRadiusKm(profile: Profile): Double =
        when (profile.geofenceMode) {
            GeofenceMode.FIXED_CIRCLE, GeofenceMode.FOLLOW_PHONE -> profile.radiusKm ?: 0.0
            GeofenceMode.POLYGON ->
                profile.polygon?.takeIf { it.size >= 3 }?.let { GeoMath.polygonBoundingCircle(it).second } ?: 0.0
        }

    /**
     * Radius (km) the ADS-B query circle needs so both the warning zone AND the
     * optional watch layer fall inside the fetched area. If the query only
     * covered the warning zone, aircraft in the outer watch ring would never be
     * seen and could never alert.
     */
    fun queryRadiusKm(profile: Profile, reference: GeoPoint?, phoneFix: GeoPoint? = null): Double {
        val warningExtentKm = effectiveWarningRadiusKm(profile).let {
            if (profile.geofenceMode == GeofenceMode.POLYGON) it * 1.10 else it
        }
        var radiusKm = warningExtentKm
        profile.watch?.let { w ->
            when (w.mode) {
                WatchMode.OFFSET -> radiusKm += w.offsetHkm
                WatchMode.FOLLOW_PHONE, WatchMode.FIXED_CIRCLE, WatchMode.POLYGON -> {
                    val watchCenter: GeoPoint? = when (w.mode) {
                        WatchMode.FOLLOW_PHONE -> phoneFix
                        WatchMode.FIXED_CIRCLE ->
                            w.centerLat?.let { lat -> w.centerLon?.let { lon -> GeoPoint(lat, lon) } }
                        WatchMode.POLYGON ->
                            w.polygon?.takeIf { it.size >= 3 }?.let { GeoMath.polygonBoundingCircle(it).first }
                        else -> null
                    }
                    val watchExtentKm = if (w.mode == WatchMode.POLYGON) {
                        w.polygon?.takeIf { it.size >= 3 }?.let { GeoMath.polygonBoundingCircle(it).second } ?: 0.0
                    } else {
                        w.radiusKm
                    }
                    if (watchCenter != null && reference != null) {
                        radiusKm = maxOf(
                            radiusKm,
                            GeoMath.haversineKm(reference.lat, reference.lon, watchCenter.lat, watchCenter.lon) +
                                watchExtentKm,
                        )
                    }
                }
            }
        }
        return radiusKm
    }
}