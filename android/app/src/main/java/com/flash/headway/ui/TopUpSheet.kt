package com.flash.headway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flash.headway.app.TopUp
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/** Shown when the card's UPI ID is saved: pick UPI or the Airtel screen. */
@Composable
fun TopUpSheet(onDismiss: () -> Unit) {
    val hw = LocalHw.current
    val context = LocalContext.current
    HwSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Top up metro card", style = HwType.headline, color = hw.text, modifier = Modifier.align(Alignment.CenterHorizontally))
            ListCard(
                listOf<@Composable () -> Unit>(
                    {
                        ListRow(onClick = { close { TopUp.payByUpi(context) } }) {
                            Icon(Icons.Rounded.QrCode2, null, tint = hw.tint, modifier = Modifier.size(22.dp))
                            RowText("Pay by UPI", "Any UPI app, no fee, then load it on the card in Airtel", modifier = Modifier.weight(1f))
                        }
                    },
                    {
                        ListRow(onClick = { close { TopUp.openAirtel(context) } }) {
                            Icon(Icons.Rounded.CreditCard, null, tint = hw.tint, modifier = Modifier.size(22.dp))
                            RowText("Airtel Thanks", "Free, can write the balance to your card", modifier = Modifier.weight(1f))
                        }
                    },
                ),
                inset = 50.dp,
            )
        }
    }
}
