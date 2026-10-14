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
import com.flash.headway.engine.Schedule
import com.flash.headway.ui.clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The train countdown: a live notification that counts down to the moment the train leaves,
 * and works with the app closed. It counts to departure only; no walking-time offset.
 */
object Alerts {
    private const val CHANNEL_COUNTDOWN = "countdown"
    private const val ID_COUNTDOWN = 2

    const val ACTION_FIRE = "com.flash.headway.FIRE"
    const val ACTION_NEXT = "com.flash.headway.NEXT"
    const val ACTION_STOP = "com.flash.headway.STOP"
    private const val EXTRA_FROM = "from"
    private const val EXTRA_TO = "to"
    private const val EXTRA_DEP = "dep"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The old "time to set off" channel belonged to the walking-time alarm, which is gone.
        nm.deleteNotificationChannel("reminders")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COUNTDOWN, "Train countdown", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A live countdown to the train you're catching"
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun startCountdown(context: Context, route: SavedRoute, dep: Long) {
        // Clear any set-off alarm an older version scheduled.
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_FIRE, null, 0))
        val following = Schedule().routeDepartures(route.from, route.to, dep + 1, 1).firstOrNull()
        postCountdown(context, route, dep, following?.dep)
    }

    fun stopCountdown(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_FIRE, null, 0))
        context.getSystemService(NotificationManager::class.java).cancel(ID_COUNTDOWN)
    }

    private fun postCountdown(context: Context, route: SavedRoute, dep: Long, following: Long?) {
        if (!canNotify(context)) return
        val text = buildString {
            append("${Network.name(route.from)} to ${Network.name(route.to)}.")
            if (following != null) append(" Next one is ${clock(following)}.")
        }
        val builder = Notification.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("The ${clock(dep)} from ${Network.name(route.from)}")
            .setContentText(text)
            .setWhen(dep)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setTimeoutAfter((dep - System.currentTimeMillis() + 60_000L).coerceAtLeast(60_000L))
        if (following != null) {
            builder.addAction(
                Notification.Action.Builder(null, "I'll take the ${clock(following)}", broadcast(context, ACTION_NEXT, route, dep)).build(),
            )
        }
        builder.addAction(Notification.Action.Builder(null, "Stop", broadcast(context, ACTION_STOP, route, dep)).build())
        context.getSystemService(NotificationManager::class.java).notify(ID_COUNTDOWN, builder.build())
    }

    private fun broadcast(context: Context, action: String, route: SavedRoute?, dep: Long): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).setAction(action)
        if (route != null) intent.putExtra(EXTRA_FROM, route.from).putExtra(EXTRA_TO, route.to)
        intent.putExtra(EXTRA_DEP, dep)
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
                val following = Schedule().routeDepartures(route.from, route.to, dep + 1, 1).firstOrNull() ?: return
                Alerts.startCountdown(context, route, following.dep)
                updatePrefs(context) { it.copy(reminder = Reminder(route.id, following.dep)) }
            }
            // ACTION_FIRE came from the removed set-off alarm; nothing to do.
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
