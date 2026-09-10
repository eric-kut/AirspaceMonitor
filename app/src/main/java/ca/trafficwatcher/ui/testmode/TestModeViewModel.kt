package ca.trafficwatcher.ui.testmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.trafficwatcher.di.AppContainer
import ca.trafficwatcher.service.TestInjector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TestModeViewModel(private val container: AppContainer) : ViewModel() {

    val running: StateFlow<Boolean> =
        container.monitorState.running
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val injectedSpec: StateFlow<TestInjector.Spec?> =
        container.testInjector.spec
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun inject(
        hex: String,
        callsign: String,
        altitudeFt: Double,
        distanceKm: Double,
        bearingDeg: Double,
    ) {
        container.testInjector.inject(
            TestInjector.Spec(
                hex = hex.ifBlank { "test01" },
                callsign = callsign.ifBlank { "TEST01" },
                altitudeFt = altitudeFt,
                distanceKm = distanceKm,
                bearingDeg = bearingDeg,
            ),
        )
    }

    fun clear() = viewModelScope.launch { container.testInjector.clear() }
}