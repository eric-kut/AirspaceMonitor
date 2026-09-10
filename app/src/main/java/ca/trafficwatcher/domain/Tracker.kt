package ca.trafficwatcher.domain

/**
 * Per-aircraft alert state machine, keyed by ICAO hex.
 *
 * NEW     -> first sighting: emit an alert immediately.
 * INSIDE  -> still inside: stay silent until [cooldownMs] since the last
 *            emitted alert, then re-alert.
 * GONE    -> no sighting for [goneAfterMs] (default 120 s): drop the key so a
 *            later reappearance is treated as NEW again.
 *
 * When [alertsSuppressed] is true (global snooze) no alerts are emitted and
 * lastAlertMs is NOT advanced, so pending alerts still fire once the snooze
 * window ends.
 *
 * A tier escalation (WATCH -> WARNING) always alerts immediately, even inside
 * the cooldown, because the urgency changed.
 */
class AircraftTracker {

    data class Alert(val aircraft: MatchedAircraft, val isNew: Boolean)

    /** A tracked aircraft that stopped appearing; [lastTier] selects the "cleared" tone. */
    data class Departure(val hex: String, val lastTier: Tier)

    data class CycleResult(
        val alerts: List<Alert>,
        val gone: List<String>,
        val departures: List<Departure> = emptyList(),
        val trackedCount: Int,
        val zoneEmptied: Boolean,
    )

    private class Entry(
        var lastSeenMs: Long,
        var lastAlertMs: Long = 0L,
        var lastTier: Tier = Tier.WATCH,
        /** First cycle this aircraft was absent; null while seen. */
        var missingSinceMs: Long? = null,
        /** The one-time "cleared" chime has already been fired for this stay. */
        var clearReported: Boolean = false,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun process(
        sightings: List<MatchedAircraft>,
        nowMs: Long,
        cooldownMs: Long,
        goneAfterMs: Long = 120_000L,
        alertsSuppressed: Boolean = false,
        clearAfterMs: Long = 30_000L,
    ): CycleResult {
        val previousCount = entries.size
        val alerts = ArrayList<Alert>()
        val seenHexes = sightings.map { it.aircraft.hex }.toSet()

        for (m in sightings) {
            val hex = m.aircraft.hex
            val entry = entries[hex]
            if (entry == null) {
                entries[hex] = Entry(lastSeenMs = nowMs, lastTier = m.tier)
                if (!alertsSuppressed) {
                    entries[hex]!!.lastAlertMs = nowMs
                    alerts.add(Alert(m, isNew = true))
                }
            } else {
                entry.lastSeenMs = nowMs
                entry.missingSinceMs = null
                val escalated = m.tier == Tier.WARNING && entry.lastTier != Tier.WARNING
                entry.lastTier = m.tier
                if (!alertsSuppressed && (escalated || nowMs - entry.lastAlertMs >= cooldownMs)) {
                    entry.lastAlertMs = nowMs
                    alerts.add(Alert(m, isNew = false))
                }
            }
        }

        // Departure: absent for clearAfterMs -> fire the one-time "cleared"
        // chime with the tier it last had. The entry stays tracked until the
        // goneAfterMs window so a brief feed dropout does not reset alerts.
        val departures = ArrayList<Departure>()
        for (hex in entries.keys - seenHexes) {
            val e = entries.getValue(hex)
            if (e.missingSinceMs == null) e.missingSinceMs = nowMs
            if (!e.clearReported && nowMs - e.missingSinceMs!! >= clearAfterMs) {
                e.clearReported = true
                departures.add(Departure(hex, e.lastTier))
            }
        }

        // GONE: no sighting for goneAfterMs since the last one.
        val gone = ArrayList<String>()
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (nowMs - e.value.lastSeenMs >= goneAfterMs) {
                iter.remove()
                gone.add(e.key)
            }
        }

        return CycleResult(
            alerts = alerts,
            gone = gone,
            departures = departures,
            trackedCount = entries.size,
            zoneEmptied = previousCount > 0 && entries.isEmpty(),
        )
    }

    fun clear() = entries.clear()

    fun trackedHexes(): Set<String> = entries.keys.toSet()
}