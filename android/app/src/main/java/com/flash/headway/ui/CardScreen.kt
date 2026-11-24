package com.flash.headway.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flash.headway.app.NcmcReader
import com.flash.headway.app.TopUp
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private sealed interface Read {
    data object Waiting : Read
    data object Reading : Read
    data class Done(val paise: Long, val at: Long, val trips: List<NcmcReader.Trip> = emptyList()) : Read
    data class Failed(val message: String, val log: List<String>) : Read
}

private enum class NfcState { ON, OFF, MISSING }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun rupees(paise: Long) = "₹" + "%.2f".format(paise / 100.0)

/**
 * The Metro card tab: the chip balance and recent trips (read over NFC), ways to top up, and the
 * card's UPI ID. NFC reader mode is on only while this tab is showing, and stops after one read so
 * a card left on the phone is not read again and again.
 */
@Composable
fun ColumnScope.CardScreen(now: () -> Long) {
    val hw = LocalHw.current
    val context = LocalContext.current
    val nfc = remember { NfcAdapter.getDefaultAdapter(context) }
    val last = remember { TopUp.lastBalance(context) }
    var read by remember { mutableStateOf<Read>(if (last != null) Read.Done(last.first, last.second) else Read.Waiting) }
    var fresh by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(true) }
    var upi by remember { mutableStateOf(TopUp.upiId(context)) }

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
                            val at = System.currentTimeMillis()
                            TopUp.saveBalance(context, r.balancePaise, at)
                            read = Read.Done(r.balancePaise, at, r.trips)
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

    LargeTitle("Metro card")
    LoadCardBanner(now)

    BalanceCard(
        read = read,
        fresh = fresh,
        listening = listening,
        nfcState = when {
            nfc == null -> NfcState.MISSING
            !nfc.isEnabled -> NfcState.OFF
            else -> NfcState.ON
        },
        onReadAgain = {
            read = Read.Waiting
            listening = true
        },
    )
    val shown = read
    val trips = (shown as? Read.Done)?.trips.orEmpty()
    androidx.compose.animation.AnimatedVisibility(
        trips.isNotEmpty(),
        enter = androidx.compose.animation.expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + androidx.compose.animation.fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = androidx.compose.animation.shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + androidx.compose.animation.fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { TripList(trips) }
    }

    SectionTitle("Top up")
    ListCard(
        buildList<@Composable () -> Unit> {
            if (upi != null) {
                add {
                    ListRow(onClick = { TopUp.payByUpi(context) }) {
                        LeadIcon(Icons.Rounded.QrCode2)
                        RowText("Pay by UPI", "Any UPI app · No fee · Add to card in Airtel", modifier = Modifier.weight(1f))
                    }
                }
            }
            add {
                ListRow(onClick = { TopUp.openAirtel(context) }) {
                    LeadIcon(Icons.Rounded.CreditCard)
                    RowText("Airtel Thanks", "No fee · Adds the balance to the card", modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = hw.sub)
                }
            }
        },
    )

    SectionTitle("Card UPI ID")
    UpiIdCard(onSaved = { upi = it })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BalanceCard(read: Read, fresh: Boolean, listening: Boolean, nfcState: NfcState, onReadAgain: () -> Unit) {
    val hw = LocalHw.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showLog by remember { mutableStateOf(false) }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scheme = MaterialTheme.colorScheme
    val waiting = read is Read.Waiting || read is Read.Reading

    // The balance is this screen's hero: primary-container, extra-extra-large corners.
    HwCard(
        color = if (read is Read.Failed) hw.warnBg else scheme.primaryContainer,
        padding = 24.dp,
        gap = 8.dp,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        val on = if (read is Read.Failed) hw.onWarnBg else scheme.onPrimaryContainer
        AnimatedContent(targetState = read, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "balance") { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (r) {
                        is Read.Done -> {
                            Text(if (fresh) "Balance on the card" else "Last read", style = HwType.subheadStrong, color = on)
                            Text(rupees(r.paise), style = HwType.big.copy(fontSize = 64.sp, lineHeight = 68.sp), color = on)
                            Text(
                                "Read ${readDay(r.at)}${clock(r.at)}" + if (fresh && r.trips.isEmpty()) " · No trips on this card" else "",
                                style = HwType.subhead,
                                color = on,
                            )
                        }
                        Read.Reading -> {
                            Text("Reading…", style = HwType.subheadStrong, color = on)
                            Text("Keep the card still", style = HwType.cardTitle, color = on)
                        }
                        is Read.Failed -> {
                            Text("Couldn't read the card", style = HwType.subheadStrong, color = on)
                            Text(r.message, style = HwType.body, color = on)
                        }
                        Read.Waiting -> {
                            Text("Check balance", style = HwType.subheadStrong, color = on)
                            Text("Hold the card to the back of the phone", style = HwType.cardTitle, color = on)
                        }
                    }
                }
                // Expressive loading indicator while waiting for (or reading) the card.
                if (r is Read.Waiting || r is Read.Reading) {
                    if (nfcState == NfcState.ON) ContainedLoadingIndicator(Modifier.size(56.dp))
                }
            }
        }
        when (nfcState) {
            NfcState.MISSING -> Text("This phone doesn’t support NFC.", style = HwType.subhead, color = on)
            NfcState.OFF -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NFC is off.", style = HwType.subhead, color = on, modifier = Modifier.weight(1f))
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Turn on") }
            }
            NfcState.ON -> if (!waiting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Contactless, null, tint = on, modifier = Modifier.size(18.dp))
                Text("Reads the balance stored on the card.", style = HwType.footnote, color = on)
            }
        }
        if (!listening && nfcState == NfcState.ON) {
            FilledTonalButton(onClick = onReadAgain, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                Text("Read again", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (read is Read.Failed) {
            TextButton(
                onClick = { if (showLog) clipboard.setText(AnnotatedString(read.log.joinToString("\n"))) else showLog = true },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(contentColor = on),
            ) { Text(if (showLog) "Copy details" else "Show details") }
            if (showLog) Text(read.log.joinToString("\n"), style = HwType.caption, color = on)
        }
    }
}

/** The card's recent taps, newest first: where, when, and the fare taken. */
@Composable
private fun TripList(trips: List<NcmcReader.Trip>) {
    val hw = LocalHw.current
    val today = java.time.LocalDate.now(com.flash.headway.engine.IST)
    val dayFormat = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.US)
    SectionTitle("Recent trips")
    ListCard(
        trips.map { t ->
            @Composable {
                val date = java.time.Instant.ofEpochMilli(t.at).atZone(com.flash.headway.engine.IST).toLocalDate()
                val day = when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> date.format(dayFormat)
                }
                ListRow {
                    RowText(
                        (t.station?.let { com.flash.headway.data.Network.name(it) } ?: "Unknown station") + when (t.kind) {
                            NcmcReader.Kind.ENTRY -> " · in"
                            NcmcReader.Kind.EXIT -> " · out"
                            NcmcReader.Kind.PENALTY -> " · penalty"
                            NcmcReader.Kind.OTHER -> ""
                        },
                        "$day, ${clock(t.at)}",
                        modifier = Modifier.weight(1f),
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        if (t.farePaise > 0) Text("−" + rupees(t.farePaise), style = HwType.subheadStrong, color = hw.text)
                        Text(rupees(t.balanceAfterPaise) + " left", style = HwType.footnote, color = hw.sub)
                    }
                }
            }
        },
    )
}

