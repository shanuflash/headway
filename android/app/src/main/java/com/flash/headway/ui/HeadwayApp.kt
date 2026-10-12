package com.flash.headway.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flash.headway.app.Alerts
import com.flash.headway.app.HeadwayViewModel
import com.flash.headway.app.Prefs
import com.flash.headway.app.TripKey
import com.flash.headway.data.Timetable
import com.flash.headway.engine.Journey
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlinx.coroutines.delay

enum class Tab { NOW, TRIPS, LINES }

enum class Screen { TRIP, RIDE, SETTINGS }

private data class Dest(val key: String, val depth: Int)

@Composable
fun HeadwayApp(vm: HeadwayViewModel, prefs: Prefs) {
    val hw = LocalHw.current
    val context = LocalContext.current
    val state by vm.now.collectAsStateWithLifecycle()

    // One clock for every countdown, aligned to the second.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            value = System.currentTimeMillis()
        }
    }
    val minute = now / 60_000

    var tab by rememberSaveable { mutableStateOf(Tab.NOW) }
    val stack = remember { mutableStateListOf<Screen>() }
    var picker by remember { mutableStateOf<PickField?>(null) }
    var tripDep by remember { mutableStateOf<Long?>(null) }
    var openedRide by rememberSaveable { mutableStateOf(false) }

    // Reopen the ride screen when the app starts mid-trip.
    LaunchedEffect(Unit) {
        if (!openedRide && prefs.ride != null) {
            stack.add(Screen.RIDE)
            openedRide = true
        }
    }

    fun push(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun goNow() {
        stack.clear()
        tab = Tab.NOW
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun remind(j: Journey) {
        val on = prefs.reminderDep == j.dep
        if (!on) askNotifications()
        vm.toggleReminder(j)
        toast(if (on) "Reminder off" else "We'll remind you at ${clock(vm.reminderTime(j))}")
    }

    // Trip detail and Trips tab data, refreshed each minute.
    var options by remember { mutableStateOf<List<Journey>?>(null) }
    val showingTrip = stack.lastOrNull() == Screen.TRIP
    LaunchedEffect(showingTrip, prefs.from, prefs.to, prefs.walk, minute) {
        if (showingTrip) options = vm.tripOptions()
    }
    var nexts by remember { mutableStateOf<Map<TripKey, Journey?>>(emptyMap()) }
    LaunchedEffect(tab, prefs.saved, prefs.recents, minute) {
        if (tab == Tab.TRIPS) nexts = vm.nextFor((prefs.saved + prefs.recents).distinct())
    }

    val top = stack.lastOrNull()
    val dest = when (top) {
        null -> Dest(tab.name, 0)
        else -> Dest(top.name, stack.size)
    }

    Box(Modifier.fillMaxSize().background(hw.bg)) {
        AnimatedContent(
            targetState = dest,
            transitionSpec = {
                val slide = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
                when {
                    targetState.depth > initialState.depth ->
                        slideInHorizontally(slide) { it } togetherWith (slideOutHorizontally(slide) { -it / 4 } + fadeOut(targetAlpha = 0.6f))
                    targetState.depth < initialState.depth ->
                        ((slideInHorizontally(slide) { -it / 4 } + fadeIn(initialAlpha = 0.6f)) togetherWith slideOutHorizontally(slide) { it })
                            .apply { targetContentZIndex = -1f }
                    else -> fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                }
            },
            label = "screens",
        ) { d ->
            ScreenColumn {
                when (d.key) {
                    Tab.NOW.name -> NowScreen(
                        state,
                        prefs,
                        now,
                        NowActions(
                            pickFrom = { picker = PickField.FROM },
                            pickTo = { picker = PickField.TO },
                            settings = { push(Screen.SETTINGS) },
                            openTrip = { dep ->
                                tripDep = dep
                                push(Screen.TRIP)
                            },
                            remind = ::remind,
                            quick = { id ->
                                val from = prefs.from
                                if (from == null) {
                                    vm.setTo(id)
                                    picker = PickField.FROM
                                } else {
                                    vm.setTrip(from, id)
                                }
                            },
                            openRide = { push(Screen.RIDE) },
                            reminderTime = vm::reminderTime,
                        ),
                    )
                    Tab.TRIPS.name -> TripsScreen(
                        prefs,
                        nexts,
                        now,
                        onUse = { t ->
                            vm.setTrip(t.from, t.to)
                            goNow()
                        },
                        onNew = { picker = PickField.TO },
                    )
                    Tab.LINES.name -> LinesScreen(vm.todayInfo(), now, onSettings = { push(Screen.SETTINGS) })
                    Screen.TRIP.name -> options?.let { o ->
                        TripScreen(
                            journey = o.firstOrNull { it.dep == tripDep } ?: o.firstOrNull(),
                            prefs = prefs,
                            now = now,
                            onBack = ::pop,
                            onToggleSaved = vm::toggleSaved,
                            onRemind = ::remind,
                        )
                    }
                    Screen.RIDE.name -> prefs.ride?.let { ride ->
                        RideScreen(
                            ride,
                            now,
                            onMinimise = ::pop,
                            onEnd = {
                                vm.endRide()
                                goNow()
                            },
                        )
                    } ?: LaunchedEffect(Unit) { pop() }
                    Screen.SETTINGS.name -> SettingsScreen(
                        prefs = prefs,
                        today = vm.todayInfo(),
                        exactAllowed = Alerts.canScheduleExact(context),
                        notificationsAllowed = Alerts.canNotify(context),
                        actions = SettingsActions(
                            back = ::pop,
                            override = vm::setOverride,
                            walk = vm::setWalk,
                            lead = vm::setRemindLead,
                            lastWarn = vm::setLastWarn,
                            theme = vm::setTheme,
                            allowExact = {
                                if (Build.VERSION.SDK_INT >= 31) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                                    )
                                }
                            },
                            allowNotifications = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            openTimetable = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Timetable.SOURCE_URL)))
                            },
                        ),
                    )
                }
            }
        }

        // Floating controls: tab bar on the tabs, a primary action on trip and ride screens.
        val dock: Screen? = top
        AnimatedContent(
            targetState = dock,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            label = "dock",
        ) { screen ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 21.dp, end = 21.dp, bottom = 21.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                when (screen) {
                    null -> TabDock(
                        tab = tab,
                        onTab = { tab = it },
                        onSearch = { picker = PickField.TO },
                    )
                    Screen.TRIP -> TripDock(
                        onStart = {
                            val o = options.orEmpty()
                            val j = o.firstOrNull { it.dep == tripDep } ?: o.firstOrNull()
                            if (j != null) {
                                askNotifications()
                                vm.startRide(j)
                                stack[stack.lastIndex] = Screen.RIDE
                            }
                        },
                        onNext = {
                            val o = options.orEmpty()
                            if (o.isNotEmpty()) {
                                val i = o.indexOfFirst { it.dep == tripDep }
                                tripDep = o[(i + 1).mod(o.size)].dep
                                toast("Showing the ${clock(tripDep ?: 0L)}")
                            }
                        },
                    )
                    Screen.RIDE -> RideDock(prefs.rideAlert, vm::setRideAlert)
                    Screen.SETTINGS -> Spacer(Modifier.height(0.dp))
                }
            }
        }

        picker?.let { field ->
            val asking = if (field == PickField.FROM || prefs.from == null) PickField.FROM else PickField.TO
            val origin = if (asking == PickField.TO) prefs.from else null
            var plans by remember(asking, origin) { mutableStateOf<Map<String, Journey?>>(emptyMap()) }
            LaunchedEffect(asking, origin) {
                if (origin != null) plans = vm.plansFrom(origin)
            }
            StationPicker(
                asking = asking,
                origin = origin,
                current = if (asking == PickField.FROM) prefs.from else prefs.to,
                recents = prefs.recents.map { if (asking == PickField.FROM) it.from else it.to },
                plans = plans,
                onPick = { id ->
                    picker = null
                    if (asking == PickField.FROM) {
                        val to = if (prefs.to == id) prefs.from else prefs.to
                        if (to != null && to != id) {
                            vm.setTrip(id, to)
                            goNow()
                        } else {
                            vm.setFrom(id)
                            picker = PickField.TO
                        }
                    } else {
                        val from = prefs.from
                        if (from != null) vm.setTrip(from, id)
                        goNow()
                    }
                },
                onDismiss = { picker = null },
            )
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp)
                .navigationBarsPadding()
                .padding(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun Glass(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = CircleShape, content: @Composable () -> Unit) {
    val hw = LocalHw.current
    Box(
        modifier
            .shadow(14.dp, shape, ambientColor = Color(0x3314203A), spotColor = Color(0x3314203A))
            .clip(shape)
            .background(hw.glass)
            .border(0.5.dp, hw.glassBorder, shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TabDock(tab: Tab, onTab: (Tab) -> Unit, onSearch: () -> Unit) {
    val hw = LocalHw.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glass(Modifier.weight(1f).height(62.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
                val w = maxWidth / 3
                val x by animateDpAsState(w * tab.ordinal, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "tab")
                Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(hw.selected))
                Row(Modifier.fillMaxSize()) {
                    listOf(Tab.NOW to ("Now" to Icons.Rounded.Schedule), Tab.TRIPS to ("Trips" to Icons.Rounded.Commute), Tab.LINES to ("Lines" to Icons.Rounded.Map))
                        .forEach { (t, labelIcon) ->
                            val (label, icon) = labelIcon
                            val on = t == tab
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clip(CircleShape).press(scaleTo = 0.92f) { onTab(t) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(icon, null, tint = if (on) hw.tint else hw.text, modifier = Modifier.size(24.dp))
                                Text(label, style = HwType.tab, color = if (on) hw.tint else hw.text)
                            }
                        }
                }
            }
        }
        Glass(Modifier.size(62.dp).press(scaleTo = 0.92f, onClick = onSearch)) {
            Icon(Icons.Rounded.Search, "Search stations", tint = hw.text, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun TripDock(onStart: () -> Unit, onNext: () -> Unit) {
    val hw = LocalHw.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(62.dp)
                .shadow(14.dp, CircleShape, ambientColor = hw.tint, spotColor = hw.tint)
                .clip(CircleShape)
                .background(hw.tint)
                .press(scaleTo = 0.97f, onClick = onStart),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start trip", style = HwType.headline, color = Color.White)
        }
        Glass(Modifier.size(62.dp).press(scaleTo = 0.92f, onClick = onNext)) {
            Icon(Icons.Rounded.Schedule, "Show the next train", tint = hw.text, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RideDock(alert: Boolean, onAlert: (Boolean) -> Unit) {
    val hw = LocalHw.current
    Glass(Modifier.fillMaxWidth().height(62.dp), shape = RoundedCornerShape(31.dp)) {
        Row(
            Modifier.fillMaxSize().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = hw.text, modifier = Modifier.size(22.dp))
            Text("Alert one stop before", style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
            HwSwitch(alert, onAlert, "Alert one stop before")
        }
    }
}
