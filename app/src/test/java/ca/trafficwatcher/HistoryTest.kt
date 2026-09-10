package ca.trafficwatcher

import ca.trafficwatcher.domain.EventEntityLite
import ca.trafficwatcher.domain.History
import ca.trafficwatcher.domain.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private fun event(
        tsMs: Long,
        hex: String,
        type: String,
        tier: Tier = Tier.WATCH,
        profileId: Long = 1L,
        profileName: String = "Profile $profileId",
        lat: Double? = 45.0,
        lon: Double? = -75.0,
    ) = EventEntityLite(
        tsMs = tsMs,
        profileId = profileId,
        profileName = profileName,
        hex = hex,
        callsign = "CS$hex",
        eventType = type,
        tier = tier,
        lat = lat,
        lon = lon,
        altMslFt = 1000.0,
        aglFt = null,
        distanceKm = 2.0,
    )

    @Test
    fun `enter and exit pair into one closed episode with points`() {
        val events = listOf(
            event(1_000L, "abc", History.EVENT_ENTER),
            event(2_000L, "abc", History.EVENT_POSITION, Tier.WATCH),
            event(3_000L, "abc", History.EVENT_POSITION, Tier.WARNING),
            event(4_000L, "abc", History.EVENT_EXIT),
        )
        val episodes = History.episodes(events)
        assertEquals(1, episodes.size)
        val e = episodes.first()
        assertEquals("abc", e.hex)
        assertEquals(1_000L, e.enterMs)
        assertEquals(4_000L, e.exitMs)
        assertEquals(Tier.WARNING, e.maxTier)
        assertEquals(3, e.points.size)
    }

    @Test
    fun `episode without exit stays open`() {
        val events = listOf(
            event(1_000L, "abc", History.EVENT_ENTER),
            event(2_000L, "abc", History.EVENT_POSITION),
        )
        val episodes = History.episodes(events)
        assertEquals(1, episodes.size)
        assertNull(episodes.first().exitMs)
    }

    @Test
    fun `profiles and aircraft are grouped independently`() {
        val events = listOf(
            event(1_000L, "abc", History.EVENT_ENTER, profileId = 1),
            event(1_500L, "abc", History.EVENT_ENTER, profileId = 2),
            event(2_000L, "def", History.EVENT_ENTER, profileId = 1),
            event(3_000L, "abc", History.EVENT_EXIT, profileId = 1),
        )
        val episodes = History.episodes(events)
        assertEquals(3, episodes.size)
        val closed = episodes.filter { it.exitMs != null }
        assertEquals(1, closed.size)
        assertEquals(1L, closed.first().profileId)
        assertEquals("abc", closed.first().hex)
    }

    @Test
    fun `re-enter after exit starts a new episode`() {
        val events = listOf(
            event(1_000L, "abc", History.EVENT_ENTER),
            event(2_000L, "abc", History.EVENT_EXIT),
            event(3_000L, "abc", History.EVENT_ENTER),
        )
        val episodes = History.episodes(events)
        assertEquals(2, episodes.size)
        assertEquals(1, episodes.count { it.exitMs != null }) // 1 closed + 1 open
    }

    @Test
    fun `episodes are sorted newest first`() {
        val events = listOf(
            event(1_000L, "a1", History.EVENT_ENTER),
            event(5_000L, "a2", History.EVENT_ENTER),
        )
        val episodes = History.episodes(events)
        assertTrue(episodes.first().enterMs > episodes.last().enterMs)
    }

    @Test
    fun `csv contains header and one row per event`() {
        val events = listOf(
            event(1_000L, "abc", History.EVENT_ENTER, Tier.WARNING, profileName = "My, profile"),
            event(2_000L, "abc", History.EVENT_EXIT),
        )
        val csv = History.csv(events)
        val lines = csv.trim().split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("timestamp,profile_id,profile_name,hex,callsign,event,tier"))
        assertTrue(lines[1].contains("ENTER"))
        assertTrue(lines[1].contains("WARNING"))
        assertTrue(lines[1].contains("\"My, profile\""))
        assertTrue(lines[2].contains("EXIT"))
    }
}