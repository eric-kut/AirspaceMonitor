package ca.trafficwatcher.data

import ca.trafficwatcher.data.db.EventDao
import ca.trafficwatcher.data.db.EventEntity
import ca.trafficwatcher.domain.EventEntityLite
import ca.trafficwatcher.domain.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class EventRepository(private val dao: EventDao) {

    val events: Flow<List<EventEntity>> = dao.observeAll()

    /** Domain-lite view for the UI / CSV. */
    val lite: Flow<List<EventEntityLite>> = events.map { list -> list.map { it.toLite() } }

    suspend fun record(events: List<EventEntity>) = dao.insertAll(events)

    suspend fun all(): List<EventEntityLite> = dao.getAll().map { it.toLite() }

    suspend fun pruneOlderThanDays(days: Int, nowMs: Long = System.currentTimeMillis()) {
        dao.deleteOlderThan(nowMs - days * 24L * 60L * 60L * 1000L)
    }

    suspend fun clear() = dao.clear()
}

fun EventEntity.toLite(): EventEntityLite = EventEntityLite(
    tsMs = tsMs,
    profileId = profileId,
    profileName = profileName,
    hex = hex,
    callsign = callsign,
    eventType = eventType,
    tier = tier.toTier(),
    lat = lat,
    lon = lon,
    altMslFt = altMslFt,
    aglFt = aglFt,
    distanceKm = distanceKm,
)

private fun String?.toTier(): Tier = runCatching { Tier.valueOf(this ?: "") }.getOrNull() ?: Tier.WATCH