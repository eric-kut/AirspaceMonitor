package ca.trafficwatcher.service

import ca.trafficwatcher.domain.GeoPoint
import ca.trafficwatcher.domain.MatchedAircraft
import ca.trafficwatcher.domain.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state of one monitored profile. */
data class ProfileRun(
    val profile: Profile,
    /** GPS fix / profile center / polygon centroid — distances are measured from here. */
    val referencePoint: GeoPoint? = null,
    val tracked: List<MatchedAircraft> = emptyList(),
    val lastPollMs: Long? = null,
    val lastError: String? = null,
    /** Per-hex breadcrumb positions from the last 60 s. */
    val trails: Map<String, List<GeoPoint>> = emptyMap(),
)

/**
 * Shared observable state written by [MonitoringService] and collected by the UI.
 * Keeps the service and Compose decoupled without any framework. Several
 * profiles can be monitored at once, keyed by profile id.
 */
class MonitorState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _runs = MutableStateFlow<Map<Long, ProfileRun>>(emptyMap())
    val runs: StateFlow<Map<Long, ProfileRun>> = _runs.asStateFlow()

    /** Age of the GPS fix in ms; non-null and > 30 000 in FOLLOW_PHONE mode means "fix is stale". */
    private val _gpsFixAgeMs = MutableStateFlow<Long?>(null)
    val gpsFixAgeMs: StateFlow<Long?> = _gpsFixAgeMs.asStateFlow()

    /** True when FOLLOW_PHONE has no permission or no fix at all. */
    private val _gpsUnavailable = MutableStateFlow(false)
    val gpsUnavailable: StateFlow<Boolean> = _gpsUnavailable.asStateFlow()

    /** Latest GPS fix position, for FOLLOW_PHONE warning layers on profiles of other base modes. */
    private val _phonePoint = MutableStateFlow<GeoPoint?>(null)
    val phonePoint: StateFlow<GeoPoint?> = _phonePoint.asStateFlow()

    fun reset() {
        _running.value = false
        _runs.value = emptyMap()
        _gpsFixAgeMs.value = null
        _gpsUnavailable.value = false
        _phonePoint.value = null
    }

    // Service-side setters
    fun setRunning(v: Boolean) { _running.value = v }

    fun addRun(profile: Profile) { _runs.value = _runs.value + (profile.id to ProfileRun(profile)) }

    fun removeRun(profileId: Long) { _runs.value = _runs.value - profileId }

    fun updateRun(profileId: Long, transform: (ProfileRun) -> ProfileRun) {
        val current = _runs.value[profileId] ?: return
        _runs.value = _runs.value + (profileId to transform(current))
    }

    fun setGpsFixAge(age: Long?) { _gpsFixAgeMs.value = age }
    fun setGpsUnavailable(v: Boolean) { _gpsUnavailable.value = v }
    fun setPhonePoint(p: GeoPoint?) { _phonePoint.value = p }
}