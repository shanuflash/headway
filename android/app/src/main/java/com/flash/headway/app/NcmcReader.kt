package com.flash.headway.app

import android.nfc.tech.IsoDep
import java.security.SecureRandom
import java.util.Calendar

/**
 * Reads the chip ("offline") balance of a RuPay NCMC card, read-only.
 *
 * Steps follow the open NCMC Reader app (github.com/w0qs1/NCMC_Reader_App, MIT), which is based on a
 * reverse-engineered NCMC spec: select the RuPay application, then GET PROCESSING OPTIONS with fixed
 * terminal data; the reply's DF33 service data carries the balance in BCD paise at bytes 23..28.
 * Like any tap at a reader, GPO bumps the card's transaction counter; no money moves.
 * Trips come from the card's transaction log (the NCMC common service area after FF01); their
 * times count minutes from the card's effective date (tag 5F25). The card number is never kept or logged.
 */
object NcmcReader {
    class Trip(val at: Long, val station: String?, val kind: Kind, val farePaise: Long, val balanceAfterPaise: Long)

    enum class Kind { ENTRY, EXIT, PENALTY, OTHER }

    class Result(val balancePaise: Long?, val trips: List<Trip>, val error: String?, val log: List<String>)

    /** Chennai Metro (acquirer 0x0B, operator 0x177D): reader ID >> 12 to Headway's station id.
     *  From the community list github.com/w0qs1/NCMC_IDs. */
    private const val CMRL = 0x0B177D
    private val CMRL_STATIONS = mapOf(
        0x001 to "airport", 0x002 to "meenambakkam", 0x003 to "nanganallur-road", 0x004 to "alandur",
        0x005 to "guindy", 0x006 to "little-mount", 0x007 to "saidapet", 0x008 to "nandanam",
        0x009 to "teynampet", 0x00A to "agdms", 0x00B to "thousand-lights", 0x00C to "lic",
        0x00D to "government-estate", 0x00E to "central", 0x00F to "high-court", 0x010 to "mannadi",
        0x011 to "washermenpet", 0x012 to "st-thomas-mount", 0x013 to "ekkattuthangal", 0x014 to "ashok-nagar",
        0x015 to "vadapalani", 0x016 to "arumbakkam", 0x017 to "cmbt", 0x018 to "koyambedu",
        0x019 to "thirumangalam", 0x01A to "anna-nagar-tower", 0x01B to "anna-nagar-east", 0x01C to "shenoy-nagar",
        0x01D to "pachaiyappas-college", 0x01E to "kilpauk", 0x01F to "nehru-park", 0x020 to "egmore",
        0x021 to "theagaraya-college", 0x022 to "tondiarpet", 0x023 to "new-washermenpet", 0x024 to "tollgate",
        0x025 to "kaladipet", 0x026 to "tiruvottiyur", 0x027 to "wimco-nagar", 0x028 to "wimco-nagar-depot",
        0x029 to "tiruvottiyur-theradi", 0x02A to "porur-junction", 0x02B to "porur-bypass", 0x02C to "poonamallee-bypass",
        0x02D to "poonamallee", 0x02E to "mullaithottam", 0x02F to "karayanchavadi", 0x030 to "kumananchavadi",
        0x031 to "kattupakkam", 0x032 to "iyyappanthangal", 0x033 to "thelliyaragaram",
    )

    private val PPSE = hex("325041592E5359532E4444463031") // 2PAY.SYS.DDF01
    private val RUPAY_AID = hex("A0000005241010")

