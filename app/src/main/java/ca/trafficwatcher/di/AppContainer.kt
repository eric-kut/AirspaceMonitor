package ca.trafficwatcher.di

import android.content.Context
import ca.trafficwatcher.data.EventRepository
import ca.trafficwatcher.data.SettingsStore
import ca.trafficwatcher.data.db.AppDatabase
import ca.trafficwatcher.data.ProfileRepository
import ca.trafficwatcher.data.network.AdsBClient
import ca.trafficwatcher.data.network.OpenMeteoClient
import ca.trafficwatcher.service.MonitorState
import ca.trafficwatcher.service.SnoozeStore
import ca.trafficwatcher.service.TestInjector

/**
 * Hand-rolled dependency container (no DI framework — the object graph is tiny
 * and fully under our control, which also keeps the app Google-component-free).
 */
class AppContainer(context: Context) {

    val database: AppDatabase = AppDatabase.build(context)
    val profileRepository = ProfileRepository(database.profileDao())
    val eventRepository = EventRepository(database.eventDao())
    val settingsStore = SettingsStore(context)
    val adsbClient = AdsBClient(hostsProvider = { settingsStore.current().hosts })
    val openMeteoClient = OpenMeteoClient()
    val monitorState = MonitorState()
    val testInjector = TestInjector()
    val snoozeStore = SnoozeStore(context)
}