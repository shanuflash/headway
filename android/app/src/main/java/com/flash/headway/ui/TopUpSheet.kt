package com.flash.headway.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flash.headway.app.NcmcReader
import com.flash.headway.app.TopUp
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

private sealed interface Read {
    data object Waiting : Read
    data object Reading : Read
    data class Done(val paise: Long, val at: Long, val trips: List<NcmcReader.Trip> = emptyList()) : Read
    data class Failed(val message: String, val log: List<String>) : Read
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * The metro card: tap it on the phone to read its chip balance, and top it up.
 * While this sheet is open the app listens for the card (NFC reader mode).
 */
@Composable
fun TopUpSheet(onDismiss: () -> Unit) {
    val hw = LocalHw.current
    val context = LocalContext.current
    val nfc = remember { NfcAdapter.getDefaultAdapter(context) }
    val last = remember { TopUp.lastBalance(context) }
    var read by remember { mutableStateOf<Read>(if (last != null) Read.Done(last.first, last.second) else Read.Waiting) }
    var fresh by remember { mutableStateOf(false) }
    // Listen for one read at a time: a card left on the phone would otherwise be read again and again.
    var listening by remember { mutableStateOf(true) }

    DisposableEffect(nfc, listening) {
        val activity = context.activity()
        if (nfc != null && activity != null && listening) {
            nfc.enableReaderMode(
                activity,
                { tag ->
                    val iso = IsoDep.get(tag) ?: return@enableReaderMode
                    activity.runOnUiThread { read = Read.Reading }
                    val r = NcmcReader.read(iso)
                    activity.runOnUiThread {
                        listening = false
                        if (r.balancePaise != null) {
                            val now = System.currentTimeMillis()
                            TopUp.saveBalance(context, r.balancePaise, now)
                            read = Read.Done(r.balancePaise, now, r.trips)
                            fresh = true
                        } else {
                            read = Read.Failed(r.error ?: "Couldn't read the card.", r.log)
                        }
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                // Check less often whether the card is still there, so a resting card isn't re-read.
                android.os.Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) },
            )
        }
        onDispose { if (nfc != null && activity != null) runCatching { nfc.disableReaderMode(activity) } }
    }

    HwSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Metro card", style = HwType.headline, color = hw.text, modifier = Modifier.align(Alignment.CenterHorizontally))
            BalanceCard(read, fresh, listening, onReadAgain = {
                read = Read.Waiting
                listening = true
            }, nfcState = when {
                nfc == null -> NfcState.MISSING
                !nfc.isEnabled -> NfcState.OFF
                else -> NfcState.ON
            })
            val rows = buildList<@Composable () -> Unit> {
                if (TopUp.upiId(context) != null) {
                    add {
                        ListRow(onClick = { close { TopUp.payByUpi(context) } }) {
                            Icon(Icons.Rounded.QrCode2, null, tint = hw.tint, modifier = Modifier.size(22.dp))
                            RowText("Pay by UPI", "Any UPI app, no fee, then load it on the card in Airtel", modifier = Modifier.weight(1f))
                        }
                    }
                }
                add {
                    ListRow(onClick = { close { TopUp.openAirtel(context) } }) {
                        Icon(Icons.Rounded.CreditCard, null, tint = hw.tint, modifier = Modifier.size(22.dp))
                        RowText("Airtel Thanks", "Free, can write the balance to your card", modifier = Modifier.weight(1f))
                    }
                }
            }
            ListCard(rows, inset = 50.dp)
        }
    }
}

private enum class NfcState { ON, OFF, MISSING }

