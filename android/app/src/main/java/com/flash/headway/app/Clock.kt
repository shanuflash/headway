package com.flash.headway.app

import com.flash.headway.engine.IST
import com.flash.headway.engine.serviceDate

/**
 * The app's notion of "now". Normally real time; with mock time on (Settings, for testing)
 * it runs shifted by a fixed offset, so the mocked clock keeps ticking.
 */
object Clock {
    @Volatile
    var offset: Long = 0L

    fun now(): Long = System.currentTimeMillis() + offset

    val mocked: Boolean get() = offset != 0L

    /** Offset that makes now read [minutes] past midnight IST on the current service day. */
    fun offsetFor(minutes: Int): Long {
        val real = System.currentTimeMillis()
        var day = serviceDate(real)
        // Times before 03:00 belong to the end of the service day, i.e. the next calendar date.
        if (minutes < 3 * 60) day = day.plusDays(1)
        val target = day.atStartOfDay(IST).toInstant().toEpochMilli() + minutes * 60_000L
        return target - real
    }
}

/** Presets offered in Settings: minutes past midnight, and their labels. */
val MOCK_CHOICES = listOf(8 * 60 to "8 AM", 18 * 60 to "6 PM", 23 * 60 + 30 to "11:30 PM")
