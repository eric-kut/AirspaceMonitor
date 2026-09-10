package ca.trafficwatcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/** Global "suppress alert notifications for N minutes" store (action button on alerts). */
class SnoozeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("snooze", Context.MODE_PRIVATE)

    fun snoozeUntil(): Long = prefs.getLong(KEY, 0L)

    fun isSnoozed(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < snoozeUntil()

    /** Returns the new snooze-until timestamp. */
    fun snoozeForMinutes(minutes: Int): Long {
        val until = System.currentTimeMillis() + minutes * 60_000L
        prefs.edit().putLong(KEY, until).apply()
        return until
    }

    companion object {
        private const val KEY = "snooze_until_ms"
        const val DEFAULT_MINUTES = 5
    }
}

/** Handles the notification "Snooze" action. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val container = (app as ca.trafficwatcher.TrafficApp).container
        container.snoozeStore.snoozeForMinutes(SnoozeStore.DEFAULT_MINUTES)
    }
}