/** The card's recent taps, newest first: where, when, and the fare taken. */
@Composable
private fun TripList(trips: List<NcmcReader.Trip>) {
    val hw = LocalHw.current
    val today = java.time.LocalDate.now(com.flash.headway.engine.IST)
    val dayFormat = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.US)
    Column(
        Modifier.padding(top = 12.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Recent trips", style = HwType.subheadStrong, color = hw.text)
        trips.forEach { t ->
            val date = java.time.Instant.ofEpochMilli(t.at).atZone(com.flash.headway.engine.IST).toLocalDate()
            val day = when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(dayFormat)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (t.station?.let { com.flash.headway.data.Network.name(it) } ?: "Unknown station") + when (t.kind) {
                            NcmcReader.Kind.ENTRY -> " · in"
                            NcmcReader.Kind.EXIT -> " · out"
                            NcmcReader.Kind.PENALTY -> " · penalty"
                            NcmcReader.Kind.OTHER -> ""
                        },
                        style = HwType.subhead,
                        color = hw.text,
                    )
                    Text("$day, ${clock(t.at)}", style = HwType.footnote, color = hw.sub)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (t.farePaise > 0) Text("−₹" + "%.2f".format(t.farePaise / 100.0), style = HwType.subheadStrong, color = hw.text)
                    Text("₹" + "%.2f".format(t.balanceAfterPaise / 100.0) + " left", style = HwType.footnote, color = hw.sub)
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(read: Read, fresh: Boolean, listening: Boolean, onReadAgain: () -> Unit, nfcState: NfcState) {
    val hw = LocalHw.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showLog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedContent(targetState = read, label = "balance") { r ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (r) {
                    is Read.Done -> {
                        Text(if (fresh) "Balance on the card" else "Last read", style = HwType.subheadStrong, color = hw.tint)
                        Text("₹" + "%.2f".format(r.paise / 100.0), style = HwType.big.copy(fontSize = 56.sp, lineHeight = 60.sp), color = hw.text)
                        Text("Read ${clock(r.at)}.", style = HwType.footnote, color = hw.sub)
                        if (r.trips.isNotEmpty()) {
                            TripList(r.trips)
                        } else if (fresh) {
                            Text("No trips on the card yet.", style = HwType.footnote, color = hw.sub, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Read.Reading -> {
                        Text("Reading…", style = HwType.subheadStrong, color = hw.tint)
                        Text("Keep the card still", style = HwType.cardTitle, color = hw.text)
                    }
                    is Read.Failed -> {
                        Text("Couldn't read the card", style = HwType.subheadStrong, color = hw.warn)
                        Text(r.message, style = HwType.subhead, color = hw.text)
                    }
                    Read.Waiting -> {
                        Text("Check balance", style = HwType.subheadStrong, color = hw.tint)
                        Text("Hold your card to the back of the phone", style = HwType.cardTitle, color = hw.text)
                    }
                }
            }
        }
        when (nfcState) {
            NfcState.MISSING -> Text("This phone has no NFC, so it can't read the card.", style = HwType.footnote, color = hw.warn)
            NfcState.OFF -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NFC is off.", style = HwType.footnote, color = hw.warn, modifier = Modifier.weight(1f))
                Text(
                    "Turn on",
                    style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(hw.tint).press {
                        context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            NfcState.ON -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Contactless, null, tint = hw.sub, modifier = Modifier.size(16.dp))
                Text("Shows the balance on the chip, which is what the gates use.", style = HwType.footnote, color = hw.sub)
            }
        }
        if (!listening && nfcState == NfcState.ON) {
            Text(
                "Read again",
                style = HwType.subheadStrong,
                color = Color.White,
                modifier = Modifier.padding(top = 6.dp).clip(CircleShape).background(hw.tint).press(onClick = onReadAgain)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (read is Read.Failed) {
            Text(
                if (showLog) "Copy details" else "Show details",
                style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = hw.tint,
                modifier = Modifier.padding(top = 4.dp).press {
                    if (showLog) clipboard.setText(AnnotatedString(read.log.joinToString("\n"))) else showLog = true
                },
            )
            if (showLog) Text(read.log.joinToString("\n"), style = HwType.caption, color = hw.sub)
        }
    }
}
