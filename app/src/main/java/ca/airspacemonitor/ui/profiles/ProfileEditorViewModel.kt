package ca.airspacemonitor.ui.profiles

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.data.DistanceUnit
import ca.airspacemonitor.di.AppContainer
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.GeoMath
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.GeofenceMode
import ca.airspacemonitor.domain.Profile
import ca.airspacemonitor.domain.Units
import ca.airspacemonitor.domain.WatchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileEditorViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    data class SaveDialogUiState(
        val message: String,
        val isNameDialog: Boolean = false,
        val nameText: String = "",
        /** True after the user typed a name that is already in use. */
        val nameTaken: Boolean = false,
    )

    data class UiState(
        val loaded: Boolean = false,
        val id: Long = 0L,
        val name: String = "",
        // ---- Mandatory warning zone (base geofence) -----------------------
        val geofenceMode: GeofenceMode = GeofenceMode.FIXED_CIRCLE,
        val centerLatText: String = "45.4215",
        val centerLonText: String = "-75.6972",
        val radiusKm: Double = 5.0,
        val polygon: List<GeoPoint> = emptyList(),
        /** Ceiling texts are in the global display unit; stored values are ft. */
        val ceilingValueText: String = "400",
        val ceilingRef: CeilingRef = CeilingRef.ASL,
        val terrainText: String = "",
        // ---- Optional outer watch layer -----------------------------------
        val watchEnabled: Boolean = false,
        val watchMode: WatchMode = WatchMode.OFFSET,
        val watchRadiusKm: Double = 10.0,
        val watchCenterLatText: String = "",
        val watchCenterLonText: String = "",
        val watchPolygon: List<GeoPoint> = emptyList(),
        val watchCeilingText: String = "1500",
        val watchCeilingRef: CeilingRef = CeilingRef.ASL,
        val watchOffsetHkm: Double = 4.0,
        val watchOffsetVText: String = "300",
        // ---- Timing / alerts ----------------------------------------------
        val pollIntervalSec: Int = 12,
        val cooldownMin: Double = 2.0,
        val soundEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        val warningVoiceEnabled: Boolean = false,
        val watchVoiceEnabled: Boolean = false,
        // ---- Captured global unit settings --------------------------------
        val altitudeUnit: AltitudeUnit = AltitudeUnit.FT,
        val distanceUnit: DistanceUnit = DistanceUnit.KM,
        // ---- Feedback ------------------------------------------------------
        /** Inline errors (GPS/elevation/map interactions); save failures use [saveDialog]. */
        val error: String? = null,
        val elevationStatus: String? = null,
        val saveDialog: SaveDialogUiState? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun load(profileId: Long?) {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val settings = container.settingsStore.current()
            val s = if (profileId == null || profileId <= 0) {
                val (ceiling, watchCeiling, offsetV) = defaultDisplayTexts(settings.altitudeUnit)
                UiState(
                    loaded = true,
                    altitudeUnit = settings.altitudeUnit,
                    distanceUnit = settings.distanceUnit,
                    ceilingValueText = ceiling,
                    watchCeilingText = watchCeiling,
                    watchOffsetVText = offsetV,
                )
            } else {
                val p = container.profileRepository.get(profileId) ?: return@launch
                UiState(
                    loaded = true,
                    id = p.id,
                    name = p.name,
                    altitudeUnit = settings.altitudeUnit,
                    distanceUnit = settings.distanceUnit,
                    geofenceMode = p.geofenceMode,
                    centerLatText = p.centerLat?.let { formatCoord(it) } ?: "",
                    centerLonText = p.centerLon?.let { formatCoord(it) } ?: "",
                    radiusKm = p.radiusKm ?: 5.0,
                    polygon = p.polygon ?: emptyList(),
                    ceilingValueText = toDisplayText(p.ceilingValue, settings.altitudeUnit),
                    ceilingRef = p.ceilingRef,
                    terrainText = p.terrainElevM?.let { formatTerrain(it) } ?: "",
                    watchEnabled = p.watch != null,
                    watchMode = p.watch?.mode ?: WatchMode.OFFSET,
                    watchRadiusKm = p.watch?.radiusKm ?: 10.0,
                    watchCenterLatText = p.watch?.centerLat?.let { formatCoord(it) }
                        ?.takeIf { p.watch?.mode == WatchMode.FIXED_CIRCLE }
                        ?: "",
                    watchCenterLonText = p.watch?.centerLon?.let { formatCoord(it) }
                        ?.takeIf { p.watch?.mode == WatchMode.FIXED_CIRCLE }
                        ?: "",
                    watchPolygon = p.watch?.polygon ?: emptyList(),
                    watchCeilingText = toDisplayText(p.watch?.ceilingValue ?: 1500.0, settings.altitudeUnit),
                    watchCeilingRef = p.watch?.ceilingRef ?: CeilingRef.ASL,
                    watchOffsetHkm = p.watch?.offsetHkm ?: 4.0,
                    watchOffsetVText = toDisplayText(p.watch?.offsetV ?: 300.0, settings.altitudeUnit),
                    pollIntervalSec = p.pollIntervalSec,
                    cooldownMin = p.alertCooldownMin,
                    soundEnabled = p.soundEnabled,
                    vibrationEnabled = p.vibrationEnabled,
                    warningVoiceEnabled = p.warningVoiceEnabled,
                    watchVoiceEnabled = p.watchVoiceEnabled,
                )
            }
            _state.value = s
        }
    }

    private fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }

    // ---- Warning zone (mandatory) ----------------------------------------
    fun setName(v: String) = update { it.copy(name = v, saveDialog = null) }
    fun setMode(mode: GeofenceMode) = update { it.copy(geofenceMode = mode) }
    fun setCenterLat(v: String) = update { it.copy(centerLatText = v) }
    fun setCenterLon(v: String) = update { it.copy(centerLonText = v) }
    fun setRadius(km: Double) = update { it.copy(radiusKm = km) }
    fun setCeilingValue(v: String) = update { it.copy(ceilingValueText = v) }
    fun setCeilingRef(r: CeilingRef) = update { it.copy(ceilingRef = r) }
    fun setTerrain(v: String) = update { it.copy(terrainText = v, elevationStatus = null) }
    fun undoVertex() = update { s ->
        if (s.polygon.isEmpty()) s else s.copy(polygon = s.polygon.dropLast(1))
    }

    /** Top-map tap edits the mandatory warning zone. */
    fun onMapTap(point: GeoPoint) {
        update { s ->
            when (s.geofenceMode) {
                GeofenceMode.POLYGON ->
                    if (s.polygon.size >= MAX_POLYGON_VERTICES) {
                        s.copy(error = "Polygon can have at most $MAX_POLYGON_VERTICES vertices.")
                    } else {
                        s.copy(polygon = s.polygon + point, error = null)
                    }
                else -> s.copy(
                    centerLatText = formatCoord(point.lat),
                    centerLonText = formatCoord(point.lon),
                )
            }
        }
    }

    /** Long-press deletes the nearest warning-zone vertex within 1 km of the press. */
    fun onMapLongPress(point: GeoPoint) {
        update { s ->
            if (s.geofenceMode != GeofenceMode.POLYGON || s.polygon.isEmpty()) s
            else {
                val nearest = s.polygon.minByOrNull { GeoMath.haversineKm(it, point) }
                if (nearest != null && GeoMath.haversineKm(nearest, point) <= 1.0) {
                    s.copy(polygon = s.polygon - nearest)
                } else s
            }
        }
    }

    // ---- Watch layer (optional) ------------------------------------------
    fun setWatchEnabled(b: Boolean) = update { it.copy(watchEnabled = b) }
    fun setWatchMode(m: WatchMode) = update { it.copy(watchMode = m) }
    fun setWatchRadius(km: Double) = update { it.copy(watchRadiusKm = km) }
    fun setWatchCenterLat(v: String) = update { it.copy(watchCenterLatText = v) }
    fun setWatchCenterLon(v: String) = update { it.copy(watchCenterLonText = v) }
    fun setWatchCeilingValue(v: String) = update { it.copy(watchCeilingText = v) }
    fun setWatchCeilingRef(r: CeilingRef) = update { it.copy(watchCeilingRef = r) }
    fun setWatchOffsetHkm(km: Double) = update { it.copy(watchOffsetHkm = km) }
    fun setWatchOffsetV(v: String) = update { it.copy(watchOffsetVText = v) }
    fun undoWatchVertex() = update { s ->
        if (s.watchPolygon.isEmpty()) s else s.copy(watchPolygon = s.watchPolygon.dropLast(1))
    }
    fun setPollInterval(sec: Int) = update { it.copy(pollIntervalSec = sec) }
    fun setCooldown(min: Double) = update { it.copy(cooldownMin = min) }
    fun setSound(b: Boolean) = update { it.copy(soundEnabled = b) }
    fun setVibration(b: Boolean) = update { it.copy(vibrationEnabled = b) }
    fun setWarningVoice(b: Boolean) = update { it.copy(warningVoiceEnabled = b) }
    fun setWatchVoice(b: Boolean) = update { it.copy(watchVoiceEnabled = b) }

    /** Second-map tap edits the watch layer (polygon vertices or circle center). */
    fun onWatchMapTap(point: GeoPoint) {
        update { s ->
            when (s.watchMode) {
                WatchMode.POLYGON ->
                    if (s.watchPolygon.size >= MAX_POLYGON_VERTICES) {
                        s.copy(error = "Watch polygon can have at most $MAX_POLYGON_VERTICES vertices.")
                    } else {
                        s.copy(watchPolygon = s.watchPolygon + point, error = null)
                    }
                WatchMode.FIXED_CIRCLE -> s.copy(
                    watchCenterLatText = formatCoord(point.lat),
                    watchCenterLonText = formatCoord(point.lon),
                )
                WatchMode.OFFSET, WatchMode.FOLLOW_PHONE -> s
            }
        }
    }

    /** Long-press deletes the nearest watch-polygon vertex within 1 km of the press. */
    fun onWatchMapLongPress(point: GeoPoint) {
        update { s ->
            if (s.watchMode != WatchMode.POLYGON || s.watchPolygon.isEmpty()) s
            else {
                val nearest = s.watchPolygon.minByOrNull { GeoMath.haversineKm(it, point) }
                if (nearest != null && GeoMath.haversineKm(nearest, point) <= 1.0) {
                    s.copy(watchPolygon = s.watchPolygon - nearest)
                } else s
            }
        }
    }

    fun useMyGpsPosition() {
        val hasPermission = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            update { it.copy(error = "Grant location permission to use your GPS position.") }
            return
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fix = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (fix == null) {
            update { it.copy(error = "No recent GPS fix available. Enable location and try again.") }
            return
        }
        update { s ->
            s.copy(
                centerLatText = formatCoord(fix.latitude),
                centerLonText = formatCoord(fix.longitude),
                error = null,
            )
        }
    }

    fun fetchElevation() {
        val lat = parseDecimal(_state.value.centerLatText)
        val lon = parseDecimal(_state.value.centerLonText)
        if (lat == null || lon == null) {
            update { it.copy(error = "Set the warning zone center before fetching elevation.") }
            return
        }
        update { it.copy(elevationStatus = "Fetching…", error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.openMeteoClient.fetchElevationM(lat, lon)
            }
            result.fold(
                onSuccess = { meters ->
                    update { s ->
                        val status = "Terrain: $meters m (Open-Meteo ~90 m DEM)"
                        s.copy(
                            terrainText = formatTerrain(meters),
                            elevationStatus = if (s.altitudeUnit == AltitudeUnit.M) {
                                status
                            } else {
                                "$status — ${Math.round(Units.feetToMeters(meters))} ft"
                            },
                        )
                    }
                },
                onFailure = { e ->
                    update { s -> s.copy(elevationStatus = null, error = "Elevation fetch failed: ${e.message}") }
                },
            )
        }
    }

    // ---- Save -------------------------------------------------------------

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            val existing = container.profileRepository.profiles
                .first()
                .filter { it.id != _state.value.id }
                .map { it.name }
                .toSet()
            when (val attempt = validateDraft(draft(_state.value), existing)) {
                is SaveAttempt.Failure -> update { it.copy(saveDialog = SaveDialogUiState(attempt.message, attempt.needsNameDialog, attempt.suggestedName ?: "")) }
                is SaveAttempt.Success -> persist(attempt.profile, onSaved)
            }
        }
    }

    fun onSaveDialogNameChanged(v: String) =
        update { it.copy(saveDialog = it.saveDialog?.copy(nameText = v, nameTaken = false)) }

    fun onSaveDialogCancel() = update { it.copy(saveDialog = null) }

    /** OK on the name dialog: accept the (edited) name if free, then save again. */
    fun onSaveDialogOk(onSaved: () -> Unit) {
        val dialog = _state.value.saveDialog ?: return
        val name = dialog.nameText.trim()
        viewModelScope.launch {
            val existing = container.profileRepository.profiles
                .first()
                .filter { it.id != _state.value.id }
                .map { it.name }
                .toSet()
            if (name.isEmpty()) {
                update { s ->
                    s.copy(
                        saveDialog = dialog.copy(
                            message = "The profile needs a name. A free suggestion is filled in below.",
                            nameTaken = true,
                            nameText = suggestedProfileName(existing),
                        ),
                    )
                }
                return@launch
            }
            if (name in existing) {
                update { s ->
                    s.copy(
                        saveDialog = dialog.copy(
                            message = "\"$name\" is already used by another profile. A free suggestion is filled in below.",
                            nameTaken = true,
                            nameText = suggestedProfileName(existing + name),
                        ),
                    )
                }
                return@launch
            }
            update { s -> s.copy(name = name, saveDialog = null) }
            when (val attempt = validateDraft(draft(_state.value), existing)) {
                is SaveAttempt.Failure ->
                    update { s ->
                        s.copy(saveDialog = SaveDialogUiState(attempt.message, attempt.needsNameDialog, attempt.suggestedName ?: ""))
                    }
                is SaveAttempt.Success -> persist(attempt.profile, onSaved)
            }
        }
    }

    private suspend fun persist(profile: Profile, onSaved: () -> Unit) {
        container.profileRepository.save(profile)
        onSaved()
    }

    private fun draft(s: UiState): ProfileDraft = ProfileDraft(
        id = s.id,
        name = s.name,
        geofenceMode = s.geofenceMode,
        centerLatText = s.centerLatText,
        centerLonText = s.centerLonText,
        radiusKm = s.radiusKm,
        polygon = s.polygon,
        ceilingText = s.ceilingValueText,
        altitudeUnit = s.altitudeUnit,
        ceilingRef = s.ceilingRef,
        terrainText = s.terrainText,
        watchEnabled = s.watchEnabled,
        watchMode = s.watchMode,
        watchRadiusKm = s.watchRadiusKm,
        watchCenterLatText = s.watchCenterLatText,
        watchCenterLonText = s.watchCenterLonText,
        watchPolygon = s.watchPolygon,
        watchCeilingText = s.watchCeilingText,
        watchCeilingRef = s.watchCeilingRef,
        watchOffsetHkm = s.watchOffsetHkm,
        watchOffsetVText = s.watchOffsetVText,
        pollIntervalSec = s.pollIntervalSec,
        cooldownMin = s.cooldownMin,
        soundEnabled = s.soundEnabled,
        vibrationEnabled = s.vibrationEnabled,
        warningVoiceEnabled = s.warningVoiceEnabled,
        watchVoiceEnabled = s.watchVoiceEnabled,
    )

    private fun toDisplayText(ft: Double, unit: AltitudeUnit): String {
        val display = when (unit) {
            AltitudeUnit.FT -> ft
            AltitudeUnit.M -> Units.feetToMeters(ft)
        }
        val rounded = Math.round(display).toDouble()
        return if (Math.abs(display - rounded) < 1e-9) {
            rounded.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", display)
        }
    }

    private fun defaultDisplayTexts(unit: AltitudeUnit): Triple<String, String, String> =
        Triple(
            toDisplayText(400.0, unit),
            toDisplayText(1500.0, unit),
            toDisplayText(300.0, unit),
        )
}