package ca.airspacemonitor.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.data.AppSettings
import ca.airspacemonitor.data.DistanceUnit
import ca.airspacemonitor.data.DisplayFormats
import ca.airspacemonitor.data.VoiceRefMode
import ca.airspacemonitor.domain.GeoMath
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.MatchedAircraft
import ca.airspacemonitor.domain.Tier
import java.util.Locale

/**
 * Builds the spoken-announcement text for an alert. Pure Kotlin so it can be
 * unit tested without a TTS engine.
 */
object AlertUtterance {

    /** 16-point compass abbreviations mapped to words a TTS engine says clearly. */
    private val SPOKEN_BEARING = mapOf(
        "N" to "north",
        "NNE" to "north north east",
        "NE" to "north east",
        "ENE" to "east north east",
        "E" to "east",
        "ESE" to "east south east",
        "SE" to "south east",
        "SSE" to "south south east",
        "S" to "south",
        "SSW" to "south south west",
        "SW" to "south west",
        "WSW" to "west south west",
        "W" to "west",
        "WNW" to "west north west",
        "NW" to "north west",
        "NNW" to "north north west",
    )

    /**
     * Callsigns/registrations read better letter-by-letter ("N123AB" becomes
     * "N 1 2 3 A B"); a real-word callsign stays legible too.
     */
    private fun spacedIdent(raw: String): String = raw.map { it }.joinToString(" ") { it.toString() }

    /**
     * "Warning. Cessna 172, 1.5 kilometers south west from you, at 300
     * kilometers per hour, altitude 500 meters, descending at 5 meters per
     * second, bearing south." — clauses with no data are omitted.
     */
    fun build(
        matched: MatchedAircraft,
        settings: AppSettings,
        refPos: GeoPoint? = null,
    ): String {
        val ac = matched.aircraft
        // Prefer the spoken model name ("Boeing 737") over the callsign; only
        // callsigns/registrations/hex are read letter-by-letter.
        val ident = AircraftTypeNames.spoken(ac.type)
            ?: spacedIdent(ac.callsign ?: ac.registration ?: ac.hex.uppercase())
        val layer = when (matched.tier) {
            Tier.WARNING -> "Warning"
            Tier.WATCH -> "Watch layer"
        }
        val parts = StringBuilder("$layer. $ident")

        // Distance/direction from the configured reference point (phone, map
        // marker or zone centroid) — the most actionable for the pilot.
        if (refPos != null && ac.lat != null && ac.lon != null) {
            val pos = GeoPoint(ac.lat, ac.lon)
            SPOKEN_BEARING[GeoMath.compass16(GeoMath.bearingDeg(refPos, pos))]?.let { spoken ->
                val label = if (settings.voiceRefMode == VoiceRefMode.CENTROID) "the zone center" else "you"
                parts.append(
                    ", ${DisplayFormats.spokenDistance(GeoMath.haversineKm(refPos, pos), settings.distanceUnit)} $spoken from $label",
                )
            }
        }

        ac.groundSpeedKt?.let {
            parts.append(", at ${DisplayFormats.spokenSpeed(it, settings.distanceUnit)}")
        }

        matched.altMslFt?.let {
            parts.append(", altitude ${DisplayFormats.spokenAltitude(it, settings.altitudeUnit)}")
        }

        ac.verticalRateFpm?.let { fpm ->
            parts.append(", ${DisplayFormats.spokenVerticalRatePhrase(fpm, settings.altitudeUnit)}")
        }

        SPOKEN_BEARING[GeoMath.compass16(matched.bearingDeg)]?.let {
            parts.append(", bearing $it")
        }

        return parts.toString() + "."
    }

    /** "Clear. Aircraft C 1 7 2 has left the warning zone." — grouped per tier, plural form when several left at once. */
    fun buildCleared(tier: Tier, departures: List<ca.airspacemonitor.domain.AircraftTracker.Departure>): String {
        val zone = when (tier) {
            Tier.WARNING -> "warning zone"
            Tier.WATCH -> "watch layer"
        }
        if (departures.size == 1) {
            val d = departures.first()
            val name = AircraftTypeNames.spoken(d.type)
                ?: spacedIdent(d.ident ?: d.hex.uppercase())
            return "Clear. $name has left the $zone."
        }
        return "Clear. ${departures.size} aircraft have left the $zone."
    }
}

