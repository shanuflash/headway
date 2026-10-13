package com.flash.headway.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flash.headway.MainActivity
import com.flash.headway.data.Network
import com.flash.headway.engine.routeGroup
import com.flash.headway.ui.Footnote
import com.flash.headway.ui.HwCard
import com.flash.headway.ui.HwSwitch
import com.flash.headway.ui.LargeTitle
import com.flash.headway.ui.LineBar
import com.flash.headway.ui.ListCard
import com.flash.headway.ui.ListRow
import com.flash.headway.ui.PillButton
import com.flash.headway.ui.RowText
import com.flash.headway.ui.theme.HeadwayTheme
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/** Picks which saved route a 1×1 widget shows, and whether it shows the destination. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced, which is what Android expects.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        val routes = Widgets.routes(this)
        val (current, currentLabel) = Widgets.assigned(this, widgetId)
        setContent {
            HeadwayTheme(isSystemInDarkTheme()) {
                Config(
                    routes = routes.map { it.id to "${Network.name(it.from)} → ${Network.name(it.to)}" },
                    lines = routes.map { routeGroup(it.from, it.to)?.line },
                    selected = current ?: routes.firstOrNull()?.id,
                    initialLabel = currentLabel,
                    onDone = { routeId, label ->
                        Widgets.assign(this, widgetId, routeId, label)
                        Widgets.updateAll(this)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun Config(
    routes: List<Pair<String, String>>,
    lines: List<com.flash.headway.data.Line?>,
    selected: String?,
    initialLabel: Boolean,
    onDone: (String, Boolean) -> Unit,
    onOpenApp: () -> Unit,
) {
    val hw = LocalHw.current
    var label by remember { mutableStateOf(initialLabel) }
    Column(
        Modifier
            .fillMaxSize()
            .background(hw.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LargeTitle("Widget")
        if (routes.isEmpty()) {
            HwCard {
                Text("Add a route in Headway first, then place the widget again.", style = HwType.subhead, color = hw.sub)
                PillButton("Open Headway", onOpenApp)
            }
            return@Column
        }
        Footnote("Pick the route this widget shows.")
        ListCard(
            routes.mapIndexed { i, (id, title) ->
                @Composable {
                    ListRow(onClick = { onDone(id, label) }) {
                        LineBar(hw.line(lines[i] ?: com.flash.headway.data.Line.BLUE))
                        RowText(
                            title,
                            if (id == selected) "Showing now" else null,
                            titleStyle = HwType.headline,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
        ListCard(
            listOf<@Composable () -> Unit>({
                ListRow {
                    RowText("Show destination", "Replaces \"min\" under the number", modifier = Modifier.weight(1f))
                    HwSwitch(label, { label = it }, "Show destination")
                }
            }),
            inset = 16.dp,
        )
    }
}
