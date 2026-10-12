package com.flash.headway

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flash.headway.app.Alerts
import com.flash.headway.app.HeadwayViewModel
import com.flash.headway.app.ThemeMode
import com.flash.headway.ui.HeadwayApp
import com.flash.headway.ui.theme.HeadwayTheme
import com.flash.headway.ui.theme.LocalHw

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.createChannels(this)
        enableEdgeToEdge()
        setContent {
            val vm: HeadwayViewModel = viewModel()
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val dark = when (prefs?.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            HeadwayTheme(dark) {
                val loaded = prefs
                if (loaded != null) {
                    HeadwayApp(vm, loaded)
                } else {
                    Box(Modifier.fillMaxSize().background(LocalHw.current.bg))
                }
            }
        }
    }
}
