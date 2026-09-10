package ca.trafficwatcher.data.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * On-demand terrain elevation from Open-Meteo (~90 m resolution DEM).
 * Called only from the profile editor; never per aircraft (rate limits).
 */
class OpenMeteoClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Result in meters above mean sea level. */
    fun fetchElevationM(lat: Double, lon: Double): Result<Double> = try {
        val url = "https://api.open-meteo.com/v1/elevation?latitude=%.6f&longitude=%.6f".format(lat, lon)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AdsBClient.USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException("HTTP ${response.code}"))
            }
            val body = response.body?.string()
                ?: return Result.failure(IllegalStateException("empty response body"))
            val parsed = json.decodeFromString<ElevationResponse>(body)
            val elevation = parsed.elevation.firstOrNull()
                ?: return Result.failure(IllegalStateException("no elevation in response"))
            Result.success(elevation)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}