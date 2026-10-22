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
 * Metro card top-up through Airtel Payments Bank's own card screen: free (the menu route goes
 * through BBPS at ₹1.18) and it can write the new balance to the card. The app's menus hide it,
 * so it is opened by deep link; the SMS short link redirects to the same screen.
 */
object TopUp {
    private const val DEEP_LINK = "myairtel://apb_cards?card=ncmc"
    private const val SHORT_LINK = "https://i.airtel.in/AIRBNK/transit-card"

    fun open(context: Context) {
        for (url in listOf(DEEP_LINK, SHORT_LINK)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Airtel Thanks isn't installed; try the web link.
            }
        }
        Toast.makeText(context, "Install Airtel Thanks to top up your card", Toast.LENGTH_LONG).show()
    }
}

/** No UI: opens the top-up and closes. Used by the long-press shortcut and the tile. */
class TopUpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TopUp.open(this)
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
