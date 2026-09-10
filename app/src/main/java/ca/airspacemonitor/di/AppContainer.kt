package ca.airspacemonitor.di

import android.content.Context
import ca.airspacemonitor.data.EventRepository
import ca.airspacemonitor.data.SettingsStore
import ca.airspacemonitor.data.db.AppDatabase
import ca.airspacemonitor.data.ProfileRepository
import ca.airspacemonitor.data.network.AdsBClient
import ca.airspacemonitor.data.network.OpenMeteoClient
import ca.airspacemonitor.service.MonitorState
import ca.airspacemonitor.service.SnoozeStore
import ca.airspacemonitor.service.TestInjector

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