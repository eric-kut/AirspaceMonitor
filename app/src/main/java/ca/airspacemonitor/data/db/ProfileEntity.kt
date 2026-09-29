package ca.airspacemonitor.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.GeofenceMode
import ca.airspacemonitor.domain.Profile
import ca.airspacemonitor.domain.WatchMode
import ca.airspacemonitor.domain.WatchVolume
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The mandatory warning zone is stored in the base geofence columns; the
 * optional outer watch layer lives in the watch* columns (all null = watch off).
 * All ceiling and vertical-offset values are feet (the display unit is global).
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val geofenceMode: String,
    @ColumnInfo(name = "centerLat") val centerLat: Double?,
    @ColumnInfo(name = "centerLon") val centerLon: Double?,
    val radiusKm: Double?,
    /** JSON-encoded list of "lat,lon" strings; null unless POLYGON. */
    val polygonJson: String?,
    val ceilingValue: Double,
    val ceilingRef: String,
    val terrainElevM: Double?,
    val pollIntervalSec: Int,
    /** Re-alert cooldown in (fractional) minutes. */
    val alertCooldownMin: Double,
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val warningVoiceEnabled: Boolean,
    val watchVoiceEnabled: Boolean,
    val watchMode: String? = null,
    val watchRadiusKm: Double? = null,
    val watchCenterLat: Double? = null,
    val watchCenterLon: Double? = null,
    val watchPolygonJson: String? = null,
    val watchCeilingValue: Double? = null,
    val watchCeilingRef: String? = null,
    val watchOffsetHkm: Double? = null,
    val watchOffsetV: Double? = null,
)

private val json = Json { ignoreUnknownKeys = true }

private fun serializePolygon(points: List<GeoPoint>?): String? =
    points?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it.map { listOf(it.lat, it.lon) }) }

private fun deserializePolygon(raw: String?): List<GeoPoint>? = raw?.let {
    val arrays: List<List<Double>> = json.decodeFromString(it)
    arrays.map { GeoPoint(it[0], it[1]) }
}

fun ProfileEntity.toDomain(): Profile {
    val mode = GeofenceMode.valueOf(geofenceMode)
    val watch = watchMode?.let { raw ->
        val m = runCatching { WatchMode.valueOf(raw) }.getOrNull() ?: return@let null
        WatchVolume(
            mode = m,
            radiusKm = watchRadiusKm ?: 10.0,
            centerLat = watchCenterLat,
            centerLon = watchCenterLon,
            polygon = deserializePolygon(watchPolygonJson),
            offsetHkm = watchOffsetHkm ?: 4.0,
            offsetV = watchOffsetV ?: 300.0,
            ceilingValue = watchCeilingValue ?: 3000.0,
            ceilingRef = watchCeilingRef?.let { runCatching { CeilingRef.valueOf(it) }.getOrNull() }
                ?: CeilingRef.ASL,
        )
    }
    return Profile(
        id = id,
        name = name,
        geofenceMode = mode,
        centerLat = centerLat,
        centerLon = centerLon,
        radiusKm = radiusKm,
        polygon = deserializePolygon(polygonJson),
        ceilingValue = ceilingValue,
        ceilingRef = CeilingRef.valueOf(ceilingRef),
        terrainElevM = terrainElevM,
        pollIntervalSec = pollIntervalSec,
        alertCooldownMin = alertCooldownMin,
        soundEnabled = soundEnabled,
        vibrationEnabled = vibrationEnabled,
        warningVoiceEnabled = warningVoiceEnabled,
        watchVoiceEnabled = watchVoiceEnabled,
        watch = watch,
    )
}

fun Profile.toEntity(id: Long = this.id): ProfileEntity = ProfileEntity(
    id = id,
    name = name,
    geofenceMode = geofenceMode.name,
    centerLat = if (geofenceMode == GeofenceMode.POLYGON) null else centerLat,
    centerLon = if (geofenceMode == GeofenceMode.POLYGON) null else centerLon,
    radiusKm = if (geofenceMode == GeofenceMode.POLYGON) null else radiusKm,
    polygonJson = if (geofenceMode == GeofenceMode.POLYGON) serializePolygon(polygon) else null,
    ceilingValue = ceilingValue,
    ceilingRef = ceilingRef.name,
    terrainElevM = terrainElevM,
    pollIntervalSec = pollIntervalSec,
    alertCooldownMin = alertCooldownMin,
    soundEnabled = soundEnabled,
    vibrationEnabled = vibrationEnabled,
    warningVoiceEnabled = warningVoiceEnabled,
    watchVoiceEnabled = watchVoiceEnabled,
    watchMode = watch?.mode?.name,
    watchRadiusKm = if (watch?.mode == WatchMode.FOLLOW_PHONE || watch?.mode == WatchMode.FIXED_CIRCLE) {
        watch?.radiusKm
    } else {
        null
    },
    watchCenterLat = if (watch?.mode == WatchMode.FIXED_CIRCLE) watch?.centerLat else null,
    watchCenterLon = if (watch?.mode == WatchMode.FIXED_CIRCLE) watch?.centerLon else null,
    watchPolygonJson = if (watch?.mode == WatchMode.POLYGON) serializePolygon(watch?.polygon) else null,
    watchCeilingValue = if (watch?.mode == WatchMode.OFFSET) null else watch?.ceilingValue,
    watchCeilingRef = if (watch?.mode == WatchMode.OFFSET) null else watch?.ceilingRef?.name,
    watchOffsetHkm = if (watch?.mode == WatchMode.OFFSET) watch?.offsetHkm else null,
    watchOffsetV = if (watch?.mode == WatchMode.OFFSET) watch?.offsetV else null,
)