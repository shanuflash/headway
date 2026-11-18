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
    fun startCountdown(context: Context, route: SavedRoute, dep: Long, rule: DelayRule = DelayRule()) {
        val app = context.applicationContext
        SharedEngine.post {
            val following = SharedEngine.with(app) { it.delayedDepartures(route, dep + 1, 1, rule).firstOrNull() }
            postCountdown(app, route, dep, following?.dep, rule)
            scheduleSoon(app, route, dep)
        }
    }

    /**
     * Follows a trip with changes train by train: a countdown to boarding, then to each change's
     * train, then to arrival. An alarm as each train leaves posts the next step, so it carries on
     * with the app closed; past arrival it clears itself. Planned on the rider's line delays.
     */
    fun startTrip(context: Context, route: SavedRoute, trip: ActiveTrip) {
        val app = context.applicationContext
        SharedEngine.post {
            val j = SharedEngine.with(app) { it.followTrip(route, trip, Widgets.lineDelays(app)) }
            val nm = app.getSystemService(NotificationManager::class.java)
            if (j == null || Clock.now() > j.arr + 60_000L) {
                nm.cancel(ID_COUNTDOWN)
                return@post
            }
            postTrip(app, route, trip, j)
        }
    }

    private fun postTrip(context: Context, route: SavedRoute, trip: ActiveTrip, j: RouteDeparture) {
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
                toward(leg) + " · ${Network.transfer(leg.from)} min walk",
                leg.dep,
            )
        }
        if (canNotify(context)) {
            val n = Notification.Builder(context, CHANNEL_COUNTDOWN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setWhen(at - Clock.offset)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openApp(context))
                .setDeleteIntent(broadcast(context, ACTION_STOP, route, at))
                .addAction(Notification.Action.Builder(null, "End trip", broadcast(context, ACTION_STOP, route, at)).build())
                .build()
            context.getSystemService(NotificationManager::class.java).notify(ID_COUNTDOWN, n)
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
        if (leg != null) scheduleSoon(context, route, leg.dep)
    }

    private fun tripIntent(context: Context, route: SavedRoute, trip: ActiveTrip): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).setAction(ACTION_TRIP)
            .putExtra(EXTRA_FROM, route.from).putExtra(EXTRA_TO, route.to).putExtra(EXTRA_TRIP, encodeTrip(trip))
        return PendingIntent.getBroadcast(context, 4, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

    /** An alarm [SOON_MIN] minutes before [dep] that buzzes with [postSoon]. */
    private fun scheduleSoon(context: Context, route: SavedRoute, dep: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = broadcast(context, ACTION_FIRE, route, dep)
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

    /** "The 8:14 leaves in 3 min": a buzz, so the countdown isn't missed in a pocket. */
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

    private fun postCountdown(context: Context, route: SavedRoute, dep: Long, following: Long?, rule: DelayRule) {
        if (!canNotify(context)) return
        val text = buildString {
            append("Leaves ${clock(dep)}")
            if (following != null) append(" · Next ${clock(following)}")
        }
        val builder = Notification.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${Network.name(route.from)} → ${Network.name(route.to)}")
            .setContentText(text)
            .setWhen(dep - Clock.offset)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        context.getSystemService(NotificationManager::class.java).notify(ID_COUNTDOWN, builder.build())
    }

    private fun broadcast(context: Context, action: String, route: SavedRoute?, dep: Long, rule: DelayRule = DelayRule()): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).setAction(action)
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
            Alerts.ACTION_FIRE -> if (route != null) Alerts.postSoon(context, route, dep)
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
