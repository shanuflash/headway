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

/** Leave reminders and on-board alerts. Alarms fire through [AlertReceiver], even with the app closed. */
object Alerts {
    private const val CHANNEL_REMINDERS = "reminders"
    private const val CHANNEL_TRIP = "trip"
    private const val ID_REMINDER = 1
    private const val ID_TRIP = 2
    private const val ID_RIDE_ALERT_BASE = 100
    private const val MAX_LEGS = 6

    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_ID = "id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Leave reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When to leave for your train, and when your stop is next"
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRIP, "Current trip", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A live countdown while you're on board"
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // ---------- leave reminder ----------

    fun scheduleReminder(context: Context, at: Long, title: String, body: String) {
        schedule(context, ID_REMINDER, at, title, body)
    }

    fun cancelReminder(context: Context) = cancel(context, ID_REMINDER)

    // ---------- ride ----------

    /** Posts an ongoing countdown to arrival and schedules an alert before each change and the final stop. */
    fun startRide(context: Context, ride: Ride, alertBeforeStops: Boolean) {
        endRide(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (canNotify(context)) {
            val notification = Notification.Builder(context, CHANNEL_TRIP)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("To ${Network.name(ride.to)}")
                .setContentText("Arrives ${clock(ride.arr)}")
                .setWhen(ride.arr)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setContentIntent(openApp(context))
                .setTimeoutAfter((ride.arr - System.currentTimeMillis() + 3 * 60_000L).coerceAtLeast(60_000L))
                .build()
            nm.notify(ID_TRIP, notification)
        }
        if (!alertBeforeStops) return
        ride.legs.take(MAX_LEGS).forEachIndexed { i, leg ->
            val arrive = leg.stops.last().at
            val final = i == ride.legs.lastIndex
            schedule(
                context,
                ID_RIDE_ALERT_BASE + i,
                arrive - 150_000L,
                if (final) "Your stop is next" else "Change coming up",
                "${Network.name(leg.to)} at ${clock(arrive)}",
            )
        }
    }

    fun endRide(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_TRIP)
        for (i in 0 until MAX_LEGS) cancel(context, ID_RIDE_ALERT_BASE + i)
    }

    // ---------- plumbing ----------

    private fun schedule(context: Context, id: Int, at: Long, title: String, body: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, id, title, body)
        val time = at.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }

    private fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(context, id, "", ""))
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    private fun alarmIntent(context: Context, id: Int, title: String, body: String): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_BODY, body)
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun show(context: Context, id: Int, title: String, body: String) {
        if (!canNotify(context)) return
        val notification = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alerts.show(
            context,
            intent.getIntExtra(Alerts.EXTRA_ID, 1),
            intent.getStringExtra(Alerts.EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(Alerts.EXTRA_BODY).orEmpty(),
        )
    }
}
