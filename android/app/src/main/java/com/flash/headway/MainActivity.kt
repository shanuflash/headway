package com.flash.headway

import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flash.headway.app.HeadwayViewModel
import com.flash.headway.app.ThemeMode
import com.flash.headway.ui.HeadwayApp
import com.flash.headway.ui.theme.HeadwayTheme
import com.flash.headway.ui.theme.LocalHw

class MainActivity : ComponentActivity() {
    /** Route a widget asked to open, consumed once the UI has shown it. */
    private val openRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reminders were removed: clear any countdown left over from an older version.
        getSystemService(android.app.NotificationManager::class.java)?.run {
            cancelAll()
            deleteNotificationChannel("reminders")
            deleteNotificationChannel("countdown")
        }
        openRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        enableEdgeToEdge()
        setContent {
            val vm: HeadwayViewModel = viewModel()
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val route by openRoute
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
                    HeadwayApp(vm, loaded, route, onRouteOpened = { openRoute.value = null })
                } else {
                    Box(Modifier.fillMaxSize().background(LocalHw.current.bg))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { openRoute.value = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
