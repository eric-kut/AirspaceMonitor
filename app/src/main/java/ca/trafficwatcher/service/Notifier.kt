package ca.trafficwatcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import ca.trafficwatcher.R
import ca.trafficwatcher.data.AltitudeUnit
import ca.trafficwatcher.data.DistanceUnit
import ca.trafficwatcher.domain.AircraftTracker
import ca.trafficwatcher.domain.CeilingRef
import ca.trafficwatcher.domain.MatchedAircraft
import ca.trafficwatcher.domain.Profile
import ca.trafficwatcher.domain.Tier
import ca.trafficwatcher.domain.Units

/** Builds all notifications and channels. Everything is local — no GMS/FCM anywhere. */
class Notifier(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_STATUS = "monitoring_status"
        const val CHANNEL_CLEAR_WATCH = "clear_watch"
        const val CHANNEL_CLEAR_WARNING = "clear_warning"
        const val WATCH_CHANNEL_PREFIX = "alerts_watch_"
        const val WARNING_CHANNEL_PREFIX = "alerts_warning_"

        const val NOTIF_ID_STATUS = 1
        private const val NOTIF_ID_ALERT_BASE = 1000
        private const val NOTIF_ID_CLEAR_BASE = 500

        const val ACTION_SNOOZE = "ca.trafficwatcher.action.SNOOZE"
        const val ACTION_STOP_MONITORING = "ca.trafficwatcher.action.STOP_MONITORING"

        fun watchChannel(profileId: Long) = "$WATCH_CHANNEL_PREFIX$profileId"
        fun warningChannel(profileId: Long) = "$WARNING_CHANNEL_PREFIX$profileId"

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

    /** Cheerful departure chimes — one channel per cleared tier. */
    fun ensureClearChannels() {
        ensureChannel(
            CHANNEL_CLEAR_WARNING,
            "Cleared — warning zone",
            NotificationManager.IMPORTANCE_DEFAULT,
            "An aircraft left the warning zone",
            soundEnabled = true,
            sound = android.net.Uri.parse("android.resource://${context.packageName}/raw/clear_warning"),
            vibration = true,
        )
        ensureChannel(
            CHANNEL_CLEAR_WATCH,
            "Cleared — watch layer",
            NotificationManager.IMPORTANCE_DEFAULT,
            "An aircraft left the watch layer",
            soundEnabled = true,
            sound = android.net.Uri.parse("android.resource://${context.packageName}/raw/clear_watch"),
            vibration = true,
        )
    }

    /** Watch channel: normal-priority notification sound. Warning channel: urgent alarm sound. */
    fun ensureAlertChannels(profile: Profile) {
        ensureChannel(
            watchChannel(profile.id),
            "Alerts — ${profile.name}",
            NotificationManager.IMPORTANCE_DEFAULT,
            "Aircraft entering ${profile.name} (watch layer)",
            profile.soundEnabled,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            profile.vibrationEnabled,
        )
        ensureChannel(
            warningChannel(profile.id),
            "WARNING — ${profile.name}",
            NotificationManager.IMPORTANCE_HIGH,
            "Aircraft in the warning volume of ${profile.name}",
            profile.soundEnabled,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            true,
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
        if (notificationManager.getNotificationChannel(id) != null) return
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
                context.getString(if (matched.tier == ca.trafficwatcher.domain.Tier.WARNING) R.string.tier_warning else R.string.tier_watch),
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
        ac.trackDeg?.let {
            detail.appendLine(context.getString(R.string.alert_line_track, it, ca.trafficwatcher.domain.GeoMath.compass8(it)))
        }
        // How stale the aggregator's data already was when we polled it.
        (ac.seenPosSec ?: ac.seenSec)?.let {
            detail.appendLine(context.getString(R.string.alert_line_age, Math.round(it).toInt()))
        }
        detail.append(context.getString(R.string.alert_line_source, if (ac.mlat) "MLAT" else "ADS-B"))

        val builder = baseBuilder(
            if (matched.tier == ca.trafficwatcher.domain.Tier.WARNING) warningChannel(profile.id)
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
        ensureClearChannels()
        for ((tier, group) in departures.groupBy { it.lastTier }) {
            val text = when (tier) {
                Tier.WARNING -> context.getString(R.string.cleared_warning_text, group.size)
                Tier.WATCH -> context.getString(R.string.cleared_watch_text, group.size)
            }
            val notification = baseBuilder(if (tier == Tier.WARNING) CHANNEL_CLEAR_WARNING else CHANNEL_CLEAR_WATCH)
                .setContentTitle(context.getString(R.string.cleared_notification_title, profile.name))
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(clearId(profile.id, tier), notification)
        }
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

    private fun formatDistance(km: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> "%.1f km".format(km)
        DistanceUnit.NM -> "%.1f nm".format(Units.kmToNm(km))
    }

    private fun formatAltitude(ft: Double, unit: AltitudeUnit): String = when (unit) {
        AltitudeUnit.FT -> "%,d ft".format(Math.round(ft).toLong())
        AltitudeUnit.M -> "%,d m".format(Math.round(Units.feetToMeters(ft)).toLong())
    }

    private fun formatSpeed(knots: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> "%.0f km/h".format(Units.knotsToKmh(knots))
        DistanceUnit.NM -> "%.0f kt".format(knots)
    }
}