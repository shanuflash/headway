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
    private const val EXTRA_FROM = "from"
    private const val EXTRA_TO = "to"
    private const val EXTRA_DEP = "dep"
    private const val EXTRA_DELAY = "delay"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The old "time to set off" channel belonged to the walking-time alarm, which is gone.
        nm.deleteNotificationChannel("reminders")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COUNTDOWN, "Train countdown", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A live countdown to the train you're catching"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOON, "Train leaving soon", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A buzz $SOON_MIN minutes before the train you're counting down to leaves"
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

    fun stopCountdown(context: Context) {
        val app = context.applicationContext
        SharedEngine.post {
            app.getSystemService(AlarmManager::class.java).cancel(broadcast(app, ACTION_FIRE, null, 0))
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
            .setContentTitle("The ${clock(dep)} leaves in ${if (left <= 0) "under a minute" else "$left min"}")
            .setContentText("${Network.name(route.from)} to ${Network.name(route.to)}")
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
            append("${Network.name(route.from)} to ${Network.name(route.to)}.")
            if (following != null) append(" Next one is ${clock(following)}.")
        }
        val builder = Notification.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("The ${clock(dep)} from ${Network.name(route.from)}")
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
                Notification.Action.Builder(null, "I'll take the ${clock(following)}", broadcast(context, ACTION_NEXT, route, dep, rule)).build(),
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
                updatePrefs(context) { it.copy(reminder = null) }
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
