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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.flash.headway.app.Clock
import com.flash.headway.app.HeadwayViewModel
import com.flash.headway.app.Prefs
import com.flash.headway.app.RouteStatus
import com.flash.headway.app.pairKey
import com.flash.headway.app.reverseId
import com.flash.headway.app.timeKey
import com.flash.headway.data.Network
import com.flash.headway.data.Timetable
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlinx.coroutines.delay

enum class Tab { ROUTES, LINES, MAP }

private enum class Picker { ADD, LOOK }

@Composable
fun HeadwayApp(vm: HeadwayViewModel, prefs: Prefs, openRoute: String?, onRouteOpened: () -> Unit) {
    val hw = LocalHw.current
    val context = LocalContext.current
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val previewStatus by vm.previewStatus.collectAsStateWithLifecycle()

    // One clock for every countdown, aligned to the second. It's handed down as a function and
    // only read by the bits of text that tick, so the rest of the app doesn't recompose each second.
    val clockState = produceState(Clock.now()) {
        while (true) {
            delay(1000 - Clock.now() % 1000)
            value = Clock.now()
        }
    }
    val now: () -> Long = remember(clockState) { { clockState.value } }

    var tab by rememberSaveable { mutableStateOf(Tab.ROUTES) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var whenOpen by remember { mutableStateOf(false) }
    var trainDelayFor by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val plan by vm.plan.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    var stationFor by remember { mutableStateOf<String?>(null) }
    var pickFrom by remember { mutableStateOf<String?>(null) }
    var topUp by remember { mutableStateOf(false) }

    // A widget tap opens the app on that route.
    LaunchedEffect(openRoute) {
        if (openRoute != null) {
            vm.closePreview()
            settings = false
            tab = Tab.ROUTES
            onRouteOpened()
        }
    }

    BackHandler(enabled = settings) { settings = false }

    // The bell asks for permission and saves the countdown at once; it can only be posted once
    // allowed. Refused, there's no countdown, so the bell goes out rather than pretend.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.repostReminder()
        } else {
            vm.clearReminder()
            Toast.makeText(context, "Allow notifications to get the countdown", Toast.LENGTH_LONG).show()
        }
    }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun remind(s: RouteStatus, dep: Long) {
        if (previewStatus?.route?.id == s.route.id) {
            toast("Save the route to set a reminder")
            return
        }
        val on = prefs.reminder?.let { it.routeId == s.route.id && it.dep == dep } == true
        if (!on) askNotifications()
        vm.toggleReminder(s.route, dep)
        toast(if (on) "Reminder off" else vm.reminderToast(s.route, dep))
    }

    val screen = if (settings) "settings" else tab.name

    Box(Modifier.fillMaxSize().background(hw.bg)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val slide = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
                when {
                    targetState == "settings" ->
                        slideInHorizontally(slide) { it } togetherWith (slideOutHorizontally(slide) { -it / 4 } + fadeOut(targetAlpha = 0.6f))
                    initialState == "settings" ->
                        ((slideInHorizontally(slide) { -it / 4 } + fadeIn(initialAlpha = 0.6f)) togetherWith slideOutHorizontally(slide) { it })
                            .apply { targetContentZIndex = -1f }
                    else -> fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                }
            },
            label = "screens",
        ) { key ->
            if (key == Tab.ROUTES.name) {
                // A lazy list, so only the route cards on screen are composed and drawn.
                ScreenLazy {
                    routesScreen(
                        statuses = statuses,
                        preview = previewStatus,
                        prefs = prefs,
                        feed = feed,
                        now = now,
                        actions = RouteActions(
                            add = { picker = Picker.ADD },
                            look = { picker = Picker.LOOK },
                            settings = { settings = true },
                            swap = vm::swapRoute,
                            remind = ::remind,
                            menu = { menuFor = it },
                            closePreview = vm::closePreview,
                            mockOff = { vm.setMock(null) },
                            topUp = { topUp = true },
                            savePreview = {
                                vm.savePreview()
                                toast("Route saved")
                            },
                            whenOpen = { whenOpen = true },
                            trainDelay = { id, at -> trainDelayFor = id to at },
                        ),
                    )
                }
            } else ScreenColumn {
                when (key) {
                    Tab.LINES.name -> LinesScreen(vm.todayInfo(), now, onSettings = { settings = true })
                    Tab.MAP.name -> MapScreen(onSettings = { settings = true }, onStation = { stationFor = it })
                    else -> SettingsScreen(
                        prefs = prefs,
                        today = vm.todayInfo(),
                        notificationsAllowed = Alerts.canNotify(context),
                        actions = SettingsActions(
                            back = { settings = false },
                            override = vm::setOverride,
                            count = vm::setCount,
                            lastWarn = vm::setLastWarn,
                            theme = vm::setTheme,
                            mock = vm::setMock,
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

        AnimatedContent(
            targetState = settings,
            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut()) },
            modifier = Modifier.align(Alignment.BottomCenter),
            label = "dock",
        ) { hidden ->
            if (!hidden) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 21.dp, end = 21.dp, bottom = 21.dp),
                ) {
                    TabDock(tab = tab, onTab = { tab = it }, onSearch = { picker = Picker.LOOK })
                }
            }
        }

        picker?.let { mode ->
            RoutePicker(
                title = if (mode == Picker.ADD) "Where do you board?" else "Check a station",
                onPick = { from, to ->
                    picker = null
                    pickFrom = null
                    tab = Tab.ROUTES
                    if (mode == Picker.ADD) {
                        vm.addRoute(from, to)
                        toast("Saved ${Network.name(from)} → ${Network.name(to)}")
                    } else {
                        vm.showPreview(from, to)
                    }
                },
                onDismiss = {
                    picker = null
                    pickFrom = null
                },
                startFrom = pickFrom,
            )
        }

        if (topUp) TopUpSheet(onDismiss = { topUp = false })

        trainDelayFor?.let { (id, at) ->
            val route = prefs.routes.firstOrNull { it.id == id }
            if (route == null) {
                trainDelayFor = null
            } else {
                TrainDelaySheet(
                    title = "The ${clock(at)} train",
                    subtitle = "${Network.name(route.from)} → ${Network.name(route.to)} · every day",
                    own = prefs.trainDelays[id]?.get(timeKey(at)),
                    routeDelay = prefs.delayOf(id),
                    onChange = { vm.setTrainDelay(id, at, it) },
                    onDismiss = { trainDelayFor = null },
                )
            }
        }

        val planned = preview
        if (whenOpen && planned != null) {
            WhenSheet(
                title = "${Network.name(planned.from)} → ${Network.name(planned.to)}",
                plan = plan,
                onDone = { vm.setPlan(it) },
                onDismiss = { whenOpen = false },
            )
        }

        stationFor?.let { id ->
            StationSheet(
                id = id,
                // Worked out on the engine thread, and again every half minute while it's open.
                boards = produceState(emptyList<StationBoard>(), id) {
                    while (true) {
                        value = vm.stationBoard(id)
                        delay(30_000)
                    }
                }.value,
                now = now,
                onRouteFrom = {
                    stationFor = null
                    pickFrom = id
                    picker = Picker.ADD
                },
                onDismiss = { stationFor = null },
            )
        }

        menuFor?.let { menuId ->
            // The route may have flipped while its menu is open (its switch time, or a change
            // here): follow it to the other direction.
            val route = prefs.routes.firstOrNull { it.id == menuId } ?: prefs.routes.firstOrNull { it.id == reverseId(menuId) }
            val id = route?.id ?: menuId
            if (route == null) {
                menuFor = null
            } else {
                RouteMenu(
                    title = "${Network.name(route.from)} → ${Network.name(route.to)}",
                    returnTitle = "${Network.name(route.to)} → ${Network.name(route.from)}",
                    onReturn = {
                        menuFor = null
                        vm.addReturn(id)
                    },
                    onPlan = {
                        menuFor = null
                        vm.showPreview(route.from, route.to)
                        tab = Tab.ROUTES
                        whenOpen = true
                    },
                    onTop = {
                        menuFor = null
                        vm.moveToTop(id)
                    },
                    onRemove = {
                        menuFor = null
                        vm.removeRoute(id)
                        toast("Removed ${Network.name(route.from)} → ${Network.name(route.to)}")
                    },
                    coach = prefs.coaches[id],
                    onCoach = { vm.setCoach(id, it) },
                    routeId = id,
                    delay = prefs.delayOf(id),
                    delayPreview = { vm.delayPreview(route, it) },
                    onDelay = { vm.setDelay(id, it) },
                    flipCard = {
                        FlipCard(
                            route = route,
                            flip = prefs.flips[pairKey(route)],
                            bothSaved = prefs.routes.any { it.id == reverseId(id) },
                            now = now(),
                            onToggle = { vm.setFlip(id, it) },
                            onMoveTime = { vm.moveFlipTime(id, it) },
                            onSwapHome = { vm.swapFlipHome(id) },
                        )
                    },
                    onDismiss = { menuFor = null },
                )
            }
        }
    }
}