    fun read(iso: IsoDep): Result {
        val log = mutableListOf<String>()
        try {
            if (!iso.isConnected) iso.connect()
            iso.timeout = 5000

            val ppse = send(iso, select(PPSE))
            var logSfi = 16
            var logRecords = 10
            log += "SELECT PPSE sw=${sw(ppse)}"

            val app = send(iso, select(RUPAY_AID))
            log += "SELECT RuPay sw=${sw(app)} len=${app.size - 2}"
            if (!ok(app)) return Result(null, emptyList(), "This isn't an NCMC card, or the card moved too soon.", log)
            findTag(app, 0x9F4D, 0, app.size - 2)?.takeIf { it.size >= 2 }?.let {
                logSfi = it[0].toInt() and 0xFF
                logRecords = it[1].toInt() and 0xFF
            }

            val gpo = send(iso, gpoCommand())
            log += "GPO sw=${sw(gpo)} len=${gpo.size - 2}"
            if (!ok(gpo)) return Result(null, emptyList(), "The card didn't answer the balance request.", log)

            val df33 = findTag(gpo, 0xDF33, 0, gpo.size - 2)
            if (df33 == null) {
                log += "DF33 missing"
                return Result(null, emptyList(), "This card doesn't share its balance this way.", log)
            }
            log += "DF33 len=${df33.size} data=${df33.toHex()}"
            val paise = bcd(df33, 23, 29)
                ?: return Result(null, emptyList(), "Couldn't make sense of the balance on the card.", log)
            // Trips are a bonus: a failure here still returns the balance.
            val trips = runCatching { readTrips(iso, gpo, logSfi, logRecords, log) }
                .onFailure { log += "trips error ${it.javaClass.simpleName}" }
                .getOrDefault(emptyList())
            return Result(paise, trips, null, log)
        } catch (e: Exception) {
            log += "error ${e.javaClass.simpleName}: ${e.message}"
            return Result(null, emptyList(), "Lost the card. Hold it still against the phone and try again.", log)
        } finally {
            runCatching { iso.close() }
        }
    }

    private class Raw(val minutes: Int, val seq: Int, val operator: Int, val terminal: Int, val status: Int, val amount: Int, val balBefore: Int)

    /** The newest trips first, at most 10. */
    private fun readTrips(iso: IsoDep, gpo: ByteArray, logSfi: Int, logRecords: Int, log: MutableList<String>): List<Trip> {
        val effective = effectiveDate(iso, gpo, log) ?: return emptyList()
        val raws = mutableListOf<Raw>()
        for (rec in 1..logRecords.coerceIn(1, 30)) {
            val r = send(iso, readRecord(logSfi, rec))
            if (!ok(r)) {
                log += "log record $rec sw=${sw(r)}"
                break
            }
            val csa = commonServiceArea(r, r.size - 2) ?: continue
            for (k in 0 until 4) {
                val o = 21 + k * 17
                val minutes = u24(csa, o + 6)
                val seq = u16(csa, o + 9)
                val terminal = u24(csa, o + 3)
                if (minutes == 0 && seq == 0 && terminal == 0) continue
                val balStatus = u24(csa, o + 13)
                raws += Raw(
                    minutes = minutes,
                    seq = seq,
                    operator = ((csa[o].toInt() and 0xFF) shl 16) or u16(csa, o + 1),
                    terminal = terminal,
                    status = balStatus and 0x0F,
                    amount = u16(csa, o + 11),
                    balBefore = (balStatus ushr 4) and 0xFFFFF,
                )
            }
        }
        log += "trips raw=${raws.size}"
        return raws.distinctBy { Triple(it.minutes, it.seq, it.terminal) }
            .sortedWith(compareByDescending<Raw> { it.minutes }.thenByDescending { it.seq })
            .take(10)
            .map { r ->
                // Amounts are in tenths of a rupee.
                val fare = r.amount * 10L
                val kind = when (r.status) {
                    2 -> Kind.PENALTY
                    0, 1 -> if (fare > 0) Kind.EXIT else Kind.ENTRY
                    else -> Kind.OTHER
                }
                Trip(
                    at = effective + r.minutes * 60_000L,
                    station = if (r.operator == CMRL) CMRL_STATIONS[r.terminal shr 12] else null,
                    kind = kind,
                    farePaise = fare,
                    balanceAfterPaise = r.balBefore * 10L - fare,
                )
            }
    }

    /** The card's effective date (5F25, YYMMDD) as local midnight, from the records the GPO points to. */
    private fun effectiveDate(iso: IsoDep, gpo: ByteArray, log: MutableList<String>): Long? {
        val afl = findTag(gpo, 0x94, 0, gpo.size - 2)
            ?: findTag(gpo, 0x80, 0, gpo.size - 2)?.let { if (it.size > 2) it.copyOfRange(2, it.size) else null }
        if (afl == null) {
            log += "AFL missing"
            return null
        }
        var i = 0
        while (i + 3 < afl.size) {
            val sfi = (afl[i].toInt() and 0xFF) ushr 3
            val first = afl[i + 1].toInt() and 0xFF
            val last = afl[i + 2].toInt() and 0xFF
            for (rec in first..last) {
                val r = send(iso, readRecord(sfi, rec))
                if (!ok(r)) continue
                val d = findTag(r, 0x5F25, 0, r.size - 2) ?: continue
                if (d.size < 3) continue
                val yy = bcd(d, 0, 1) ?: continue
                val mm = bcd(d, 1, 2) ?: continue
                val dd = bcd(d, 2, 3) ?: continue
                log += "effective date found"
                return Calendar.getInstance().apply {
                    clear()
                    set((2000 + yy).toInt(), (mm - 1).toInt(), dd.toInt(), 0, 0, 0)
                }.timeInMillis
            }
            i += 4
        }
        log += "effective date missing"
        return null
    }

