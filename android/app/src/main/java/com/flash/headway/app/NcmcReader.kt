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
 * The card number is never kept or logged.
 */
object NcmcReader {
    class Result(val balancePaise: Long?, val error: String?, val log: List<String>)

    private val PPSE = hex("325041592E5359532E4444463031") // 2PAY.SYS.DDF01
    private val RUPAY_AID = hex("A0000005241010")

    fun read(iso: IsoDep): Result {
        val log = mutableListOf<String>()
        try {
            if (!iso.isConnected) iso.connect()
            iso.timeout = 5000

            val ppse = send(iso, select(PPSE))
            log += "SELECT PPSE sw=${sw(ppse)}"

            val app = send(iso, select(RUPAY_AID))
            log += "SELECT RuPay sw=${sw(app)} len=${app.size - 2}"
            if (!ok(app)) return Result(null, "This isn't an NCMC card, or the card moved too soon.", log)

            val gpo = send(iso, gpoCommand())
            log += "GPO sw=${sw(gpo)} len=${gpo.size - 2}"
            if (!ok(gpo)) return Result(null, "The card didn't answer the balance request.", log)

            val df33 = findTag(gpo, 0xDF33, 0, gpo.size - 2)
            if (df33 == null) {
                log += "DF33 missing"
                return Result(null, "This card doesn't share its balance this way.", log)
            }
            log += "DF33 len=${df33.size} data=${df33.toHex()}"
            val paise = bcd(df33, 23, 29)
                ?: return Result(null, "Couldn't make sense of the balance on the card.", log)
            return Result(paise, null, log)
        } catch (e: Exception) {
            log += "error ${e.javaClass.simpleName}: ${e.message}"
            return Result(null, "Lost the card. Hold it still against the phone and try again.", log)
        } finally {
            runCatching { iso.close() }
        }
    }

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
