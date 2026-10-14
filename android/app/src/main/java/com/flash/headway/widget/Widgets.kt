package com.flash.headway.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.flash.headway.MainActivity
import com.flash.headway.R
import com.flash.headway.app.Clock
import com.flash.headway.app.Prefs
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.routeStatus
import com.flash.headway.data.DayType
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.engine.DayOverride
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.routeGroup
import com.flash.headway.ui.clock
import com.flash.headway.ui.mins
import java.time.LocalDate

/**
 * The 1×1 widget: one number, the minutes to the next train you can catch on a saved route.
 * Widgets read a SharedPreferences mirror of the routes so they can render synchronously.
 */
object Widgets {
    private const val STORE = "widgets"
    const val ACTION_TICK = "com.flash.headway.WIDGET_TICK"

    private fun store(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Called by the app whenever preferences change. */
    fun mirror(context: Context, p: Prefs) {
        store(context).edit()
            .putString("routes", p.routes.joinToString(";") { "${it.from}>${it.to}" })
            .putBoolean("lastWarn", p.lastWarn)
            .putString("override", p.override?.let { "${it.date}|${it.type.name}" })
            .putLong("mock", p.mockOffset)
            .apply()
    }

    fun routes(context: Context): List<SavedRoute> =
        store(context).getString("routes", "").orEmpty().split(';').mapNotNull { part ->
            val bits = part.split('>')
            if (bits.size >= 2 && bits[0] in Network.stations && bits[1] in Network.stations) {
                SavedRoute(bits[0], bits[1])
            } else {
                null
            }
        }

    fun assign(context: Context, widgetId: Int, routeId: String, label: Boolean) {
        store(context).edit().putString("w_$widgetId", routeId).putBoolean("label_$widgetId", label).apply()
    }

    fun assigned(context: Context, widgetId: Int): Pair<String?, Boolean> =
        store(context).getString("w_$widgetId", null) to store(context).getBoolean("label_$widgetId", false)

    fun forget(context: Context, widgetId: Int) {
        store(context).edit().remove("w_$widgetId").remove("label_$widgetId").apply()
    }

    private fun override(context: Context): DayOverride? = runCatching {
        val (d, t) = store(context).getString("override", null)!!.split('|')
        DayOverride(LocalDate.parse(d), DayType.valueOf(t))
    }.getOrNull()

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, HeadwayWidget::class.java))
        if (ids.isEmpty()) return
        Clock.offset = store(context).getLong("mock", 0L)
        val schedule = Schedule(override(context))
        for (id in ids) manager.updateAppWidget(id, render(context, id, schedule))
        scheduleTick(context)
    }

    fun render(context: Context, widgetId: Int, schedule: Schedule = Schedule(override(context))): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_1x1)
        val routes = routes(context)
        val (assignedId, label) = assigned(context, widgetId)
        val route = routes.firstOrNull { it.id == assignedId } ?: routes.firstOrNull()
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, widgetId, route))

        fun big(text: String, sp: Float) {
            views.setTextViewText(R.id.widget_number, text)
            views.setTextViewTextSize(R.id.widget_number, TypedValue.COMPLEX_UNIT_SP, sp)
        }
        fun color(res: Int) = ContextCompat.getColor(context, res)

        if (route == null) {
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_neutral)
            views.setViewVisibility(R.id.widget_icon, View.GONE)
            big("+", 30f)
            views.setTextColor(R.id.widget_number, color(R.color.widget_text))
            views.setTextViewText(R.id.widget_label, "Add route")
            return views
        }

        val lastWarn = store(context).getBoolean("lastWarn", true)
        val status = routeStatus(schedule, route, Clock.now(), lastWarn, 1)
        val next = status.next
        val line = routeGroup(route.from, route.to)?.line ?: Line.BLUE
        val ring = when (line) {
            Line.BLUE -> R.drawable.widget_bg_blue
            Line.GREEN -> R.drawable.widget_bg_green
            Line.YELLOW -> R.drawable.widget_bg_yellow
        }
        val destination = Network.name(route.to)

        when {
            next == null -> {
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_neutral)
                views.setViewVisibility(R.id.widget_icon, View.GONE)
                big("–", 30f)
                views.setTextColor(R.id.widget_number, color(R.color.widget_text))
                views.setTextViewText(R.id.widget_label, "no trains")
            }
            status.closed -> {
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_neutral)
                views.setViewVisibility(R.id.widget_icon, View.VISIBLE)
                big(clock(next.dep), 19f)
                views.setTextColor(R.id.widget_number, color(R.color.widget_text))
                views.setTextViewText(R.id.widget_label, if (label) destination else "first")
            }
            else -> {
                val left = next.dep - Clock.now()
                val minutes = mins(left)
                val now = left < 30_000
                views.setViewVisibility(R.id.widget_icon, View.GONE)
                big(if (now) "Now" else minutes.toString(), if (now || minutes >= 100) 22f else 30f)
                if (status.late) {
                    views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_amber)
                    views.setTextColor(R.id.widget_number, color(R.color.widget_amber))
                    views.setTextViewText(R.id.widget_label, if (label) destination else "last")
                } else {
                    views.setInt(R.id.widget_root, "setBackgroundResource", ring)
                    views.setTextColor(R.id.widget_number, color(R.color.widget_text))
                    views.setTextViewText(R.id.widget_label, if (label) destination else "min")
                }
            }
        }
        return views
    }

    private fun openApp(context: Context, widgetId: Int, route: SavedRoute?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route?.id)
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Redraws on the next minute boundary. The alarm doesn't wake the phone, so with the screen
     * off it waits and fires as soon as the screen comes back on.
     */
    fun scheduleTick(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = tickIntent(context)
        // Next minute boundary on the app clock (which may be mocked), as a real alarm time.
        val next = (Clock.now() / MINUTE + 1) * MINUTE + 500 - Clock.offset
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExact(AlarmManager.RTC, next, pi)
        } else {
            am.setWindow(AlarmManager.RTC, next, 30_000L, pi)
        }
    }

    fun cancelTick(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }

    private fun tickIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, HeadwayWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class HeadwayWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_TICK) {
            Widgets.updateAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.updateAll(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) Widgets.forget(context, id)
    }

    override fun onDisabled(context: Context) {
        Widgets.cancelTick(context)
    }
}
