package ca.trafficwatcher

import android.app.Application
import ca.trafficwatcher.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class TrafficApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // OSM Tile Usage Policy: identify ourselves on tile requests.
        Configuration.getInstance().userAgentValue = AdsB_USER_AGENT
        appScope.launch { container.profileRepository.seedIfEmpty() }
    }

    companion object {
        const val AdsB_USER_AGENT =
            "TrafficWatcher/1.0 (sideloaded situational-awareness app for recreational drone pilots; OSM tiles)"
    }
}