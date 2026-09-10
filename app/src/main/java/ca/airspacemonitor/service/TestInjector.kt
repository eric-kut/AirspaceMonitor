package ca.airspacemonitor.service

import ca.airspacemonitor.domain.Aircraft
import ca.airspacemonitor.domain.GeoMath
import ca.airspacemonitor.domain.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-mode synthetic aircraft. The monitoring poll loop includes it in every
 * cycle, so an end-to-end alert (including the notification) can be verified
 * with airplane mode / data off.
 */
class TestInjector {

    data class Spec(
        val hex: String = "test01",
        val callsign: String? = "TEST01",
        val type: String? = "C172",
        val altitudeFt: Double = 2500.0,
        /** Offset from the profile reference point. */
        val distanceKm: Double = 2.0,
        val bearingDeg: Double = 45.0,
        val groundSpeedKt: Double = 110.0,
        val trackDeg: Double = 225.0,
        val untilMs: Long = 0L,
    )

    private val _spec = MutableStateFlow<Spec?>(null)
    val spec: StateFlow<Spec?> = _spec.asStateFlow()

    fun inject(spec: Spec) {
        _spec.value = spec.copy(
            hex = spec.hex.trim().lowercase(),
            untilMs = if (spec.untilMs > 0) spec.untilMs else System.currentTimeMillis() + TEN_MINUTES_MS,
        )
    }

    fun clear() {
        _spec.value = null
    }

    /**
     * Build the synthetic aircraft for the current reference point, or null if
     * none is injected / the injection has expired.
     */
    fun aircraftFor(reference: GeoPoint, nowMs: Long): Aircraft? {
        val s = _spec.value ?: return null
        if (nowMs > s.untilMs) {
            _spec.value = null
            return null
        }
        val position = GeoMath.destinationPoint(reference, s.bearingDeg, s.distanceKm)
        return Aircraft(
            hex = s.hex,
            callsign = s.callsign?.trim()?.takeIf { it.isNotEmpty() },
            type = s.type?.trim()?.takeIf { it.isNotEmpty() },
            lat = position.lat,
            lon = position.lon,
            altBaroFt = s.altitudeFt,
            altGeomFt = null,
            groundSpeedKt = s.groundSpeedKt,
            trackDeg = s.trackDeg,
            mlat = false,
        )
    }

    companion object {
        const val TEN_MINUTES_MS = 10 * 60 * 1000L
    }
}