/**
 * Lazily initializes a TextToSpeech engine on the application context and
 * speaks alert announcements. Announcements arriving before the engine is
 * ready are queued and spoken as soon as init completes.
 */
class VoiceAnnouncer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    @Volatile private var ready = false
    private var unavailable = false
    private var tts: TextToSpeech? = null
    private var pendingUtterances = 0

    /** Invoked once when the TTS engine can't be initialized (usually: not installed). */
    var onUnavailable: (() -> Unit)? = null

    /** Utterances (text to id) held until the engine's async init completes. */
    private val pending = ArrayDeque<Pair<String, String>>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchdog: Runnable? = null

    private companion object {
        /** Upper bound for waiting on a TTS init callback that may never come. */
        const val INIT_TIMEOUT_MS = 12_000L
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            onUtteranceFinished()
        }

        override fun onError(utteranceId: String?) {
            onUtteranceFinished()
        }
    }

    private fun onUtteranceFinished() {
        synchronized(this) {
            pendingUtterances--
            if (pendingUtterances <= 0) {
                pendingUtterances = 0
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    private fun ensureTts(): Boolean {
        if (unavailable) return false
        if (tts != null) return true
        synchronized(this) {
            if (unavailable) return false
            if (tts != null) return true
            // The init callback can arrive SYNCHRONOUSLY from inside the
            // constructor (e.g. "no engine installed" dispatches ERROR before
            // TextToSpeech(...) returns), so it must not read the tts field.
            val created = arrayOf<TextToSpeech?>(null)
            var instance: TextToSpeech? = null
            runCatching {
                instance = TextToSpeech(appContext) { status -> onInit(created[0], status) }
            }
            created[0] = instance
            if (instance == null) {
                unavailable = true
                onUnavailable?.invoke()
                return false
            }
            tts = instance
            // Some devices never call back at all (broken/stubbed TTS service) —
            // don't wait forever before declaring the engine unavailable.
            watchdog = Runnable {
                synchronized(this) {
                    if (!ready && !unavailable && tts != null) {
                        markUnavailableLocked()
                    }
                }
            }.also { mainHandler.postDelayed(it, INIT_TIMEOUT_MS) }
        }
        return true
    }

    private fun onInit(engine: TextToSpeech?, status: Int) {
        synchronized(this) {
            watchdog?.let { mainHandler.removeCallbacks(it) }
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.language = Locale.US
                engine.setOnUtteranceProgressListener(progressListener)
                ready = true
                while (pending.isNotEmpty()) {
                    val (text, id) = pending.removeFirst()
                    speakLocked(engine, text, id)
                }
            } else {
                markUnavailableLocked()
            }
        }
    }

    private fun markUnavailableLocked() {
        if (unavailable) return
        unavailable = true
        pending.clear()
        pendingUtterances = 0
        audioManager.abandonAudioFocusRequest(focusRequest)
        onUnavailable?.invoke()
    }

    private fun speakLocked(engine: TextToSpeech, text: String, utteranceId: String) {
        val result = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) onUtteranceFinished()
    }

    fun announce(matched: MatchedAircraft, settings: AppSettings, refPos: GeoPoint? = null) {
        announceText(AlertUtterance.build(matched, settings, refPos), "${matched.aircraft.hex}:${System.currentTimeMillis()}")
    }

    fun announceText(text: String, utteranceId: String = "text:${System.currentTimeMillis()}") {
        if (!ensureTts()) return
        audioManager.requestAudioFocus(focusRequest)
        synchronized(this) {
            pendingUtterances++
            val engine = tts
            if (ready && engine != null) {
                speakLocked(engine, text, utteranceId)
            } else {
                pending.addLast(text to utteranceId)
            }
        }
    }

    fun shutdown() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        runCatching {
            tts?.stop()
            tts?.setOnUtteranceProgressListener(null)
            tts?.shutdown()
        }
        tts = null
        ready = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}