/** The card's UPI ID, for "Pay by UPI" top-ups. Stays on this phone. */
@Composable
private fun UpiIdCard(onSaved: (String?) -> Unit) {
    val hw = LocalHw.current
    val context = LocalContext.current
    var text by remember { mutableStateOf(TopUp.upiId(context).orEmpty()) }
    var bad by remember { mutableStateOf(false) }
    HwCard(gap = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    bad = !TopUp.setUpiId(context, it)
                    onSaved(TopUp.upiId(context))
                },
                label = { Text("UPI ID") },
                placeholder = { Text("ncmc.1234…@mairtel") },
                singleLine = true,
                isError = bad && text.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = {
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code ->
                            val id = TopUp.upiIdFromQr(code.rawValue)
                            if (id != null && TopUp.setUpiId(context, id)) {
                                text = id
                                bad = false
                                onSaved(id)
                            } else {
                                Toast.makeText(context, "That QR isn't a card top-up code", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Couldn't open the scanner. Type the ID instead.", Toast.LENGTH_LONG).show()
                        }
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 8.dp).size(IconButtonDefaults.mediumContainerSize()),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan card QR")
            }
        }
        Text(
            if (bad && text.isNotBlank()) {
                "That doesn't look like a UPI ID."
            } else {
                "From the \"Scan to recharge\" QR on the card. Adds Pay by UPI."
            },
            style = HwType.subhead,
            color = if (bad && text.isNotBlank()) hw.danger else hw.sub,
        )
    }
}

/** "at " today, "yesterday, " or "on 18 Nov, ": a read from another day says so. */
private fun readDay(ms: Long): String {
    val z = java.time.Instant.ofEpochMilli(ms).atZone(com.flash.headway.engine.IST).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(com.flash.headway.app.Clock.now()).atZone(com.flash.headway.engine.IST).toLocalDate()
    return when (z) {
        today -> "at "
        today.minusDays(1) -> "yesterday, "
        else -> "on ${z.dayOfMonth} ${z.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}, "
    }
}
