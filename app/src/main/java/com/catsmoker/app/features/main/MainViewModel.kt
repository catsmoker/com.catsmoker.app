package com.catsmoker.app.features.main

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.catsmoker.app.features.main.engine.MetricsEngine
import com.catsmoker.app.system.ads.AdManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metricsEngine: MetricsEngine,
    private val adManager: AdManager
) : ViewModel() {

    val metricsState = metricsEngine.state
    val fpsHistory = metricsEngine.fpsHistory
    val cpuHistory = metricsEngine.cpuHistory
    val ramHistory = metricsEngine.ramHistory
    val tempHistory = metricsEngine.tempHistory
    val pingHistory = metricsEngine.pingHistory
    
    private val _adsEnabled = MutableStateFlow(adManager.isEnabled())
    val adsEnabled: StateFlow<Boolean> = _adsEnabled.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "ads_enabled") {
            _adsEnabled.update { adManager.isEnabled() }
        }
    }

    init {
        metricsEngine.start()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onCleared() {
        metricsEngine.stop()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
}
