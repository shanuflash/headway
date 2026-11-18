package com.flash.headway.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Metro card top-up, two ways:
 * - Airtel Payments Bank's own card screen: free (the menu route goes through BBPS at ₹1.18) and
 *   it can write the new balance to the card. The app's menus hide it, so it opens by deep link.
 * - UPI to the card's own UPI ID (from the "Scan to recharge" QR on the card), from any UPI app.
 *   That only pays in: the money reaches the chip when the Airtel screen writes it over NFC.
 *   The ID identifies the rider's card, so it is kept on the phone only, never in the code.
 */
object TopUp {
    private const val DEEP_LINK = "myairtel://apb_cards?card=ncmc"
    private const val SHORT_LINK = "https://i.airtel.in/AIRBNK/transit-card"
    private const val STORE = "topup"
    private const val KEY_UPI = "upi"
    private const val KEY_PAID = "upi_paid_at"

    /** How long Routes offers to load a UPI top-up onto the card. */
    private const val LOAD_WINDOW = 30 * 60_000L

    /** Same shape as the IDs on the card QRs, e.g. ncmc.1234p10000…@mairtel. */
    private val UPI_ID = Regex("^[A-Za-z0-9._-]{3,}@[A-Za-z0-9]{2,}$")

    fun upiId(context: Context): String? =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY_UPI, null)?.takeIf { it.isNotBlank() }

    /** Saves the card's UPI ID; returns false if it doesn't look like one. Blank clears it. */
    fun setUpiId(context: Context, id: String): Boolean {
        val clean = id.trim()
        if (clean.isNotEmpty() && !UPI_ID.matches(clean)) return false
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY_UPI, clean.ifEmpty { null }).apply()
        return true
    }

    /** The UPI ID from a card's "Scan to recharge" QR (upi://pay?pa=…), or the text itself if it already is one. */
    fun upiIdFromQr(raw: String?): String? {
        val text = raw?.trim() ?: return null
        val id = if (text.startsWith("upi:", ignoreCase = true)) Uri.parse(text).getQueryParameter("pa") else text
        return id?.trim()?.takeIf { UPI_ID.matches(it) }
    }

    fun openAirtel(context: Context) {
        for (url in listOf(DEEP_LINK, SHORT_LINK)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Airtel Thanks isn't installed; try the web link.
            }
        }
        Toast.makeText(context, "Airtel Thanks isn't installed", Toast.LENGTH_LONG).show()
    }

    /** Opens the UPI app picker with the card's UPI ID filled in; the rider enters the amount. */
    fun payByUpi(context: Context) {
        val id = upiId(context)
        if (id == null) {
            Toast.makeText(context, "Add the card's UPI ID in Settings", Toast.LENGTH_LONG).show()
            openAirtel(context)
            return
        }
        // The ID is validated to safe characters; '@' stays raw because some UPI apps reject %40.
        val uri = Uri.parse("upi://pay?pa=$id&pn=Airtel%20NCMC%20Topup&mc=7412&cu=INR")
        val pay = Intent(Intent.ACTION_VIEW, uri)
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putLong(KEY_PAID, System.currentTimeMillis()).apply()
        try {
            context.startActivity(Intent.createChooser(pay, "Top up with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No UPI app found", Toast.LENGTH_LONG).show()
        }
    }

    /** True for a while after a UPI top-up, until the rider loads it onto the card or dismisses it. */
    fun loadPending(context: Context): Boolean {
        val at = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getLong(KEY_PAID, 0L)
        return at > 0 && System.currentTimeMillis() - at < LOAD_WINDOW
    }

    fun clearLoadPending(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(KEY_PAID).apply()
    }

    /** The last chip balance read over NFC, as (paise, read at). */
    fun lastBalance(context: Context): Pair<Long, Long>? {
        val p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val at = p.getLong("balance_at", 0L)
        return if (at > 0) p.getLong("balance", 0L) to at else null
    }

    fun saveBalance(context: Context, paise: Long, at: Long) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putLong("balance", paise).putLong("balance_at", at).apply()
        // A read after a UPI top-up means it has been loaded (or checked); stop nagging.
        clearLoadPending(context)
    }

    /** The shortcut-free default: UPI once its ID is saved, else the Airtel screen. */
    fun openDefault(context: Context) = if (upiId(context) != null) payByUpi(context) else openAirtel(context)
}

/** No UI: opens a top-up and closes. Used by the long-press shortcuts and the tile. */
class TopUpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.getStringExtra(EXTRA_VIA)) {
            "upi" -> TopUp.payByUpi(this)
            "airtel" -> TopUp.openAirtel(this)
            else -> TopUp.openDefault(this)
        }
        finish()
    }

    companion object {
        const val EXTRA_VIA = "via"
    }
}

/** Quick Settings tile: one tap to the metro card top-up. */
class TopUpTile : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, TopUpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