    /** The 96-byte NCMC common service area that follows the FF01 service id in a log record. */
    private fun commonServiceArea(r: ByteArray, end: Int): ByteArray? {
        for (i in 0..end - 2 - 96) {
            if ((r[i].toInt() and 0xFF) == 0xFF && r[i + 1].toInt() == 0x01) return r.copyOfRange(i + 2, i + 2 + 96)
        }
        return null
    }

    private fun readRecord(sfi: Int, rec: Int) = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), ((sfi shl 3) or 0x04).toByte(), 0x00)

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u24(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    /** Sends an APDU, following 61xx "more data" replies with GET RESPONSE. */
    private fun send(iso: IsoDep, apdu: ByteArray): ByteArray {
        var r = iso.transceive(apdu)
        if (r.size >= 2 && (r[r.size - 2].toInt() and 0xFF) == 0x61) {
            r = iso.transceive(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, r[r.size - 1]))
        }
        return r
    }

    private fun select(aid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)

    /** GET PROCESSING OPTIONS with the terminal data NCMC readers send (date, time, random number). */
    private fun gpoCommand(): ByteArray {
        val c = Calendar.getInstance()
        fun two(n: Int) = "%02d".format(n % 100)
        val date = hex(two(c.get(Calendar.YEAR)) + two(c.get(Calendar.MONTH) + 1) + two(c.get(Calendar.DAY_OF_MONTH)))
        val time = hex(two(c.get(Calendar.HOUR_OF_DAY)) + two(c.get(Calendar.MINUTE)) + two(c.get(Calendar.SECOND)))
        val random = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val pdol = hex("FF80F00001004000000000084800020743") + date + time + random + hex("FF013131323834353833")
        val data = byteArrayOf(0x83.toByte(), pdol.size.toByte()) + pdol
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, data.size.toByte()) + data + byteArrayOf(0x00)
    }

    /** Finds a BER-TLV tag's value, looking inside constructed tags. */
    private fun findTag(data: ByteArray, want: Int, from: Int, to: Int): ByteArray? {
        var i = from
        while (i < to) {
            var b = data[i].toInt() and 0xFF
            if (b == 0x00 || b == 0xFF) {
                i++
                continue
            }
            var tag = b
            val constructed = (b and 0x20) != 0
            i++
            if ((b and 0x1F) == 0x1F) {
                do {
                    if (i >= to) return null
                    b = data[i].toInt() and 0xFF
                    tag = (tag shl 8) or b
                    i++
                } while ((b and 0x80) != 0)
            }
            if (i >= to) return null
            var len = data[i].toInt() and 0xFF
            i++
            if ((len and 0x80) != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) {
                    if (i >= to) return null
                    len = (len shl 8) or (data[i].toInt() and 0xFF)
                    i++
                }
            }
            if (i + len > to) return null
            if (tag == want) return data.copyOfRange(i, i + len)
            if (constructed) findTag(data, want, i, i + len)?.let { return it }
            i += len
        }
        return null
    }

    /** BCD digits in [from, to) as a number, or null if any nibble isn't a digit. */
    private fun bcd(b: ByteArray, from: Int, to: Int): Long? {
        if (b.size < to) return null
        var v = 0L
        for (i in from until to) {
            val hi = (b[i].toInt() shr 4) and 0x0F
            val lo = b[i].toInt() and 0x0F
            if (hi > 9 || lo > 9) return null
            v = v * 100 + hi * 10 + lo
        }
        return v
    }

    private fun ok(r: ByteArray) = r.size >= 2 && (r[r.size - 2].toInt() and 0xFF) == 0x90 && r[r.size - 1].toInt() == 0

    private fun sw(r: ByteArray) = if (r.size >= 2) "%02X%02X".format(r[r.size - 2], r[r.size - 1]) else "none"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
