package ca.trafficwatcher.ui.profiles

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.trafficwatcher.di.AppContainer
import ca.trafficwatcher.domain.CeilingRef
import ca.trafficwatcher.domain.CeilingUnit
import ca.trafficwatcher.domain.GeoMath
import ca.trafficwatcher.domain.GeoPoint
import ca.trafficwatcher.domain.GeofenceMode
import ca.trafficwatcher.domain.Profile
import ca.trafficwatcher.domain.WatchMode
import ca.trafficwatcher.domain.WatchVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_POLYGON_VERTICES = 64

class ProfileEditorViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

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
        val ceilingValueText: String = "400",
        val ceilingUnit: CeilingUnit = CeilingUnit.FT,
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
        val watchCeilingUnit: CeilingUnit = CeilingUnit.FT,
        val watchCeilingRef: CeilingRef = CeilingRef.ASL,
        val watchOffsetHkm: Double = 4.0,
        val watchOffsetVText: String = "300",
        val watchOffsetVUnit: CeilingUnit = CeilingUnit.FT,
        val pollIntervalSec: Int = 12,
        val cooldownMin: Int = 2,
        val soundEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        val error: String? = null,
        val elevationStatus: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun load(profileId: Long?) {
        if (_state.value.loaded) return
        if (profileId == null || profileId <= 0) {
            _state.value = UiState(loaded = true)
            return
        }
        viewModelScope.launch {
            val p = container.profileRepository.get(profileId) ?: return@launch
            _state.value = UiState(
                loaded = true,
                id = p.id,
                name = p.name,
                geofenceMode = p.geofenceMode,
                centerLatText = p.centerLat?.let { "%.6f".format(it) } ?: "",
                centerLonText = p.centerLon?.let { "%.6f".format(it) } ?: "",
                radiusKm = p.radiusKm ?: 5.0,
                polygon = p.polygon ?: emptyList(),
                ceilingValueText = p.ceilingValue.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() },
                ceilingUnit = p.ceilingUnit,
                ceilingRef = p.ceilingRef,
                terrainText = p.terrainElevM?.let { "%.1f".format(it) } ?: "",
                watchEnabled = p.watch != null,
                watchMode = p.watch?.mode ?: WatchMode.OFFSET,
                watchRadiusKm = p.watch?.radiusKm ?: 10.0,
                watchCenterLatText = p.watch?.centerLat?.let { "%.6f".format(it) }
                    ?: p.centerLat?.let { "%.6f".format(it) }
                    ?: "",
                watchCenterLonText = p.watch?.centerLon?.let { "%.6f".format(it) }
                    ?: p.centerLon?.let { "%.6f".format(it) }
                    ?: "",
                watchPolygon = p.watch?.polygon ?: emptyList(),
                watchCeilingText = p.watch?.ceilingValue
                    ?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                    ?: "1500",
                watchCeilingUnit = p.watch?.ceilingUnit ?: CeilingUnit.FT,
                watchCeilingRef = p.watch?.ceilingRef ?: CeilingRef.ASL,
                watchOffsetHkm = p.watch?.offsetHkm ?: 4.0,
                watchOffsetVText = p.watch?.offsetV
                    ?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                    ?: "300",
                watchOffsetVUnit = p.watch?.offsetVUnit ?: CeilingUnit.FT,
                pollIntervalSec = p.pollIntervalSec,
                cooldownMin = p.alertCooldownMin,
                soundEnabled = p.soundEnabled,
                vibrationEnabled = p.vibrationEnabled,
            )
        }
    }

    private fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }

    // ---- Warning zone (mandatory) ----------------------------------------
    fun setName(v: String) = update { it.copy(name = v) }
    fun setMode(mode: GeofenceMode) = update { it.copy(geofenceMode = mode) }
    fun setCenterLat(v: String) = update { it.copy(centerLatText = v) }
    fun setCenterLon(v: String) = update { it.copy(centerLonText = v) }
    fun setRadius(km: Double) = update { it.copy(radiusKm = km) }
    fun setCeilingValue(v: String) = update { it.copy(ceilingValueText = v) }
    fun setCeilingUnit(u: CeilingUnit) = update { it.copy(ceilingUnit = u) }
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
                    centerLatText = "%.6f".format(point.lat),
                    centerLonText = "%.6f".format(point.lon),
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
    fun setWatchCeilingUnit(u: CeilingUnit) = update { it.copy(watchCeilingUnit = u) }
    fun setWatchCeilingRef(r: CeilingRef) = update { it.copy(watchCeilingRef = r) }
    fun setWatchOffsetHkm(km: Double) = update { it.copy(watchOffsetHkm = km) }
    fun setWatchOffsetV(v: String) = update { it.copy(watchOffsetVText = v) }
    fun setWatchOffsetVUnit(u: CeilingUnit) = update { it.copy(watchOffsetVUnit = u) }
    fun undoWatchVertex() = update { s ->
        if (s.watchPolygon.isEmpty()) s else s.copy(watchPolygon = s.watchPolygon.dropLast(1))
    }
    fun setPollInterval(sec: Int) = update { it.copy(pollIntervalSec = sec) }
    fun setCooldown(min: Int) = update { it.copy(cooldownMin = min) }
    fun setSound(b: Boolean) = update { it.copy(soundEnabled = b) }
    fun setVibration(b: Boolean) = update { it.copy(vibrationEnabled = b) }

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
                    watchCenterLatText = "%.6f".format(point.lat),
                    watchCenterLonText = "%.6f".format(point.lon),
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
                centerLatText = "%.6f".format(fix.latitude),
                centerLonText = "%.6f".format(fix.longitude),
                error = null,
            )
        }
    }

    fun fetchElevation() {
        val lat = _state.value.centerLatText.toDoubleOrNull()
        val lon = _state.value.centerLonText.toDoubleOrNull()
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
                        s.copy(terrainText = "%.1f".format(meters), elevationStatus = "Terrain: $meters m (Open-Meteo ~90 m DEM)")
                    }
                },
                onFailure = { e ->
                    update { s -> s.copy(elevationStatus = null, error = "Elevation fetch failed: ${e.message}") }
                },
            )
        }
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        // ---- Warning zone (mandatory) -------------------------------------
        if (s.name.isBlank()) {
            update { it.copy(error = "Name is required.") }
            return
        }
        if (s.geofenceMode != GeofenceMode.POLYGON) {
            val lat = s.centerLatText.toDoubleOrNull()
            val lon = s.centerLonText.toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                update { it.copy(error = "Warning zone center must be valid coordinates.") }
                return
            }
            if (s.radiusKm !in 0.5..20.0) {
                update { it.copy(error = "Warning zone radius must be between 0.5 and 20 km.") }
                return
            }
        } else {
            if (s.polygon.size < 3) {
                update { it.copy(error = "Warning polygon needs at least 3 vertices (tap the map to add).") }
                return
            }
            if (s.polygon.size > MAX_POLYGON_VERTICES) {
                update { it.copy(error = "Warning polygon can have at most $MAX_POLYGON_VERTICES vertices.") }
                return
            }
        }
        val ceiling = s.ceilingValueText.toDoubleOrNull()
        if (ceiling == null || ceiling <= 0.0) {
            update { it.copy(error = "Warning ceiling must be a positive number.") }
            return
        }
        val terrain = s.terrainText.toDoubleOrNull()
        if (s.ceilingRef == CeilingRef.AGL && terrain == null) {
            update { it.copy(error = "AGL warning ceiling requires a terrain elevation (fetch or enter manually).") }
            return
        }
        if (terrain != null && terrain !in -500.0..9000.0) {
            update { it.copy(error = "Terrain elevation out of range.") }
            return
        }

        // ---- Watch layer (optional) ---------------------------------------
        var watch: WatchVolume? = null
        if (s.watchEnabled) {
            when (s.watchMode) {
                WatchMode.OFFSET -> {
                    if (s.watchOffsetHkm !in 0.5..20.0) {
                        update { it.copy(error = "Watch horizontal offset must be between 0.5 and 20 km.") }
                        return
                    }
                    val offsetV = s.watchOffsetVText.toDoubleOrNull()
                    if (offsetV == null || offsetV <= 0.0) {
                        update { it.copy(error = "Watch vertical offset must be a positive number.") }
                        return
                    }
                    watch = WatchVolume(
                        mode = WatchMode.OFFSET,
                        offsetHkm = s.watchOffsetHkm,
                        offsetV = offsetV,
                        offsetVUnit = s.watchOffsetVUnit,
                    )
                }
                WatchMode.FOLLOW_PHONE -> {
                    if (s.watchRadiusKm !in 0.5..20.0) {
                        update { it.copy(error = "Watch radius must be between 0.5 and 20 km.") }
                        return
                    }
                    val watchCeiling = s.watchCeilingText.toDoubleOrNull()
                    if (watchCeiling == null || watchCeiling <= 0.0) {
                        update { it.copy(error = "Watch ceiling must be a positive number.") }
                        return
                    }
                    if (s.watchCeilingRef == CeilingRef.AGL && terrain == null) {
                        update { it.copy(error = "AGL watch ceiling requires a terrain elevation (warning zone).") }
                        return
                    }
                    watch = WatchVolume(
                        mode = WatchMode.FOLLOW_PHONE,
                        radiusKm = s.watchRadiusKm,
                        ceilingValue = watchCeiling,
                        ceilingUnit = s.watchCeilingUnit,
                        ceilingRef = s.watchCeilingRef,
                    )
                }
                WatchMode.FIXED_CIRCLE -> {
                    if (s.watchRadiusKm !in 0.5..20.0) {
                        update { it.copy(error = "Watch radius must be between 0.5 and 20 km.") }
                        return
                    }
                    val watchCeiling = s.watchCeilingText.toDoubleOrNull()
                    if (watchCeiling == null || watchCeiling <= 0.0) {
                        update { it.copy(error = "Watch ceiling must be a positive number.") }
                        return
                    }
                    if (s.watchCeilingRef == CeilingRef.AGL && terrain == null) {
                        update { it.copy(error = "AGL watch ceiling requires a terrain elevation (warning zone).") }
                        return
                    }
                    val wLat = s.watchCenterLatText.ifBlank { s.centerLatText }.toDoubleOrNull()
                    val wLon = s.watchCenterLonText.ifBlank { s.centerLonText }.toDoubleOrNull()
                    if (wLat == null || wLon == null || wLat !in -90.0..90.0 || wLon !in -180.0..180.0) {
                        update { it.copy(error = "Watch center must be valid coordinates (tap the watch map to set it).") }
                        return
                    }
                    watch = WatchVolume(
                        mode = WatchMode.FIXED_CIRCLE,
                        radiusKm = s.watchRadiusKm,
                        centerLat = wLat,
                        centerLon = wLon,
                        ceilingValue = watchCeiling,
                        ceilingUnit = s.watchCeilingUnit,
                        ceilingRef = s.watchCeilingRef,
                    )
                }
                WatchMode.POLYGON -> {
                    if (s.watchPolygon.size < 3) {
                        update { it.copy(error = "Watch polygon needs at least 3 vertices (tap the watch map below).") }
                        return
                    }
                    if (s.watchPolygon.size > MAX_POLYGON_VERTICES) {
                        update { it.copy(error = "Watch polygon can have at most $MAX_POLYGON_VERTICES vertices.") }
                        return
                    }
                    val watchCeiling = s.watchCeilingText.toDoubleOrNull()
                    if (watchCeiling == null || watchCeiling <= 0.0) {
                        update { it.copy(error = "Watch ceiling must be a positive number.") }
                        return
                    }
                    if (s.watchCeilingRef == CeilingRef.AGL && terrain == null) {
                        update { it.copy(error = "AGL watch ceiling requires a terrain elevation (warning zone).") }
                        return
                    }
                    watch = WatchVolume(
                        mode = WatchMode.POLYGON,
                        polygon = s.watchPolygon,
                        ceilingValue = watchCeiling,
                        ceilingUnit = s.watchCeilingUnit,
                        ceilingRef = s.watchCeilingRef,
                    )
                }
            }
        }

        val profile = Profile(
            id = s.id,
            name = s.name.trim(),
            geofenceMode = s.geofenceMode,
            centerLat = if (s.geofenceMode == GeofenceMode.POLYGON) null else s.centerLatText.toDouble(),
            centerLon = if (s.geofenceMode == GeofenceMode.POLYGON) null else s.centerLonText.toDouble(),
            radiusKm = if (s.geofenceMode == GeofenceMode.POLYGON) null else s.radiusKm,
            polygon = if (s.geofenceMode == GeofenceMode.POLYGON) s.polygon else null,
            ceilingValue = ceiling,
            ceilingUnit = s.ceilingUnit,
            ceilingRef = s.ceilingRef,
            terrainElevM = if (s.ceilingRef == CeilingRef.AGL) terrain else null,
            pollIntervalSec = s.pollIntervalSec,
            alertCooldownMin = s.cooldownMin,
            soundEnabled = s.soundEnabled,
            vibrationEnabled = s.vibrationEnabled,
            watch = watch,
        )
        viewModelScope.launch {
            container.profileRepository.save(profile)
            onSaved()
        }
    }
}