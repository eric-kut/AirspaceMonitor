package ca.airspacemonitor

import ca.airspacemonitor.domain.Aircraft
import ca.airspacemonitor.domain.AircraftTracker
import ca.airspacemonitor.domain.MatchedAircraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {

    private fun matched(hex: String) = MatchedAircraft(
        aircraft = Aircraft(hex = hex),
        distanceKm = 1.0,
        bearingDeg = 90.0,
        bearingCompass = "E",
        altMslFt = 1000.0,
        aglFt = null,
    )

    private val cooldownMs = 2 * 60_000L

    @Test
    fun `first sighting is NEW and alerts immediately`() {
        val tracker = AircraftTracker()
        val now = 1_000_000L
        val r = tracker.process(listOf(matched("abc123")), now, cooldownMs)
        assertEquals(1, r.alerts.size)
        assertTrue(r.alerts.first().isNew)
        assertEquals("abc123", r.alerts.first().aircraft.aircraft.hex)
        assertEquals(1, r.trackedCount)
        assertFalse(r.zoneEmptied)
    }

    @Test
    fun `no re-alert while inside and cooldown has not elapsed`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        val r = tracker.process(listOf(matched("abc123")), 1_000_000L + 60_000L, cooldownMs)
        assertEquals(0, r.alerts.size)
        assertEquals(1, r.trackedCount)
        assertFalse(r.zoneEmptied)
    }

    @Test
    fun `re-alert after cooldown elapses`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        val r = tracker.process(listOf(matched("abc123")), 1_000_000L + cooldownMs, cooldownMs)
        assertEquals(1, r.alerts.size)
        assertFalse(r.alerts.first().isNew)
        assertEquals(1, r.trackedCount)
    }

    @Test
    fun `aircraft goes silent after gone window`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        val r = tracker.process(emptyList(), 1_000_000L + 130_000L, cooldownMs)
        assertEquals(0, r.alerts.size)
        assertEquals(listOf("abc123"), r.gone)
        assertEquals(0, r.trackedCount)
        assertTrue(r.zoneEmptied)
    }

    @Test
    fun `aircraft not removed before gone window`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        val r = tracker.process(emptyList(), 1_000_000L + 60_000L, cooldownMs)
        assertTrue(r.gone.isEmpty())
        assertEquals(1, r.trackedCount)
        assertFalse(r.zoneEmptied)
    }

    @Test
    fun `reappearance before gone window keeps tracking without NEW alert`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        tracker.process(emptyList(), 1_000_000L + 60_000L, cooldownMs)
        val r = tracker.process(listOf(matched("abc123")), 1_000_000L + 100_000L, cooldownMs)
        assertEquals(0, r.alerts.size)
        assertEquals(1, r.trackedCount)
    }

    @Test
    fun `gone aircraft that reappears is NEW again`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        tracker.process(emptyList(), 1_000_000L + 130_000L, cooldownMs)
        val r = tracker.process(listOf(matched("abc123")), 1_000_000L + 200_000L, cooldownMs)
        assertEquals(1, r.alerts.size)
        assertTrue(r.alerts.first().isNew)
    }

    @Test
    fun `snooze suppresses alerts but they fire once snooze ends`() {
        val tracker = AircraftTracker()
        val r1 = tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs, alertsSuppressed = true)
        assertEquals(0, r1.alerts.size)
        val r2 = tracker.process(listOf(matched("abc123")), 1_000_000L + 10_000L, cooldownMs)
        assertEquals(1, r2.alerts.size)
        assertEquals("abc123", r2.alerts.first().aircraft.aircraft.hex)
    }

    @Test
    fun `snooze does not advance last alert time for re-alerts`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs) // alert at t=1M
        val suppressed = tracker.process(listOf(matched("abc123")), 1_000_000L + cooldownMs + 1, cooldownMs, alertsSuppressed = true)
        assertEquals(0, suppressed.alerts.size)
        // Still snoozed for a while; when it lifts, the pending re-alert fires.
        val after = tracker.process(listOf(matched("abc123")), 1_000_000L + cooldownMs + 5_000, cooldownMs)
        assertEquals(1, after.alerts.size)
        assertFalse(after.alerts.first().isNew)
    }

    @Test
    fun `multiple aircraft are tracked independently`() {
        val tracker = AircraftTracker()
        val t0 = 1_000_000L
        val r = tracker.process(listOf(matched("a1"), matched("a2"), matched("a3")), t0, cooldownMs)
        assertEquals(3, r.alerts.size)
        assertEquals(3, r.trackedCount)
        val r2 = tracker.process(listOf(matched("a1"), matched("a3")), t0 + 130_000L, cooldownMs)
        assertEquals(listOf("a2"), r2.gone)
        assertEquals(2, r2.trackedCount)
        assertTrue(r2.zoneEmptied.not())
    }

    @Test
    fun `watch to warning escalation alerts immediately even inside cooldown`() {
        val tracker = AircraftTracker()
        val t0 = 1_000_000L
        val watch = matched("abc123")
        val warning = watch.copy(tier = ca.airspacemonitor.domain.Tier.WARNING)
        tracker.process(listOf(watch), t0, cooldownMs)
        // Escalation 1 s later: must alert even though cooldown is 2 min.
        val r = tracker.process(listOf(warning), t0 + 1_000L, cooldownMs)
        assertEquals(1, r.alerts.size)
        assertEquals(ca.airspacemonitor.domain.Tier.WARNING, r.alerts.first().aircraft.tier)
        // And it respects the cooldown again afterwards.
        val r2 = tracker.process(listOf(warning), t0 + 60_000L, cooldownMs)
        assertEquals(0, r2.alerts.size)
    }

    @Test
    fun `clear resets the tracker`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        tracker.clear()
        val r = tracker.process(listOf(matched("abc123")), 1_100_000L, cooldownMs)
        assertTrue(r.alerts.first().isNew)
    }

    // ---- Departure ("cleared") chimes ------------------------------------

    @Test
    fun `departure fires once after clearAfterMs with last tier`() {
        val tracker = AircraftTracker()
        val warning = matched("abc123").copy(tier = ca.airspacemonitor.domain.Tier.WARNING)
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        tracker.process(listOf(warning), 1_000_000L + 60_000L, cooldownMs)
        // Absent on the next cycle: missingSince is set, but the chime waits clearAfterMs.
        val r1 = tracker.process(emptyList(), 1_000_000L + 70_000L, cooldownMs)
        assertTrue(r1.departures.isEmpty())
        val r2 = tracker.process(emptyList(), 1_000_000L + 100_001L, cooldownMs)
        assertEquals(1, r2.departures.size)
        assertEquals("abc123", r2.departures.first().hex)
        assertEquals(ca.airspacemonitor.domain.Tier.WARNING, r2.departures.first().lastTier)
        // One-time per stay.
        val r3 = tracker.process(emptyList(), 1_000_000L + 110_000L, cooldownMs)
        assertTrue(r3.departures.isEmpty())
    }

    @Test
    fun `departure does not remove the entry before the gone window`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        tracker.process(emptyList(), 1_000_000L + 60_000L, cooldownMs) // missing clock starts
        val r = tracker.process(emptyList(), 1_000_000L + 90_001L, cooldownMs) // 30 s later
        assertEquals(1, r.departures.size)
        assertEquals(1, r.trackedCount)
        assertTrue(r.gone.isEmpty())
    }

    @Test
    fun `reappearance after the chime does not NEW-alert while still tracked`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        tracker.process(emptyList(), 1_000_000L + 60_000L, cooldownMs) // cleared chime fires
        val r = tracker.process(listOf(matched("abc123")), 1_000_000L + 100_000L, cooldownMs)
        assertEquals(0, r.alerts.size)
        assertEquals(1, r.trackedCount)
    }

    @Test
    fun `sighting again resets the missing clock`() {
        val tracker = AircraftTracker()
        tracker.process(listOf(matched("abc123")), 1_000_000L, cooldownMs)
        tracker.process(emptyList(), 1_000_000L + 20_000L, cooldownMs) // missing starts
        tracker.process(listOf(matched("abc123")), 1_000_000L + 40_000L, cooldownMs) // seen again
        val r = tracker.process(emptyList(), 1_000_000L + 50_000L, cooldownMs) // 10 s < clearAfterMs
        assertTrue(r.departures.isEmpty())
    }
}