package ca.airspacemonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import ca.airspacemonitor.R
import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.data.DistanceUnit
import ca.airspacemonitor.domain.AircraftTracker
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.MatchedAircraft
import ca.airspacemonitor.domain.Profile
import ca.airspacemonitor.domain.Tier

/** Builds all notifications and channels. Everything is local — no GMS/FCM anywhere. */
class Notifier(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_STATUS = "monitoring_status"
        const val CHANNEL_VOICE_STATUS = "voice_status"
        private const val LEGACY_CHANNEL_CLEAR_WATCH = "clear_watch"
        private const val LEGACY_CHANNEL_CLEAR_WARNING = "clear_warning"
        const val WATCH_CHANNEL_PREFIX = "alerts_watch_"
        const val WARNING_CHANNEL_PREFIX = "alerts_warning_"
        const val CLEAR_WATCH_CHANNEL_PREFIX = "clears_watch_"
        const val CLEAR_WARNING_CHANNEL_PREFIX = "clears_warning_"

        const val NOTIF_ID_STATUS = 1
        const val NOTIF_ID_VOICE_STATUS = 900
        private const val NOTIF_ID_ALERT_BASE = 1000
        private const val NOTIF_ID_CLEAR_BASE = 500

        const val ACTION_SNOOZE = "ca.airspacemonitor.action.SNOOZE"
        const val ACTION_STOP_MONITORING = "ca.airspacemonitor.action.STOP_MONITORING"

        fun watchChannel(profileId: Long) = "$WATCH_CHANNEL_PREFIX$profileId"
        fun warningChannel(profileId: Long) = "$WARNING_CHANNEL_PREFIX$profileId"
        fun clearWatchChannel(profileId: Long) = "$CLEAR_WATCH_CHANNEL_PREFIX$profileId"
        fun clearWarningChannel(profileId: Long) = "$CLEAR_WARNING_CHANNEL_PREFIX$profileId"

        /** Unique per (profile, aircraft); a tier escalation updates the same notification. */
        fun alertId(profileId: Long, hex: String): Int =
            NOTIF_ID_ALERT_BASE + (("$profileId:$hex").hashCode() and 0x7FFFFF)

        /** One notification slot per (profile, tier) so repeated clearances update, not stack. */
        fun clearId(profileId: Long, tier: Tier): Int =
            NOTIF_ID_CLEAR_BASE + (profileId.toString().hashCode() and 0xFF) * 2 + tier.ordinal

        fun formatTime(context: Context, epochMs: Long): String =
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(epochMs))
    }

    fun ensureBaseChannels() {
        if (notificationManager.getNotificationChannel(CHANNEL_STATUS) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Monitoring status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent indicator while a zone is being monitored"
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * Cheerful departure chimes — one channel per (profile, cleared tier) so the
     * chime follows that profile's sound/vibration toggles. When a tier's spoken
     * voice is enabled its chime channel is created soundless, mirroring the
     * alert channels (the voice replaces the tone). The pre-per-profile global
     * channels are removed so they can't chime on their own.
     */
    fun ensureClearChannels(profile: Profile) {
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_CLEAR_WARNING)
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_CLEAR_WATCH)
        ensureChannel(
            clearWarningChannel(profile.id),
            "Cleared — ${profile.name} warning zone",
            NotificationManager.IMPORTANCE_DEFAULT,
            "An aircraft left the warning zone of ${profile.name}",
            soundEnabled = profile.soundEnabled && !profile.warningVoiceEnabled,
            sound = android.net.Uri.parse("android.resource://${context.packageName}/raw/clear_warning"),
            vibration = profile.vibrationEnabled,
        )
        ensureChannel(
            clearWatchChannel(profile.id),
            "Cleared — ${profile.name} watch layer",
            NotificationManager.IMPORTANCE_DEFAULT,
            "An aircraft left the watch layer of ${profile.name}",
            soundEnabled = profile.soundEnabled && !profile.watchVoiceEnabled,
            sound = android.net.Uri.parse("android.resource://${context.packageName}/raw/clear_watch"),
            vibration = profile.vibrationEnabled,
        )
    }

    /**
     * Watch channel: normal-priority notification sound. Warning channel: urgent
     * alarm sound. When voice announcements are enabled for a layer its channel
     * is created soundless — the synthesized voice replaces the tone.
     */
    fun ensureAlertChannels(profile: Profile) {
        ensureChannel(
            watchChannel(profile.id),
            "Alerts — ${profile.name}",
            NotificationManager.IMPORTANCE_DEFAULT,
            "Aircraft entering ${profile.name} (watch layer)",
            soundEnabled = profile.soundEnabled && !profile.watchVoiceEnabled,
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            vibration = profile.vibrationEnabled,
        )
        ensureChannel(
            warningChannel(profile.id),
            "WARNING — ${profile.name}",
            NotificationManager.IMPORTANCE_HIGH,
            "Aircraft in the warning volume of ${profile.name}",
            soundEnabled = profile.soundEnabled && !profile.warningVoiceEnabled,
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            vibration = profile.vibrationEnabled,
        )
    }

    private fun ensureChannel(
        id: String,
        name: String,
        importance: Int,
        description: String,
        soundEnabled: Boolean,
        sound: android.net.Uri?,
        vibration: Boolean,
    ) {
        val existing = notificationManager.getNotificationChannel(id)
        if (existing != null) {
            // Channels are immutable once created; only a delete + recreate applies
            // changed sound/vibration settings (voice toggle, sound/vibration flags).
            val existingHasSound = existing.sound != null
            val wantsSound = soundEnabled && sound != null
            if (existingHasSound == wantsSound && existing.shouldVibrate() == vibration) return
            notificationManager.deleteNotificationChannel(id)
        }
        notificationManager.createNotificationChannel(
            NotificationChannel(id, name, importance).apply {
                this.description = description
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (soundEnabled && sound != null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(
                            if (importance >= NotificationManager.IMPORTANCE_HIGH) {
                                android.media.AudioAttributes.USAGE_ALARM
                            } else {
                                android.media.AudioAttributes.USAGE_NOTIFICATION
                            },
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(sound, attrs)
                } else {
                    setSound(null, null)
                }
                enableVibration(vibration)
                vibrationPattern = if (vibration) longArrayOf(0, 250, 150, 250, 0, 250, 150, 250) else null
            },
        )
    }

    fun statusNotification(profileName: String, lastPollMs: Long?, trackedCount: Int): Notification {
        val text = context.getString(R.string.status_notification_text)
            .format(
                lastPollMs?.let { Notifier.formatTime(context, it) }
                    ?: context.getString(R.string.status_not_polled_yet),
                trackedCount,
            )
        return baseBuilder(CHANNEL_STATUS)
            .setContentTitle(context.getString(R.string.status_notification_title, profileName))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(stopIntent())
            .addAction(
                0,
                context.getString(R.string.action_stop),
                stopIntent(),
            )
            .build()
    }

    fun postStatus(profileName: String, lastPollMs: Long?, trackedCount: Int) {
        notificationManager.notify(NOTIF_ID_STATUS, statusNotification(profileName, lastPollMs, trackedCount))
    }

    fun alertNotification(
        matched: MatchedAircraft,
        profile: Profile,
        distanceUnit: DistanceUnit,
        altitudeUnit: AltitudeUnit,
    ): Notification {
        val ac = matched.aircraft
        val title = "${profile.name} · ${(ac.callsign ?: ac.hex.uppercase())}" +
            (ac.type?.let { " ($it)" } ?: "")

        val dist = formatDistance(matched.distanceKm, distanceUnit)
        val line1 = context.getString(R.string.alert_line_geo, dist, matched.bearingCompass)

        val detail = StringBuilder()
        detail.appendLine(context.getString(R.string.alert_line_area, profile.name))
        detail.appendLine(
            context.getString(
                R.string.alert_line_tier,
                context.getString(if (matched.tier == ca.airspacemonitor.domain.Tier.WARNING) R.string.tier_warning else R.string.tier_watch),
            ),
        )
        matched.altMslFt?.let {
            detail.appendLine(context.getString(R.string.alert_line_alt_msl, formatAltitude(it, altitudeUnit)))
        }
        matched.aglFt?.let {
            detail.appendLine(context.getString(R.string.alert_line_alt_agl, formatAltitude(it, altitudeUnit)))
        }
        ac.groundSpeedKt?.let {
            detail.appendLine(context.getString(R.string.alert_line_gs, formatSpeed(it, distanceUnit)))
        }
        ac.verticalRateFpm?.let { fpm ->
            detail.appendLine(
                context.getString(
                    R.string.alert_line_vs,
                    ca.airspacemonitor.data.DisplayFormats.verticalRatePhrase(fpm, altitudeUnit)
                        .replaceFirstChar { it.uppercase() },
                ),
            )
        }
        ac.trackDeg?.let {
            detail.appendLine(context.getString(R.string.alert_line_track, it, ca.airspacemonitor.domain.GeoMath.compass8(it)))
        }
        // How stale the aggregator's data already was when we polled it.
        (ac.seenPosSec ?: ac.seenSec)?.let {
            detail.appendLine(context.getString(R.string.alert_line_age, Math.round(it).toInt()))
        }
        detail.append(context.getString(R.string.alert_line_source, if (ac.mlat) "MLAT" else "ADS-B"))

        val builder = baseBuilder(
            if (matched.tier == ca.airspacemonitor.domain.Tier.WARNING) warningChannel(profile.id)
            else watchChannel(profile.id),
        )
            .setContentTitle(title)
            .setContentText(line1)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line1 + "\n" + detail.toString().trimEnd()))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .addAction(0, context.getString(R.string.action_map), viewIntent(
                "https://globe.adsbexchange.com/?icao=${ac.hex}",
            ))
            .addAction(
                0,
                context.getString(R.string.action_snooze),
                snoozeIntent(),
            )
        if (ac.callsign != null) {
            builder.addAction(0, context.getString(R.string.action_fr24), viewIntent(
                "https://www.flightradar24.com/${ac.callsign}",
            ))
        }
        return builder.build()
    }

    fun postAlert(
        matched: MatchedAircraft,
        profile: Profile,
        distanceUnit: DistanceUnit,
        altitudeUnit: AltitudeUnit,
    ) {
        ensureAlertChannels(profile)
        notificationManager.notify(alertId(profile.id, matched.aircraft.hex), alertNotification(matched, profile, distanceUnit, altitudeUnit))
    }

    fun cancelAlert(profileId: Long, hex: String) {
        notificationManager.cancel(alertId(profileId, hex))
    }

    /** One notification per tier with the cheerful chime of that tier's channel. */
    fun postCleared(profile: Profile, departures: List<AircraftTracker.Departure>) {
        ensureClearChannels(profile)
        for ((tier, group) in departures.groupBy { it.lastTier }) {
            val text = when (tier) {
                Tier.WARNING -> context.getString(R.string.cleared_warning_text, group.size)
                Tier.WATCH -> context.getString(R.string.cleared_watch_text, group.size)
            }
            val notification = baseBuilder(
                if (tier == Tier.WARNING) clearWarningChannel(profile.id) else clearWatchChannel(profile.id),
            )
                .setContentTitle(context.getString(R.string.cleared_notification_title, profile.name))
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(clearId(profile.id, tier), notification)
        }
    }

    /**
     * TTS init failed — almost always because no text-to-speech engine is
     * installed (e.g. GrapheneOS ships without one). Tell the user once instead
     * of failing silently; the next monitoring restart retries TTS.
     */
    fun postVoiceUnavailable() {
        ensureChannel(
            CHANNEL_VOICE_STATUS,
            "Voice announcements",
            NotificationManager.IMPORTANCE_DEFAULT,
            "Problems with spoken voice announcements",
            soundEnabled = true,
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            vibration = false,
        )
        val text = "No text-to-speech engine was found, so spoken alerts are off. " +
            "Install one (e.g. RHVoice or eSpeak NG from F-Droid) and enable it in " +
            "Android Settings > Text-to-speech output, then restart monitoring."
        notificationManager.notify(
            NOTIF_ID_VOICE_STATUS,
            baseBuilder(CHANNEL_VOICE_STATUS)
                .setContentTitle("Spoken voice unavailable")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun baseBuilder(channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_aircraft)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun viewIntent(url: String): PendingIntent = PendingIntent.getActivity(
        context,
        url.hashCode(),
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun snoozeIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        ACTION_SNOOZE.hashCode(),
        Intent(context, SnoozeReceiver::class.java).setAction(ACTION_SNOOZE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        context,
        ACTION_STOP_MONITORING.hashCode(),
        Intent(context, MonitoringService::class.java).setAction(ACTION_STOP_MONITORING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun formatDistance(km: Double, unit: DistanceUnit): String =
        ca.airspacemonitor.data.DisplayFormats.formatDistance(km, unit)

    private fun formatAltitude(ft: Double, unit: AltitudeUnit): String =
        ca.airspacemonitor.data.DisplayFormats.formatAltitude(ft, unit)

    private fun formatSpeed(knots: Double, unit: DistanceUnit): String =
        ca.airspacemonitor.data.DisplayFormats.formatSpeed(knots, unit)
}