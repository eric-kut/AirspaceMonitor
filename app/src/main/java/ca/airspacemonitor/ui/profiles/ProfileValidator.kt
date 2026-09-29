package ca.airspacemonitor.ui.profiles

import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.GeofenceMode
import ca.airspacemonitor.domain.Profile
import ca.airspacemonitor.domain.Units
import ca.airspacemonitor.domain.WatchMode
import java.util.Locale

/** Bounds shared with the editor sliders; validation must match what the UI offers. */
const val MAX_POLYGON_VERTICES = 64

/**
 * Locale-tolerant decimal input: the editor writes fields with Locale.US
 * (dot), but a user keyboard (or a map/GPS autofill on a comma-decimal
 * locale) can produce commas. Trim, normalize ',' -> '.', parse.
 */
fun parseDecimal(raw: String): Double? {
    val normalized = raw.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    return normalized.toDoubleOrNull()
}

/** Locale-invariant coordinate text so toDouble back / parseDecimal agree everywhere. */
fun formatCoord(v: Double): String = String.format(Locale.US, "%.6f", v)

fun formatTerrain(meters: Double): String = String.format(Locale.US, "%.1f", meters)

/** Smallest free "Profile N" name not in [existingNames]. */
fun suggestedProfileName(existingNames: Set<String>): String {
    val used = existingNames.mapNotNull { name ->
        Regex("""^Profile\s+(\d+)$""").find(name.trim())?.groupValues?.get(1)?.toIntOrNull()
    }.toSet()
    var n = 1
    while (n in used) n++
    return "Profile $n"
}

/** Everything the validator needs, in display units where applicable. */
data class ProfileDraft(
    val id: Long = 0L,
    val name: String = "",
    val geofenceMode: GeofenceMode = GeofenceMode.FIXED_CIRCLE,
    val centerLatText: String = "",
    val centerLonText: String = "",
    val radiusKm: Double = 5.0,
    val polygon: List<GeoPoint> = emptyList(),
    val ceilingText: String = "",
    /** Global display unit; the draft converts to feet before building the Profile. */
    val altitudeUnit: AltitudeUnit = AltitudeUnit.FT,
    val ceilingRef: CeilingRef = CeilingRef.ASL,
    /** Terrain elevation, always meters internally. */
    val terrainText: String = "",
    val watchEnabled: Boolean = false,
    val watchMode: WatchMode = WatchMode.OFFSET,
    val watchRadiusKm: Double = 10.0,
    val watchCenterLatText: String = "",
    val watchCenterLonText: String = "",
    val watchPolygon: List<GeoPoint> = emptyList(),
    val watchCeilingText: String = "",
    val watchCeilingRef: CeilingRef = CeilingRef.ASL,
    val watchOffsetHkm: Double = 4.0,
    val watchOffsetVText: String = "",
    val pollIntervalSec: Int = 12,
    val cooldownMin: Double = 2.0,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val warningVoiceEnabled: Boolean = false,
    val watchVoiceEnabled: Boolean = false,
)

/** A validated profile ready to save, or a failure with a user-facing message. */
sealed interface SaveAttempt {
    data class Success(val profile: Profile) : SaveAttempt
    /**
     * [needsNameDialog] marks the specific "no name" failure, which gets the
     * suggestion dialog instead of a plain message.
     */
    data class Failure(val message: String, val needsNameDialog: Boolean, val suggestedName: String? = null) : SaveAttempt
}

private fun ceilingToFt(text: String, unit: AltitudeUnit): Double? {
    val value = parseDecimal(text) ?: return null
    if (value <= 0.0) return null
    return when (unit) {
        AltitudeUnit.FT -> value
        AltitudeUnit.M -> Units.metersToFeet(value)
    }
}

/**
 * Validates a draft in the exact order the editor presents its fields, so the
 * reported error points at the first thing the user must fix.
 */
