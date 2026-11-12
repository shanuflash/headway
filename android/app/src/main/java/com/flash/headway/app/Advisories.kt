package com.flash.headway.app

import android.content.Context
import com.flash.headway.data.DayType
import com.flash.headway.data.Line
import com.flash.headway.engine.Adjustment
import com.flash.headway.engine.Feed
import com.flash.headway.engine.Notice
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import com.flash.headway.engine.IST
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The advisory feed: CMRL service notices, extended hours and holiday timetables. A scheduled job
 * keeps advisories.json on the main branch current (tools/advisories.mjs); the app downloads it
 * and keeps the last good copy, so it works offline and the widget can read it any time.
 */
object Advisories {
    const val FEED_URL = "https://raw.githubusercontent.com/shanuflash/headway/main/advisories.json"
    private const val CACHE = "advisories.json"

    private fun file(context: Context) = File(context.filesDir, CACHE)

    private var memo: Pair<Long, Feed?>? = null

    /** The last feed downloaded, or null before the first. Parsed once per download, not per call. */
    @Synchronized
    fun cached(context: Context): Feed? {
        val f = file(context)
        val stamp = f.lastModified()
        memo?.takeIf { it.first == stamp && stamp != 0L }?.let { return it.second }
        val feed = runCatching { parse(f.readText()) }.getOrNull()
        memo = stamp to feed
        return feed
    }

    /** Downloads the feed and caches it. Null when offline or it couldn't be read (the cache stays). */
    suspend fun refresh(context: Context): Feed? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(FEED_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            try {
                if (conn.responseCode != 200) return@withContext null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val feed = parse(text)
                // Write then rename, so the widget never reads half a file.
                val tmp = File(context.filesDir, "$CACHE.tmp")
                tmp.writeText(text)
                tmp.renameTo(file(context))
                feed
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun parse(text: String): Feed {
        val o = JSONObject(text)
        val holidays = o.optJSONObject("holidays")?.let { h ->
            h.keys().asSequence().mapNotNull { k -> runCatching { LocalDate.parse(k) to h.getString(k) }.getOrNull() }.toMap()
        }.orEmpty()
        val notices = o.optJSONArray("notices").objects().mapNotNull { n ->
            val title = n.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // A time that's there but can't be read would leave the notice up forever: skip it.
            if (listOf("from", "to").any { k -> n.optString(k).isNotBlank() && time(n.optString(k)) == null }) return@mapNotNull null
            Notice(
                id = n.optString("id", title),
                level = n.optString("level", "info"),
                title = title,
                body = n.optString("body").takeIf { it.isNotBlank() },
                link = n.optString("link").takeIf { it.startsWith("https://") },
                from = time(n.optString("from")),
                to = time(n.optString("to")),
            )
        }
        val adjustments = o.optJSONArray("adjustments").objects().mapNotNull { a ->
            Adjustment(
                date = a.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                dayType = a.optString("dayType").takeIf { it.isNotBlank() }?.let { dayType(it) },
                extendUntil = a.optString("extendUntil").takeIf { it.isNotBlank() }?.let(::minutes),
                headway = a.optInt("headway", 0).takeIf { it > 0 },
                from = time(a.optString("from")),
                to = time(a.optString("to")),
                delay = a.optInt("delay", 0).takeIf { it != 0 },
                // "inter" is the inter-corridor service, which the engine runs as Green Line trains;
                // a list with nothing readable left means every line, not none.
                lines = a.optJSONArray("lines")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> lineOf(arr.optString(i)) }.toSet().takeIf { it.isNotEmpty() }
                },
                reason = a.optString("reason").takeIf { it.isNotBlank() },
            ).takeIf { it.dayType != null || it.extendUntil != null || it.delay != null }
        }
        return Feed(updated = o.optString("updated").takeIf { it.isNotBlank() }, holidays = holidays, notices = notices, adjustments = adjustments)
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /**
     * A time in the feed: "2026-11-15T18:00:00+05:30", "…Z", or without an offset ("2026-11-15T18:00",
     * "2026-11-15 18:00", "2026-11-15"), which is India time. Null when it can't be read.
     */
    internal fun time(s: String?): Long? {
        val t = s?.trim()?.replace(' ', 'T')?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(t).toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(t).atZone(IST).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(t).atStartOfDay(IST).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun dayType(s: String): DayType? = when (s.trim().lowercase()) {
        "holiday" -> DayType.SUNDAY
        else -> runCatching { DayType.valueOf(s.trim().uppercase()) }.getOrNull()
    }

    private fun lineOf(s: String): Line? = when (s.trim().lowercase()) {
        "inter", "inter-corridor" -> Line.GREEN
        else -> runCatching { Line.valueOf(s.trim().uppercase()) }.getOrNull()
    }

    private fun minutes(hm: String): Int? = hm.split(':').takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }
}
