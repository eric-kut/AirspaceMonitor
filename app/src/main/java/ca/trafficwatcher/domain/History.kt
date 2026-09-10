package ca.trafficwatcher.domain

/**
 * Pure helpers to turn recorded events into episodes (enter -> exit) and CSV.
 */
object History {

    const val EVENT_ENTER = "ENTER"
    const val EVENT_EXIT = "EXIT"
    const val EVENT_POSITION = "POSITION"

    data class Episode(
        val profileId: Long,
        val profileName: String,
        val hex: String,
        val callsign: String?,
        val enterMs: Long,
        /** null while the aircraft is still inside. */
        val exitMs: Long?,
        /** Tier at entry; WARNING when the aircraft escalated at any point. */
        val maxTier: Tier,
        /** Sighting positions recorded while inside, in chronological order. */
        val points: List<EventPoint>,
    )

    data class EventPoint(
        val tsMs: Long,
        val lat: Double?,
        val lon: Double?,
        val altMslFt: Double?,
        val aglFt: Double?,
        val distanceKm: Double?,
        val tier: Tier,
    )

    /** Groups a chronologically-ordered event list into episodes keyed by (profileId, hex). */
    fun episodes(events: List<EventEntityLite>): List<Episode> {
        data class Open(var episode: Episode, val points: MutableList<EventPoint>)

        val open = LinkedHashMap<Pair<Long, String>, Open>()
        val closed = ArrayList<Episode>()

        for (e in events.sortedBy { it.tsMs }) {
            val key = e.profileId to e.hex
            when (e.eventType) {
                EVENT_ENTER -> {
                    // Defensive: close any dangling episode for the same aircraft+profile.
                    open.remove(key)?.let { o ->
                        o.episode = o.episode.copy(points = o.points.toList())
                        closed.add(o.episode)
                    }
                    val point = EventPoint(e.tsMs, e.lat, e.lon, e.altMslFt, e.aglFt, e.distanceKm, e.tier)
                    open[key] = Open(
                        Episode(
                            profileId = e.profileId,
                            profileName = e.profileName,
                            hex = e.hex,
                            callsign = e.callsign,
                            enterMs = e.tsMs,
                            exitMs = null,
                            maxTier = e.tier,
                            points = listOf(point),
                        ),
                        mutableListOf(point),
                    )
                }
                EVENT_POSITION -> {
                    val o = open[key] ?: continue
                    val point = EventPoint(e.tsMs, e.lat, e.lon, e.altMslFt, e.aglFt, e.distanceKm, e.tier)
                    o.points.add(point)
                    o.episode = o.episode.copy(maxTier = maxOf(o.episode.maxTier, e.tier))
                }
                EVENT_EXIT -> {
                    val o = open.remove(key) ?: continue
                    o.episode = o.episode.copy(exitMs = e.tsMs, points = o.points.toList())
                    closed.add(o.episode)
                }
            }
        }
        // Still-inside episodes stay open (exitMs = null).
        closed.addAll(open.values.map { it.episode.copy(points = it.points.toList()) })
        return closed.sortedByDescending { it.enterMs }
    }

    /** Full event log as CSV (one row per event, ISO-8601 timestamps). */
    fun csv(events: List<EventEntityLite>): String {
        val header = "timestamp,profile_id,profile_name,hex,callsign,event,tier," +
            "lat,lon,alt_msl_ft,agl_ft,distance_km"
        val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
            .withZone(java.time.ZoneOffset.UTC)
        val rows = events.map { e ->
            listOf(
                fmt.format(java.time.Instant.ofEpochMilli(e.tsMs)),
                e.profileId.toString(),
                csvField(e.profileName),
                csvField(e.hex),
                csvField(e.callsign ?: ""),
                e.eventType,
                e.tier.name,
                e.lat?.toString() ?: "",
                e.lon?.toString() ?: "",
                e.altMslFt?.toString() ?: "",
                e.aglFt?.toString() ?: "",
                e.distanceKm?.toString() ?: "",
            ).joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\r\n") + "\r\n"
    }

    private fun csvField(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

/** Minimal event record so [History.episodes] stays testable without Android deps. */
data class EventEntityLite(
    val tsMs: Long,
    val profileId: Long,
    val profileName: String,
    val hex: String,
    val callsign: String?,
    val eventType: String,
    val tier: Tier,
    val lat: Double?,
    val lon: Double?,
    val altMslFt: Double?,
    val aglFt: Double?,
    val distanceKm: Double?,
)