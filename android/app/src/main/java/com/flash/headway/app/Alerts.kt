package com.flash.headway.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.flash.headway.MainActivity
import com.flash.headway.R
import com.flash.headway.data.Network
import com.flash.headway.ui.clock
import com.flash.headway.ui.legPlatform
import com.flash.headway.ui.legLineName
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.changeMinutes
import com.flash.headway.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The train countdown: a live notification that counts down to the moment the train leaves,
 * and works with the app closed. It counts to departure only; no walking-time offset.
 */
object Alerts {
    private const val CHANNEL_COUNTDOWN = "countdown"
    private const val CHANNEL_SOON = "leaving_soon"
    private const val ID_COUNTDOWN = 2
    private const val ID_SOON = 3

    /** The countdown buzzes this many minutes before the train leaves. */
    const val SOON_MIN = 3

    const val ACTION_FIRE = "com.flash.headway.FIRE"
    const val ACTION_NEXT = "com.flash.headway.NEXT"
    const val ACTION_STOP = "com.flash.headway.STOP"
    const val ACTION_TRIP = "com.flash.headway.TRIP"
    const val ACTION_MISSED = "com.flash.headway.MISSED"
    private const val EXTRA_TRIP = "trip"
    private const val EXTRA_FROM = "from"
    private const val EXTRA_TO = "to"
    private const val EXTRA_DEP = "dep"
    private const val EXTRA_DELAY = "delay"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The old "time to set off" channel belonged to the walking-time alarm, which is gone.
        nm.deleteNotificationChannel("reminders")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COUNTDOWN, "Live countdown", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Time left until your train or next change"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOON, "Departure alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An alert $SOON_MIN min before your train leaves"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Posts the countdown to [dep] (already moved by the route's delays, which the next train gets
     * too) and sets the buzz [SOON_MIN] minutes before. Runs on the background thread, in order
     * with [stopCountdown], using the timetable as the rider sees it (their override, the feed).
     */
    fun startCountdown(context: Context, route: SavedRoute, dep: Long, rule: DelayRule = DelayRule(), alert: Boolean = false) {
        val app = context.applicationContext
        SharedEngine.post {
            val following = SharedEngine.with(app) { it.delayedDepartures(route, dep + 1, 1, rule).firstOrNull() }
            postCountdown(app, route, dep, following?.dep, rule, alert)
            if (!alert) scheduleSoon(app, route, dep, rule)
        }
    }

    /**
     * Follows a trip with changes train by train: a countdown to boarding, then to each change's
     * train, then to arrival. An alarm as each train leaves posts the next step, so it carries on
     * with the app closed; past arrival it clears itself. Planned on the rider's line delays.
     */
    fun startTrip(context: Context, route: SavedRoute, trip: ActiveTrip, alert: Boolean = false) {
        val app = context.applicationContext
        SharedEngine.post {
            val j = SharedEngine.with(app) { it.followTrip(route, trip, Widgets.lineDelays(app)) }
            val nm = app.getSystemService(NotificationManager::class.java)
            if (j == null || Clock.now() > j.arr + 60_000L) {
                nm.cancel(ID_COUNTDOWN)
                return@post
            }
            postTrip(app, route, trip, j, alert)
        }
    }

    private fun postTrip(context: Context, route: SavedRoute, trip: ActiveTrip, j: RouteDeparture, alert: Boolean = false) {
        val legs = j.legs ?: return
        val now = Clock.now()
        val i = j.nextLeg(now)
        val leg = legs.getOrNull(i)
        fun toward(l: com.flash.headway.engine.Leg) =
            "${legLineName(l)} towards ${Network.name(l.trip.dest)}${legPlatform(l)?.let { " · $it" }.orEmpty()}"
        val (title, text, at) = when {
            leg == null -> Triple("Arrive at ${Network.name(route.to)}", "Arrives ${clock(j.arr)}", j.arr)
            i == 0 -> Triple("Board at ${Network.name(leg.from)} · ${clock(leg.dep)}", toward(leg), leg.dep)
            else -> Triple(
                "Change at ${Network.name(leg.from)} · ${clock(leg.dep)}",
                toward(leg) + " · ${changeMinutes(leg.from, Widgets.walk(context))} min walk",
                leg.dep,
            )
        }
        if (canNotify(context)) {
            // One notification: a few minutes before a train it's posted again on the alert
            // channel, so it buzzes itself instead of a second notification appearing.
            val n = Notification.Builder(context, if (alert) CHANNEL_SOON else CHANNEL_COUNTDOWN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(tripStyle(context, legs, j, i, text))
                .setColor(leg?.let { lineColor(it) } ?: BRAND)
                .setWhen(at - Clock.offset)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(!alert)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openApp(context))
                .setDeleteIntent(broadcast(context, ACTION_STOP, route, at))
                .apply {
                    // Missed the train to catch: move on to the next way from there.
                    if (leg != null) addAction(Notification.Action.Builder(null, "Missed it", tripIntent(context, route, trip, ACTION_MISSED)).build())
                }
                .addAction(Notification.Action.Builder(null, "End trip", broadcast(context, ACTION_STOP, route, at)).build())
                .liveUpdate()
                .build()
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(ID_SOON)
            nm.notify(ID_COUNTDOWN, n)
        }
        // The next step when this train leaves (or a minute past arrival, to clear it), and a buzz
        // a few minutes before a train to catch.
        val am = context.getSystemService(AlarmManager::class.java)
        val next = (if (leg == null) j.arr + 60_000L else at + 1_000L) - Clock.offset
        val pi = tripIntent(context, route, trip)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, next, 30_000L, pi)
        }
        if (leg != null && !alert) scheduleSoon(context, route, leg.dep, trip = trip)
    }

    private fun tripIntent(context: Context, route: SavedRoute, trip: ActiveTrip, action: String = ACTION_TRIP): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).setAction(action)
            .putExtra(EXTRA_FROM, route.from).putExtra(EXTRA_TO, route.to).putExtra(EXTRA_TRIP, encodeTrip(trip))
        val code = if (action == ACTION_MISSED) 5 else 4
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The followed trip's next train was missed: follow the next way on from the station it was
     * to be boarded at instead. Returns the new trip, or null when nothing else runs tonight.
     */
    fun missedTrain(context: Context, route: SavedRoute, trip: ActiveTrip): ActiveTrip? {
        val app = context.applicationContext
        val next = SharedEngine.with(app) { s ->
            val now = s.followTrip(route, trip, Widgets.lineDelays(app)) ?: return@with null
            s.backupFor(route, now, Clock.now())
        } ?: return null
        val moved = followOf(route, next, next.legs?.firstOrNull()?.from) ?: return null
        startTrip(app, route, moved)
        return moved
    }

    fun stopCountdown(context: Context) {
        val app = context.applicationContext
        SharedEngine.post {
            val am = app.getSystemService(AlarmManager::class.java)
            am.cancel(broadcast(app, ACTION_FIRE, null, 0))
            am.cancel(broadcast(app, ACTION_TRIP, null, 0))
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.cancel(ID_COUNTDOWN)
            nm.cancel(ID_SOON)
        }
    }

    /**
     * An alarm [SOON_MIN] minutes before [dep] that posts the countdown (or the trip's step) again
     * with a buzz.
     */
    private fun scheduleSoon(context: Context, route: SavedRoute, dep: Long, rule: DelayRule = DelayRule(), trip: ActiveTrip? = null) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = broadcast(context, ACTION_FIRE, route, dep, rule, trip)
        // The app clock may be mocked; alarms run on the real one.
        val at = dep - SOON_MIN * 60_000L - Clock.offset
        if (at <= System.currentTimeMillis()) {
            am.cancel(pi)
            return
        }
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        }
    }

    /** Unused since the countdown buzzes itself; kept so an alarm set by an older version still shows something. */
    internal fun postSoon(context: Context, route: SavedRoute, dep: Long) {
        if (!canNotify(context)) return
        val left = ((dep - Clock.now()) / 60_000L).coerceAtLeast(0)
        val n = Notification.Builder(context, CHANNEL_SOON)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (left <= 0) "Leaving now" else "Leaves in $left min")
            .setContentText("${clock(dep)} · ${Network.name(route.from)} → ${Network.name(route.to)}")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setTimeoutAfter((dep - Clock.now() + 60_000L).coerceAtLeast(60_000L))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_SOON, n)
    }

    private fun postCountdown(context: Context, route: SavedRoute, dep: Long, following: Long?, rule: DelayRule, alert: Boolean = false) {
        if (!canNotify(context)) return
        val text = buildString {
            append("Leaves ${clock(dep)}")
            if (following != null) append(" · Next ${clock(following)}")
        }
        val builder = Notification.Builder(context, if (alert) CHANNEL_SOON else CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${Network.name(route.from)} → ${Network.name(route.to)}")
            .setContentText(text)
            .setColor(BRAND)
            .setWhen(dep - Clock.offset)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            // Swiping it away (Android 14 lets you) stops the countdown, so the bell goes out too.
            .setDeleteIntent(broadcast(context, ACTION_STOP, route, dep))
            .setTimeoutAfter((dep - Clock.now() + 60_000L).coerceAtLeast(60_000L))
        if (following != null) {
            builder.addAction(
                Notification.Action.Builder(null, "Take ${clock(following)}", broadcast(context, ACTION_NEXT, route, dep, rule)).build(),
            )
        }
        builder.addAction(Notification.Action.Builder(null, "Stop", broadcast(context, ACTION_STOP, route, dep)).build())
        builder.liveUpdate()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_SOON)
        nm.notify(ID_COUNTDOWN, builder.build())
    }

    /** Headway's M3 primary: tints the small icon, app name and actions. */
    private const val BRAND = 0xFF0058BB.toInt()

    /** A leg's line colour (the inter-corridor service runs on Green Line track). */
    private fun lineColor(leg: com.flash.headway.engine.Leg): Int = when {
        leg.trip.route == com.flash.headway.data.RouteId.INTER -> 0xFF006E28.toInt()
        leg.trip.line == com.flash.headway.data.Line.BLUE -> 0xFF005BC0.toInt()
        leg.trip.line == com.flash.headway.data.Line.GREEN -> 0xFF006E28.toInt()
        else -> 0xFFF2C230.toInt()
    }

    /**
     * Android 16+: asks for a Live Update, so the countdown is promoted to the top of the shade,
     * the lock screen and a status-bar chip counting down to the train.
     */
    private fun Notification.Builder.liveUpdate(): Notification.Builder = apply {
        if (Build.VERSION.SDK_INT >= 36) {
            addExtras(android.os.Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
    }

    /**
     * The trip as a progress bar (Android 16+): one segment per train in its line's colour, grey
     * for waits and walks between, a point at each change, the tracker where the rider is now.
     * Older Android shows the step as big text.
     */
    private fun tripStyle(context: Context, legs: List<com.flash.headway.engine.Leg>, j: RouteDeparture, current: Int, text: String): Notification.Style {
        if (Build.VERSION.SDK_INT < 36 || legs.isEmpty()) return Notification.BigTextStyle().bigText(text)
        val start = legs.first().dep
        fun min(ms: Long) = ((ms - start) / 60_000L).toInt().coerceAtLeast(0)
        val gap = 0xFFC4C6D0.toInt()
        val segments = ArrayList<Notification.ProgressStyle.Segment>()
        val points = ArrayList<Notification.ProgressStyle.Point>()
        var at = 0
        legs.forEachIndexed { k, l ->
            val from = min(l.dep)
            if (from > at) segments.add(Notification.ProgressStyle.Segment(from - at).setColor(gap))
            if (k > 0) points.add(Notification.ProgressStyle.Point(maxOf(from, 1)).setColor(lineColor(l)))
            val to = maxOf(min(l.arr), from + 1)
            segments.add(Notification.ProgressStyle.Segment(to - from).setColor(lineColor(l)))
            at = to
        }
        val progress = when (val l = legs.getOrNull(current)) {
            null -> at
            else -> min(l.dep)
        }
        return Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(segments)
            .setProgressPoints(points)
            .setProgress(progress.coerceIn(0, at))
            .setProgressTrackerIcon(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification))
    }

    private fun broadcast(context: Context, action: String, route: SavedRoute?, dep: Long, rule: DelayRule = DelayRule(), trip: ActiveTrip? = null): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).setAction(action)
        if (trip != null) intent.putExtra(EXTRA_TRIP, encodeTrip(trip))
        if (route != null) intent.putExtra(EXTRA_FROM, route.from).putExtra(EXTRA_TO, route.to)
        intent.putExtra(EXTRA_DEP, dep).putExtra(EXTRA_DELAY, encodeRule(rule))
        val code = when (action) {
            ACTION_FIRE -> 1
            ACTION_NEXT -> 2
            ACTION_TRIP -> 4
            else -> 3
        }
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun routeFrom(intent: Intent): SavedRoute? {
        val from = intent.getStringExtra(EXTRA_FROM) ?: return null
        val to = intent.getStringExtra(EXTRA_TO) ?: return null
        return SavedRoute(from, to)
    }

    internal fun tripFrom(intent: Intent): ActiveTrip? = decodeTrip(intent.getStringExtra(EXTRA_TRIP))

    internal fun depFrom(intent: Intent): Long = intent.getLongExtra(EXTRA_DEP, 0L)

    internal fun ruleFrom(intent: Intent): DelayRule = decodeRule(intent.getStringExtra(EXTRA_DELAY))
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val route = Alerts.routeFrom(intent)
        val dep = Alerts.depFrom(intent)
        when (intent.action) {
            Alerts.ACTION_STOP -> {
                Alerts.stopCountdown(context)
                updatePrefs(context) { it.copy(reminder = null, trip = null) }
            }
            Alerts.ACTION_MISSED -> {
                val trip = Alerts.tripFrom(intent)
                if (route != null && trip != null) {
                    val app = context.applicationContext
                    val pending = goAsync()
                    SharedEngine.post {
                        try {
                            val moved = Alerts.missedTrain(app, route, trip)
                            if (moved != null) runBlocking { PrefsStore(app).update { it.copy(trip = moved, reminder = null) } }
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
            Alerts.ACTION_TRIP -> {
                val trip = Alerts.tripFrom(intent)
                if (route != null && trip != null) Alerts.startTrip(context, route, trip)
            }
            Alerts.ACTION_NEXT -> if (route != null) {
                val rule = Alerts.ruleFrom(intent)
                val app = context.applicationContext
                val pending = goAsync()
                SharedEngine.post {
                    try {
                        val following = SharedEngine.with(app) { it.delayedDepartures(route, dep + 1, 1, rule).firstOrNull() }
                        if (following != null) {
                            Alerts.startCountdown(app, route, following.dep, rule)
                            runBlocking { PrefsStore(app).update { it.copy(reminder = Reminder(route.id, following.dep)) } }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            // A few minutes before a train: the same notification again, with a buzz.
            Alerts.ACTION_FIRE -> if (route != null) {
                val trip = Alerts.tripFrom(intent)
                if (trip != null) Alerts.startTrip(context, route, trip, alert = true) else Alerts.startCountdown(context, route, dep, Alerts.ruleFrom(intent), alert = true)
            }
        }
    }

    private fun updatePrefs(context: Context, transform: (Prefs) -> Prefs) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                PrefsStore(context.applicationContext).update(transform)
            } finally {
                pending.finish()
            }
        }
    }
}
