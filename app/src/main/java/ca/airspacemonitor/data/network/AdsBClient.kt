package ca.airspacemonitor.data.network

import ca.airspacemonitor.domain.Aircraft
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for tar1090/readsb v2-compatible community ADS-B hosts, with
 * ordered failover and exponential backoff coordination.
 *
 * Backoff policy: the caller (poll loop) widens its delay on each failure;
 * this class rotates to the next host on every failed request, so one dead
 * aggregator costs a single poll instead of a stretch of dead polls.
 * A hard 1 request/second cap is enforced regardless of configured interval.
 */
class AdsBClient(private val hostsProvider: suspend () -> List<String>) {

    companion object {
        const val USER_AGENT = "AirspaceMonitor/1.0 (recreational drone airspace awareness app)"
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val MAX_CONSECUTIVE_FAILURES = 1
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var hostIndex = 0
    private var consecutiveFailures = 0
    private var lastRequestAtMs = 0L

    data class FetchOutcome(
        val aircraft: List<Aircraft>,
        val hostUsed: String?,
        /** Human-readable failure reason, null on success. */
        val error: String?,
    )

    /** GET {base}/point/{lat}/{lon}/{radius_nm} */
    suspend fun queryPoint(lat: Double, lon: Double, radiusNm: Double): FetchOutcome {
        val hosts = hostsProvider()
        if (hosts.isEmpty()) return FetchOutcome(emptyList(), null, "no API hosts configured")
        val host = hosts[Math.floorMod(hostIndex, hosts.size)]
        val url = "%s/point/%.5f/%.5f/%.3f".format(host, lat, lon, radiusNm)

        rateCap()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("empty response body")
                val parsed = json.decodeFromString<V2Response>(body)
                consecutiveFailures = 0
                FetchOutcome(parsed.ac.orEmpty().map { it.toDomain() }, host, null)
            }
        } catch (e: Exception) {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                hostIndex = (hostIndex + 1) % hosts.size
                consecutiveFailures = 0
            }
            FetchOutcome(emptyList(), null, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun rateCap() {
        val since = System.currentTimeMillis() - lastRequestAtMs
        if (lastRequestAtMs > 0 && since in 0 until MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - since)
        }
        lastRequestAtMs = System.currentTimeMillis()
    }

    fun currentHostIndex(): Int = hostIndex
}

fun AircraftDto.toDomain(): Aircraft = Aircraft(
    hex = hex.trim().lowercase(),
    callsign = flight?.trim()?.takeIf { it.isNotEmpty() },
    registration = r?.trim()?.takeIf { it.isNotEmpty() },
    type = t?.trim()?.takeIf { it.isNotEmpty() },
    lat = lat,
    lon = lon,
    altBaroFt = alt_baro,
    altGeomFt = alt_geom,
    groundSpeedKt = gs,
    trackDeg = track,
    seenSec = seen,
    seenPosSec = seen_pos,
    dbFlags = dbFlags,
)