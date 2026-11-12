package com.flash.headway.app

import android.content.Context
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.serviceDate
import com.flash.headway.widget.Widgets
import java.util.concurrent.Executors

/**
 * The timetable for work outside the app's screens: the widget and the countdown notification.
 *
 * Building a [Schedule] and its two-day connection windows is heavy, so this one is kept and
 * reused until the service day, the rider's timetable override or the advisory feed changes.
 * [Schedule] isn't thread-safe, so it's only ever used inside [with], one caller at a time; and
 * that work belongs on [post]'s background thread, never the main thread.
 */
object SharedEngine {
    private var schedule: Schedule? = null
    private var key: Any? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "headway-background").apply { isDaemon = true } }

    /** Runs [block] with the current schedule (the rider's override and the cached feed applied). */
    @Synchronized
    fun <T> with(context: Context, block: (Schedule) -> T): T {
        val override = Widgets.override(context)
        val feed = Advisories.cached(context)
        val k = Triple(serviceDate(Clock.now()), override, feed?.updated)
        val s = schedule?.takeIf { key == k } ?: Schedule(override, feed).also {
            schedule = it
            key = k
        }
        return block(s)
    }

    /** Runs [task] on the background thread, in order with everything else posted here. */
    fun post(task: () -> Unit) {
        worker.execute { runCatching(task) }
    }
}
