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
 * Ways to top up a Singara Chennai / NCMC card. Only links that were checked are included;
 * providers without a verifiable link carry steps instead.
 */
data class TopUpProvider(
    val id: String,
    val name: String,
    val detail: String,
    val fee: String,
    /** Whether the new balance lands on the card, or how to get it there. */
    val balance: String,
    /** Opens the provider's top-up screen; null when there is no verified link. */
    val link: String? = null,
    /** Used when [link] has no app to handle it (for example, the app is not installed). */
    val fallback: String? = null,
)

object TopUp {
    val providers = listOf(
        TopUpProvider(
            id = "airtel",
            name = "Airtel Payments Bank",
            detail = "The card top-up screen in Airtel Thanks, which the app's menus don't show.",
            fee = "Free",
            balance = "Can write the new balance to the card from your phone",
            link = "myairtel://apb_cards?card=ncmc",
            fallback = "https://i.airtel.in/AIRBNK/transit-card",
        ),
        TopUpProvider(
            id = "sbi",
            name = "SBI Singara Chennai card",
            detail = "SBI's prepaid card portal.",
            fee = "Free (SBI lists no reload fee)",
            balance = "Update the balance at a Metro station before you travel",
            link = "https://prepaid.sbi.bank.in",
        ),
        TopUpProvider(
            id = "airtel-bbps",
            name = "Airtel Thanks, NCMC recharge",
            detail = "The usual recharge in the app's menus, through BBPS.",
            fee = "₹1.18",
            balance = "Update the balance at a Metro station before you travel",
        ),
        TopUpProvider(
            id = "bbps",
            name = "PhonePe, BHIM UPI or SBI Unipay",
            detail = "Recharge & bills, then NCMC recharge, then your card's bank (BBPS).",
            fee = "Set by the app; check before paying",
            balance = "Update the balance at a Metro station before you travel",
        ),
        TopUpProvider(
            id = "station",
            name = "Metro station counter",
            detail = "Cash, UPI or card at any CMRL ticket counter.",
            fee = "Free",
            balance = "Loaded on the card right away",
        ),
    )

    private const val STORE = "topup"
    private const val KEY = "provider"

    /** The provider the shortcut and tile open. Kept in plain SharedPreferences so they can read it instantly. */
    fun chosen(context: Context): TopUpProvider {
        val id = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, "airtel")
        return providers.firstOrNull { it.id == id && it.link != null } ?: providers.first()
    }

    fun choose(context: Context, id: String) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
    }

    /** Opens the provider's link, falling back to its web link, then to a hint. */
    fun open(context: Context, p: TopUpProvider) {
        for (url in listOfNotNull(p.link, p.fallback)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next link.
            }
        }
        Toast.makeText(context, "Couldn't open ${p.name}. Is the app installed?", Toast.LENGTH_LONG).show()
    }
}

/** No UI: opens the chosen provider and closes. Used by the long-press shortcut and the tile. */
class TopUpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TopUp.open(this, TopUp.chosen(this))
        finish()
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