fun validateDraft(draft: ProfileDraft, existingNames: Set<String>): SaveAttempt {
    val name = draft.name.trim()
    if (name.isEmpty()) {
        return SaveAttempt.Failure(
            "The profile needs a name. A free suggestion is filled in below.",
            needsNameDialog = true,
            suggestedName = suggestedProfileName(existingNames),
        )
    }

    // ---- Warning zone (mandatory) ----------------------------------------
    val center: Pair<Double, Double>?
    if (draft.geofenceMode == GeofenceMode.POLYGON) {
        center = null
        if (draft.polygon.size < 3) {
            return SaveAttempt.Failure(
                "The warning polygon needs at least 3 vertices — tap the map above to add them.",
                needsNameDialog = false,
            )
        }
        if (draft.polygon.size > MAX_POLYGON_VERTICES) {
            return SaveAttempt.Failure(
                "The warning polygon can have at most $MAX_POLYGON_VERTICES vertices.",
                needsNameDialog = false,
            )
        }
    } else {
        val lat = parseDecimal(draft.centerLatText)
        val lon = parseDecimal(draft.centerLonText)
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return SaveAttempt.Failure(
                "The warning zone center must be valid coordinates (latitude -90..90, longitude -180..180).",
                needsNameDialog = false,
            )
        }
        if (draft.radiusKm !in 2.0..30.0) {
            return SaveAttempt.Failure(
                "The warning zone radius must be between 2 and 30 km.",
                needsNameDialog = false,
            )
        }
        center = lat to lon
    }

    val ceilingFt = ceilingToFt(draft.ceilingText, draft.altitudeUnit)
    if (ceilingFt == null) {
        return SaveAttempt.Failure(
            "The warning ceiling must be a positive number, in ${if (draft.altitudeUnit == AltitudeUnit.FT) "feet" else "meters"}.",
            needsNameDialog = false,
        )
    }
    val terrain = parseDecimal(draft.terrainText)
    if (draft.ceilingRef == CeilingRef.AGL && terrain == null) {
        return SaveAttempt.Failure(
            "AGL warning ceilings need a terrain elevation — fetch it, or enter it manually.",
            needsNameDialog = false,
        )
    }
    if (terrain != null && terrain !in -500.0..9000.0) {
        return SaveAttempt.Failure(
            "The terrain elevation must be between -500 and 9000 m.",
            needsNameDialog = false,
        )
    }

    // ---- Watch layer (optional) ------------------------------------------
    var watch: ca.airspacemonitor.domain.WatchVolume? = null
    if (draft.watchEnabled) {
        when (draft.watchMode) {
            WatchMode.OFFSET -> {
                if (draft.watchOffsetHkm !in 0.5..20.0) {
                    return SaveAttempt.Failure(
                        "The watch horizontal offset must be between 0.5 and 20 km.",
                        needsNameDialog = false,
                    )
                }
                val offsetFt = ceilingToFt(draft.watchOffsetVText, draft.altitudeUnit)
                if (offsetFt == null) {
                    return SaveAttempt.Failure(
                        "The watch vertical offset must be a positive number, in " +
                            if (draft.altitudeUnit == AltitudeUnit.FT) "feet." else "meters.",
                        needsNameDialog = false,
                    )
                }
                watch = ca.airspacemonitor.domain.WatchVolume(
                    mode = WatchMode.OFFSET,
                    offsetHkm = draft.watchOffsetHkm,
                    offsetV = offsetFt,
                )
            }
            WatchMode.FOLLOW_PHONE, WatchMode.FIXED_CIRCLE -> {
                if (draft.watchRadiusKm !in 2.0..30.0) {
                    return SaveAttempt.Failure(
                        "The watch radius must be between 2 and 30 km.",
                        needsNameDialog = false,
                    )
                }
                val watchCeilingFt = ceilingToFt(draft.watchCeilingText, draft.altitudeUnit)
                if (watchCeilingFt == null) {
                    return SaveAttempt.Failure(
                        "The watch ceiling must be a positive number, in " +
                            if (draft.altitudeUnit == AltitudeUnit.FT) "feet." else "meters.",
                        needsNameDialog = false,
                    )
                }
                if (draft.watchCeilingRef == CeilingRef.AGL && terrain == null) {
                    return SaveAttempt.Failure(
                        "AGL watch ceilings need a terrain elevation — the warning zone supplies it, " +
                            "so set the warning ceiling reference to AGL and fetch/enter an elevation.",
                        needsNameDialog = false,
                    )
                }
                if (draft.watchMode == WatchMode.FIXED_CIRCLE) {
                    val wLat = parseDecimal(draft.watchCenterLatText.ifBlank { draft.centerLatText })
                    val wLon = parseDecimal(draft.watchCenterLonText.ifBlank { draft.centerLonText })
                    if (wLat == null || wLon == null || wLat !in -90.0..90.0 || wLon !in -180.0..180.0) {
                        return SaveAttempt.Failure(
                            "The watch center must be valid coordinates — tap the watch map to set it.",
                            needsNameDialog = false,
                        )
                    }
                    watch = ca.airspacemonitor.domain.WatchVolume(
                        mode = WatchMode.FIXED_CIRCLE,
                        radiusKm = draft.watchRadiusKm,
                        centerLat = wLat,
                        centerLon = wLon,
                        ceilingValue = watchCeilingFt,
                        ceilingRef = draft.watchCeilingRef,
                    )
                } else {
                    watch = ca.airspacemonitor.domain.WatchVolume(
                        mode = WatchMode.FOLLOW_PHONE,
                        radiusKm = draft.watchRadiusKm,
                        ceilingValue = watchCeilingFt,
                        ceilingRef = draft.watchCeilingRef,
                    )
                }
            }
            WatchMode.POLYGON -> {
                if (draft.watchPolygon.size < 3) {
                    return SaveAttempt.Failure(
                        "The watch polygon needs at least 3 vertices — tap the watch map below to add them.",
                        needsNameDialog = false,
                    )
                }
                if (draft.watchPolygon.size > MAX_POLYGON_VERTICES) {
                    return SaveAttempt.Failure(
                        "The watch polygon can have at most $MAX_POLYGON_VERTICES vertices.",
                        needsNameDialog = false,
                    )
                }
                val watchCeilingFt = ceilingToFt(draft.watchCeilingText, draft.altitudeUnit)
                if (watchCeilingFt == null) {
                    return SaveAttempt.Failure(
                        "The watch ceiling must be a positive number, in " +
                            if (draft.altitudeUnit == AltitudeUnit.FT) "feet." else "meters.",
                        needsNameDialog = false,
                    )
                }
                if (draft.watchCeilingRef == CeilingRef.AGL && terrain == null) {
                    return SaveAttempt.Failure(
                        "AGL watch ceilings need a terrain elevation — the warning zone supplies it, " +
                            "so set the warning ceiling reference to AGL and fetch/enter an elevation.",
                        needsNameDialog = false,
                    )
                }
                watch = ca.airspacemonitor.domain.WatchVolume(
                    mode = WatchMode.POLYGON,
                    polygon = draft.watchPolygon,
                    ceilingValue = watchCeilingFt,
                    ceilingRef = draft.watchCeilingRef,
                )
            }
        }
    }

    val profile = Profile(
        id = draft.id,
        name = name,
        geofenceMode = draft.geofenceMode,
        centerLat = center?.first,
        centerLon = center?.second,
        radiusKm = if (draft.geofenceMode == GeofenceMode.POLYGON) null else draft.radiusKm,
        polygon = if (draft.geofenceMode == GeofenceMode.POLYGON) draft.polygon else null,
        ceilingValue = ceilingFt,
        ceilingRef = draft.ceilingRef,
        terrainElevM = if (draft.ceilingRef == CeilingRef.AGL) terrain else null,
        pollIntervalSec = draft.pollIntervalSec,
        alertCooldownMin = draft.cooldownMin,
        soundEnabled = draft.soundEnabled,
        vibrationEnabled = draft.vibrationEnabled,
        warningVoiceEnabled = draft.warningVoiceEnabled,
        watchVoiceEnabled = draft.watchVoiceEnabled,
        watch = watch,
    )
    return SaveAttempt.Success(profile)
}