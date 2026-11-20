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
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.app.SavedRoute
import com.flash.headway.engine.Leg
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.lineDirKey
import com.flash.headway.engine.lineTerminal
import com.flash.headway.app.isDirect
import com.flash.headway.ui.theme.HwColors
import com.flash.headway.data.Timetable
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.flash.headway.data.DayType
import androidx.lifecycle.compose.LifecycleStartEffect

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
    var trainDelayFor by remember { mutableStateOf<TrainDelayTarget?>(null) }
    val plan by vm.plan.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var stationFor by remember { mutableStateOf<String?>(null) }
    // A route whose trips already under way are listed, to follow the one the rider's on.
    var onTheWayFor by remember { mutableStateOf<String?>(null) }
    // Other trains from a station on a followed trip: route id, station, after.
    var optionsFor by remember { mutableStateOf<Triple<String, String, Long>?>(null) }
    val scope = rememberCoroutineScope()
    // The Lines tab: the station each card shows next trains at (by line), and the card picking one.
    var lineStations by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var lineStationFor by remember { mutableStateOf<RouteId?>(null) }
    // A direction's delay being changed from the Lines tab: its key and title.
    var lineDelayFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    // The picked one, else where a saved route boards on that line, else mid-line.
    fun stationOn(route: RouteId): String {
        val stops = Network.routes.getValue(route).fwd.map { it.first }
        return lineStations[route.name]?.takeIf { it in stops }
            ?: prefs.routes.map { it.from }.firstOrNull { it in stops }
            ?: stops[stops.size / 2]
    }
    // Worked out on the engine thread while the tab shows, again when a delay or station changes,
    // and every 20 s so trains drop off as they leave.
    val lineBoards by produceState(emptyMap<RouteId, LineBoard>(), tab, settings, lineStations, prefs.lineDelays, prefs.routes) {
        if (tab != Tab.LINES || settings) return@produceState
        while (true) {
            value = LINE_CARDS.associateWith { r -> stationOn(r).let { st -> LineBoard(st, vm.lineBoard(r, st)) } }
            delay(20_000)
        }
    }
    var pickFrom by remember { mutableStateOf<String?>(null) }
    var topUp by remember { mutableStateOf(false) }

    // Work out trains only while the app is on screen.
    LifecycleStartEffect(vm) {
        vm.setForeground(true)
        onStopOrDispose { vm.setForeground(false) }
    }

    // The routes list, kept here so its place survives tab switches and a widget tap can scroll it.
    val routesList = rememberLazyListState()

    // A widget tap opens the app on that route (it may have flipped since), scrolled to its card.
    LaunchedEffect(openRoute) {
        val id = openRoute ?: return@LaunchedEffect
        vm.closePreview()
        settings = false
        tab = Tab.ROUTES
        val i = prefs.routes.indexOfFirst { it.id == id || it.id == reverseId(id) }
        // Item 0 is the title and banners; the cards follow in saved order.
        if (i >= 0) routesList.animateScrollToItem(i + 1)
        onRouteOpened()
    }

    BackHandler(enabled = settings) { settings = false }
    // Back closes a quick look before it leaves the app. (Sheets, composed later, go first.)
    BackHandler(enabled = !settings && previewStatus != null) { vm.closePreview() }

    // Each screen keeps its saved state (picked trains, scroll) while another is showing.
    val screens = rememberSaveableStateHolder()

    // The bell asks for permission and saves the countdown at once; it can only be posted once
    // allowed. Refused, there's no countdown, so the bell goes out rather than pretend.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.repostReminder()
        } else {
            vm.clearReminder()
            Toast.makeText(context, "Allow notifications to use countdowns", Toast.LENGTH_LONG).show()
        }
    }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun remind(s: RouteStatus, dep: Long) {
        if (s.isPreview) {
            toast("Save the route to start a countdown")
            return
        }
        val on = prefs.reminder?.let { it.routeId == s.route.id && it.dep == dep } == true
        if (!on) askNotifications()
        vm.toggleReminder(s.route, dep)
        toast(if (on) "Countdown off" else vm.reminderToast(s.route, dep))
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
            screens.SaveableStateProvider(key) {
            if (key == Tab.ROUTES.name) {
                // A lazy list, so only the route cards on screen are composed and drawn.
                ScreenLazy(routesList) {
                    routesScreen(
                        statuses = statuses,
                        preview = previewStatus,
                        prefs = prefs,
                        feed = feed,
                        now = now,
                        update = update,
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
                            trainDelay = { id, at, type, legs -> trainDelayFor = TrainDelayTarget(id, at, type, legs) },
                            swapPreview = vm::swapPreview,
                            endTrip = {
                                vm.endTrip()
                                toast("Trip ended")
                            },
                            onTheWay = { onTheWayFor = it },
                            update = vm::startUpdate,
                            dismissUpdate = vm::dismissUpdate,
                            missed = { id, backup ->
                                prefs.routes.firstOrNull { it.id == id }?.let { route ->
                                    vm.switchTrip(route, backup)
                                    toast("Switched to ${clock(backup.dep)}")
                                }
                            },
                            otherTrains = { id, station, after -> optionsFor = Triple(id, station, after) },
                        ),
                    )
                }
            } else ScreenColumn {
                when (key) {
                    Tab.LINES.name -> LinesScreen(
                        vm.todayInfo(), now, prefs.lineDelays, vm::setLineDelay,
                        boards = lineBoards,
                        onPickStation = { lineStationFor = it },
                        onDelayChip = { key, title -> lineDelayFor = key to title },
                        onSettings = { settings = true },
                    )
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
                            walk = vm::setWalk,
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
                            checkUpdate = {
                                scope.launch {
                                    if (vm.checkUpdate()) {
                                        settings = false
                                        tab = Tab.ROUTES
                                    } else {
                                        toast("Headway is up to date")
                                    }
                                }
                            },
                        ),
                    )
                }
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
                title = if (mode == Picker.ADD) "Choose starting station" else "Search",
                onPick = { from, to ->
                    picker = null
                    pickFrom = null
                    tab = Tab.ROUTES
                    if (mode == Picker.ADD) {
                        vm.addRoute(from, to)
                        toast("Route saved")
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

        trainDelayFor?.let { (id, at, type, legs) ->
            val route = prefs.routes.firstOrNull { it.id == id }
            if (route != null) {
                val own = prefs.delayRule(id).own(at, type)
                TrainDelaySheet(
                    key = "$id@$at",
                    title = if ((legs?.size ?: 1) > 1) "${clock(at)} trip" else "${clock(at)} train",
                    subtitle = "${Network.name(route.from)} → ${Network.name(route.to)}",
                    days = when (type) {
                        DayType.WEEKDAY -> "Weekdays"
                        DayType.SATURDAY -> "Saturdays"
                        DayType.SUNDAY -> "Sundays & holidays"
                    },
                    own = own?.second,
                    ownToday = own?.first?.substringAfter('/', "")?.length == 10,
                    routeDelay = prefs.delayOf(id),
                    onChange = { minutes, today -> vm.setTrainDelay(id, at, type, minutes, today) },
                    onDismiss = { trainDelayFor = null },
                    lines = tripLines(legs, hw),
                    lineDelays = prefs.lineDelays,
                    onLineDelay = vm::setLineDelay,
                    line = legs?.singleOrNull()?.let { directLine(it, hw) },
                    at = at,
                )
            }
        }
        // The route went (removed, or flipped direction): its train's sheet goes with it.
        LaunchedEffect(trainDelayFor, prefs.routes) {
            if (trainDelayFor?.let { t -> prefs.routes.none { it.id == t.routeId } } == true) trainDelayFor = null
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

        optionsFor?.let { (id, station, after) ->
            val route = prefs.routes.firstOrNull { it.id == id }
            if (route == null) {
                LaunchedEffect(id) { optionsFor = null }
            } else {
                TrainOptionsSheet(
                    station = station,
                    options = produceState<List<RouteDeparture>?>(null, id, station, after) { value = vm.optionsFrom(route, station, after) }.value,
                    onPick = { d ->
                        vm.switchTrip(route, d)
                        toast("Switched to ${clock(d.dep)}")
                    },
                    onDismiss = { optionsFor = null },
                )
            }
        }

        onTheWayFor?.let { id ->
            val route = prefs.routes.firstOrNull { it.id == id }
            if (route == null) {
                LaunchedEffect(id) { onTheWayFor = null }
            } else {
                OnTheWaySheet(
                    title = "${Network.name(route.from)} → ${Network.name(route.to)}",
                    to = route.to,
                    trips = produceState<List<RouteDeparture>?>(null, id) { value = vm.tripsOnTheWay(route) }.value,
                    now = now,
                    onPick = { d ->
                        askNotifications()
                        vm.followOnTheWay(route, d)
                        toast("Trip started")
                    },
                    onJoinAt = { station, inMinutes ->
                        askNotifications()
                        scope.launch {
                            val d = vm.followFrom(route, station, inMinutes)
                            toast(
                                if (d == null) {
                                    "No trains from ${Network.name(station)}"
                                } else {
                                    "Trip started from ${Network.name(station)}"
                                },
                            )
                        }
                    },
                    onDismiss = { onTheWayFor = null },
                )
            }
        }

        lineDelayFor?.let { (key, title) ->
            LineDelaySheet(title, prefs.lineDelayOf(key), onChange = { vm.setLineDelay(key, it) }, onDismiss = { lineDelayFor = null })
        }

        lineStationFor?.let { r ->
            LineStationSheet(
                title = LINE_TITLES.getValue(r),
                route = r,
                selected = stationOn(r),
                onPick = { lineStations = lineStations + (r.name to it) },
                onDismiss = { lineStationFor = null },
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

        // The route went (removed): its menu goes with it. (Not done mid-composition.)
        LaunchedEffect(menuFor, prefs.routes) {
            val m = menuFor ?: return@LaunchedEffect
            if (prefs.routes.none { it.id == m || it.id == reverseId(m) }) menuFor = null
        }
        menuFor?.let { menuId ->
            // The route may have flipped while its menu is open (its switch time, or a change
            // here): follow it to the other direction.
            val route = prefs.routes.firstOrNull { it.id == menuId } ?: prefs.routes.firstOrNull { it.id == reverseId(menuId) }
            val id = route?.id ?: menuId
            if (route != null) {
                RouteMenu(
                    title = "${Network.name(route.from)} → ${Network.name(route.to)}",
                    isTop = prefs.routes.firstOrNull()?.id == id,
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
                        toast("Route removed")
                    },
                    coaches = coachStops(route, statuses?.firstOrNull { it.route.id == id }?.departures.orEmpty(), prefs),
                    onCoach = { station, coach -> vm.setCoach(id, station, coach) },
                    routeId = id,
                    // A route only reached with a change has no delay of its own: each train's line has one.
                    journeyLines = if (isDirect(route)) null else routeTripLines(statuses?.firstOrNull { it.route.id == id }?.departures.orEmpty(), hw),
                    lineDelays = prefs.lineDelays,
                    onLineDelay = vm::setLineDelay,
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
private fun ScreenLazy(state: LazyListState, content: LazyListScope.() -> Unit) {
    // The same frame as ScreenColumn, as a lazy list.
    Box(Modifier.fillMaxSize().background(LocalHw.current.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = WindowInsets.navigationBars.asPaddingValues().let { nav ->
                PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = nav.calculateBottomPadding() + 132.dp)
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
                .padding(bottom = 132.dp),
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

/** The train whose own delay is being set: its route, timetabled departure, kind of day and legs. */
private data class TrainDelayTarget(val routeId: String, val at: Long, val type: DayType, val legs: List<Leg>?)

/** Each train of a trip with changes (its line and direction), in order; none for a direct train. */
private fun tripLines(legs: List<Leg>?, hw: HwColors): List<TripLine> {
    if (legs == null || legs.size < 2) return emptyList()
    return legs.distinctBy { it.trip.lineDirKey }.mapIndexed { i, leg ->
        TripLine(
            key = leg.trip.lineDirKey,
            name = "${legShort(leg)} towards ${Network.name(lineTerminal(leg.trip.route, leg.trip.dir))}",
            colors = legColors(leg, hw),
            boards = if (i == 0 && leg.from == legs.first().from) "From ${Network.name(leg.from)}" else "Change at ${Network.name(leg.from)}",
        )
    }
}

/** A direct train's line and direction, for its delay row. */
private fun directLine(leg: Leg, hw: HwColors): TripLine = TripLine(
    key = leg.trip.lineDirKey,
    name = "${legShort(leg)} towards ${Network.name(lineTerminal(leg.trip.route, leg.trip.dir))}",
    colors = legColors(leg, hw),
    boards = "",
)

/** Every train the upcoming trips with changes ride on a route, for the route menu's delays. */
private fun routeTripLines(departures: List<RouteDeparture>, hw: HwColors): List<TripLine> =
    departures.mapNotNull { d -> d.legs?.takeIf { it.size > 1 } }.flatMap { tripLines(it, hw) }.distinctBy { it.key }

/**
 * The trains to pick a best coach for: just the one on a direct route, else the first train and
 * one for each station the upcoming trips change at (they may change at different ones).
 */
private fun coachStops(route: SavedRoute, departures: List<RouteDeparture>, prefs: Prefs): List<CoachStop> {
    val first = departures.firstOrNull()?.legs?.firstOrNull()
    val changes = departures.flatMap { it.legs.orEmpty().drop(1) }.distinctBy { it.from }
    if (changes.isEmpty()) return listOf(CoachStop(null, "Best coach", null, prefs.coachFor(route.id, null)))
    return listOf(CoachStop(null, "${Network.name(route.from)}", first?.let { legShort(it) }, prefs.coachFor(route.id, null))) +
        changes.map { leg -> CoachStop(leg.from, "Change at ${Network.name(leg.from)}", legShort(leg), prefs.coachFor(route.id, leg.from)) }
}

/** The Lines tab's cards, in order, and their titles. */
private val LINE_CARDS = listOf(RouteId.BLUE, RouteId.GREEN, RouteId.INTER, RouteId.YELLOW)
private val LINE_TITLES = mapOf(RouteId.BLUE to "Blue Line", RouteId.GREEN to "Green Line", RouteId.INTER to "Inter-corridor", RouteId.YELLOW to "Yellow Line")
