package com.flash.headway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flash.headway.app.TopUp
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/** Every way to top up the metro card, with its fee and what happens to the card balance. */
@Composable
fun TopUpSheet(onDismiss: () -> Unit) {
    val hw = LocalHw.current
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(TopUp.chosen(context).id) }
    HwSheet(onDismiss = onDismiss, heightFraction = 0.92f) { _ ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Top up metro card", style = HwType.headline, color = hw.text, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                "For Singara Chennai and other NCMC cards. Your card is the one the long-press shortcut and the Quick Settings tile open.",
                style = HwType.footnote,
                color = hw.sub,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TopUp.providers.forEach { p ->
                val mine = p.id == chosen
                Column(
                    Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.name, style = HwType.headline, color = hw.text, modifier = Modifier.weight(1f))
                        Text(
                            p.fee,
                            style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = if (p.fee.startsWith("Free")) hw.ok else hw.text,
                            maxLines = 1,
                        )
                    }
                    Text(p.detail, style = HwType.subhead, color = hw.sub)
                    Text(p.balance, style = HwType.footnote, color = hw.text)
                    if (p.link != null) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Open",
                                style = HwType.subheadStrong,
                                color = Color.White,
                                modifier = Modifier.clip(CircleShape).background(hw.tint).press { TopUp.open(context, p) }
                                    .padding(horizontal = 18.dp, vertical = 9.dp),
                            )
                            Text(
                                if (mine) "My card" else "Use as my card",
                                style = HwType.subheadStrong,
                                color = if (mine) hw.tint else hw.text,
                                modifier = Modifier.clip(CircleShape).background(if (mine) hw.tintSoft else hw.fill)
                                    .then(if (mine) Modifier.border(1.dp, hw.tint, CircleShape) else Modifier.edge(CircleShape))
                                    .press {
                                        TopUp.choose(context, p.id)
                                        chosen = p.id
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
            Text(
                "Fees are what each provider charges as of Oct 2026. Online top-ups usually reach the card only after you update the balance at a Metro station.",
                style = HwType.footnote,
                color = hw.sub,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
