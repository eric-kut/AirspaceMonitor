package ca.airspacemonitor.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class DistanceUnit { KM, NM }
enum class AltitudeUnit { FT, M }

/** Where spoken-announcement distances ("…from you") are measured from. */
enum class VoiceRefMode {
    /** The phone's live GPS fix. */
    PHONE,
    /** A point the user marked on the map (e.g. their flying site). */
    MAP_POINT,
    /** The centroid of the monitored zone. */
    CENTROID,
}

data class AppSettings(
    val disclaimerAcknowledged: Boolean = false,
    val distanceUnit: DistanceUnit = DistanceUnit.KM,
    val altitudeUnit: AltitudeUnit = AltitudeUnit.FT,
    val allClearEnabled: Boolean = true,
    /** Record ENTER/EXIT episodes and per-loop positions for every event. */
    val historyEnabled: Boolean = true,
    /** Failover-ordered ADS-B API hosts (base URLs). */
    val hosts: List<String> = listOf(
        "https://api.adsb.lol/v2",
        "https://api.airplanes.live/v2",
        "https://api.adsb.fi/v2",
    ),
    /** OSM tile URL template with {z}/{x}/{y}; blank = osmdroid default MAPNIK. */
    val tileServerTemplate: String = "",
    val voiceRefMode: VoiceRefMode = VoiceRefMode.PHONE,
    /** Coordinates of the MAP_POINT voice reference. */
    val voiceRefLat: Double? = null,
    val voiceRefLon: Double? = null,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val DISCLAIMER = booleanPreferencesKey("disclaimer_acknowledged")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val ALTITUDE_UNIT = stringPreferencesKey("altitude_unit")
        val ALL_CLEAR = booleanPreferencesKey("all_clear_enabled")
        val HISTORY = booleanPreferencesKey("history_enabled")
        val HOSTS = stringPreferencesKey("api_hosts_json")
        val TILE_TEMPLATE = stringPreferencesKey("tile_server_template")
        val VOICE_REF_MODE = stringPreferencesKey("voice_ref_mode")
        val VOICE_REF_LAT = stringPreferencesKey("voice_ref_lat")
        val VOICE_REF_LON = stringPreferencesKey("voice_ref_lon")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            disclaimerAcknowledged = p[Keys.DISCLAIMER] ?: false,
            distanceUnit = (p[Keys.DISTANCE_UNIT])?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() } ?: DistanceUnit.KM,
            altitudeUnit = (p[Keys.ALTITUDE_UNIT])?.let { runCatching { AltitudeUnit.valueOf(it) }.getOrNull() } ?: AltitudeUnit.FT,
            allClearEnabled = p[Keys.ALL_CLEAR] ?: true,
            historyEnabled = p[Keys.HISTORY] ?: true,
            hosts = p[Keys.HOSTS]?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            }?.takeIf { it.isNotEmpty() } ?: AppSettings().hosts,
            tileServerTemplate = p[Keys.TILE_TEMPLATE] ?: "",
            voiceRefMode = (p[Keys.VOICE_REF_MODE])
                ?.let { runCatching { VoiceRefMode.valueOf(it) }.getOrNull() }
                ?: VoiceRefMode.PHONE,
            voiceRefLat = p[Keys.VOICE_REF_LAT]?.toDoubleOrNull(),
            voiceRefLon = p[Keys.VOICE_REF_LON]?.toDoubleOrNull(),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDisclaimerAcknowledged(value: Boolean) = edit { it[Keys.DISCLAIMER] = value }

    suspend fun setDistanceUnit(value: DistanceUnit) = edit { it[Keys.DISTANCE_UNIT] = value.name }

    suspend fun setAltitudeUnit(value: AltitudeUnit) = edit { it[Keys.ALTITUDE_UNIT] = value.name }

    suspend fun setAllClearEnabled(value: Boolean) = edit { it[Keys.ALL_CLEAR] = value }

    suspend fun setHistoryEnabled(value: Boolean) = edit { it[Keys.HISTORY] = value }

    suspend fun setHosts(hosts: List<String>) = edit { it[Keys.HOSTS] = json.encodeToString(hosts) }

    suspend fun setTileServerTemplate(template: String) = edit { it[Keys.TILE_TEMPLATE] = template }

    suspend fun setVoiceRefMode(value: VoiceRefMode) = edit { it[Keys.VOICE_REF_MODE] = value.name }

    suspend fun setVoiceRefPoint(lat: Double, lon: Double) = edit {
        it[Keys.VOICE_REF_LAT] = lat.toString()
        it[Keys.VOICE_REF_LON] = lon.toString()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}