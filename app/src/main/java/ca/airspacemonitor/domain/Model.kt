package ca.airspacemonitor.domain

data class GeoPoint(val lat: Double, val lon: Double)

enum class GeofenceMode { FOLLOW_PHONE, FIXED_CIRCLE, POLYGON }

enum class CeilingUnit { FT, M }

enum class CeilingRef { ASL, AGL }

/** Alert urgency level: the mandatory base zone is the WARNING tier; the optional outer watch layer is WATCH. */
enum class Tier { WATCH, WARNING }

data class Profile(
    val id: Long = 0L,
    val name: String,
    /** Shape of the mandatory warning zone. */
    val geofenceMode: GeofenceMode,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val radiusKm: Double? = null,
    val polygon: List<GeoPoint>? = null,
    /** Warning zone ceiling. */
    val ceilingValue: Double,
    val ceilingUnit: CeilingUnit,
    val ceilingRef: CeilingRef,
    val terrainElevM: Double? = null,
    val pollIntervalSec: Int = 12,
    val alertCooldownMin: Int = 2,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /**
     * Optional outer watch layer: a wider/higher buffer around the warning
     * zone that elicits a plain "be watchful" notification instead of the
     * urgent warning alarm.
     */
    val watch: WatchVolume? = null,
)

enum class WatchMode { OFFSET, FOLLOW_PHONE, FIXED_CIRCLE, POLYGON }

data class WatchVolume(
    val mode: WatchMode,
    /** FOLLOW_PHONE / FIXED_CIRCLE only. */
    val radiusKm: Double = 10.0,
    /** FIXED_CIRCLE only. */
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    /** POLYGON only (needs >= 3 vertices). */
    val polygon: List<GeoPoint>? = null,
    /** OFFSET only: horizontal margin added to the warning zone extent, in km. */
    val offsetHkm: Double = 4.0,
    /** OFFSET only: vertical margin added to the warning ceiling, in [offsetVUnit]. */
    val offsetV: Double = 300.0,
    val offsetVUnit: CeilingUnit = CeilingUnit.FT,
    /** Own ceiling for non-OFFSET modes. */
    val ceilingValue: Double = 3000.0,
    val ceilingUnit: CeilingUnit = CeilingUnit.FT,
    val ceilingRef: CeilingRef = CeilingRef.ASL,
)

/** One aircraft as received from an ADS-B feed or injected by Test Mode. */
data class Aircraft(
    val hex: String,
    val callsign: String? = null,
    val registration: String? = null,
    val type: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Barometric altitude in feet, or null when the feed reported "ground" or nothing. */
    val altBaroFt: Double? = null,
    /** Geometric altitude in feet (fallback when alt_baro is unusable). */
    val altGeomFt: Double? = null,
    val groundSpeedKt: Double? = null,
    val trackDeg: Double? = null,
    val seenSec: Double? = null,
    /** Seconds since the aggregator last received a position for this aircraft. */
    val seenPosSec: Double? = null,
    val mlat: Boolean = false,
    val dbFlags: Long? = null,
)

/** An aircraft that passed both the geofence and the altitude ceiling. */
data class MatchedAircraft(
    val aircraft: Aircraft,
    val distanceKm: Double,
    val bearingDeg: Double,
    val bearingCompass: String,
    val altMslFt: Double?,
    val aglFt: Double?,
    val tier: Tier = Tier.WATCH,
)