@Composable
private fun ScreenLazy(content: LazyListScope.() -> Unit) {
    // The same frame as ScreenColumn, as a lazy list.
    Box(Modifier.fillMaxSize().background(LocalHw.current.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = WindowInsets.navigationBars.asPaddingValues().let { nav ->
                PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = nav.calculateBottomPadding() + 112.dp)
            },
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    // Opaque, so a screen sliding in covers the one underneath instead of showing through it.
    Box(Modifier.fillMaxSize().background(LocalHw.current.bg), contentAlignment = Alignment.TopCenter) {
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
private fun TabDock(tab: Tab, onTab: (Tab) -> Unit, onSearch: () -> Unit) {
    val hw = LocalHw.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glass(Modifier.weight(1f).height(62.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
                val w = maxWidth / 3
                val x by animateDpAsState(w * tab.ordinal, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "tab")
                Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(hw.selected))
                Row(Modifier.fillMaxSize()) {
                    listOf(Tab.ROUTES to ("Routes" to Icons.Rounded.Commute), Tab.LINES to ("Lines" to Icons.Rounded.Schedule), Tab.MAP to ("Map" to Icons.Rounded.Map)).forEach { (t, labelIcon) ->
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
            Icon(Icons.Rounded.Search, "Check any station", tint = hw.text, modifier = Modifier.size(24.dp))
        }
    }
}
