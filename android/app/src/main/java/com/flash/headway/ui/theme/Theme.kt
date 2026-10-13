package com.flash.headway.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.flash.headway.data.Line

/** Design tokens from the Headway canvas: grouped background, white cards, one blue tint. */
@Immutable
data class HwColors(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val fill: Color,
    val fill2: Color,
    val text: Color,
    val sub: Color,
    val sep: Color,
    val chev: Color,
    val hair: Color,
    val button: Color,
    val warnBg: Color,
    val warn: Color,
    val warnButton: Color,
    val infoBg: Color,
    val info: Color,
    val glass: Color,
    val glassBorder: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val tintSoft: Color,
    val tint: Color,
    val selected: Color,
    val ok: Color,
    val blue: Color,
    val green: Color,
    val yellow: Color,
) {
    fun line(line: Line): Color = when (line) {
        Line.BLUE -> blue
        Line.GREEN -> green
        Line.YELLOW -> yellow
    }
}

val LightColors = HwColors(
    dark = false,
    bg = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    fill = Color(0x1F767680),
    fill2 = Color(0x1F767680),
    text = Color(0xFF000000),
    sub = Color(0xFF6C6C70),
    sep = Color(0xFFD1D1D6),
    chev = Color(0xFFAEAEB2),
    hair = Color(0x0F000000),
    button = Color(0xCCFFFFFF),
    warnBg = Color(0xFFFFF4E5),
    warn = Color(0xFFB25000),
    warnButton = Color(0x1AA34E00),
    infoBg = Color(0xFFEAF1FB),
    info = Color(0xFF0B4FA6),
    glass = Color(0xEBFFFFFF),
    glassBorder = Color(0x14000000),
    glassTop = Color(0xF7FFFFFF),
    glassBottom = Color(0xF0F7F7FA),
    rimTop = Color(0xFFFFFFFF),
    rimBottom = Color(0x12000000),
    tintSoft = Color(0x1A0B63CE),
    tint = Color(0xFF0B63CE),
    selected = Color(0x1F0B63CE),
    ok = Color(0xFF34C759),
    blue = Color(0xFF0B63CE),
    green = Color(0xFF248A3D),
    yellow = Color(0xFFF2C230),
)

val DarkColors = HwColors(
    dark = true,
    bg = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    fill = Color(0x3D767680),
    fill2 = Color(0x3D767680),
    text = Color(0xFFFFFFFF),
    sub = Color(0xFF98989F),
    sep = Color(0xFF38383A),
    chev = Color(0xFF636366),
    hair = Color(0x1AFFFFFF),
    button = Color(0xD92C2C2E),
    warnBg = Color(0x29FF9F0A),
    warn = Color(0xFFFF9F0A),
    warnButton = Color(0x2EFF9F0A),
    infoBg = Color(0x294C9BFF),
    info = Color(0xFF8AB8FF),
    glass = Color(0xEB242426),
    glassBorder = Color(0x1AFFFFFF),
    glassTop = Color(0xF23E3E42),
    glassBottom = Color(0xF22A2A2D),
    rimTop = Color(0x5CFFFFFF),
    rimBottom = Color(0x14FFFFFF),
    tintSoft = Color(0x333D8BFF),
    tint = Color(0xFF3D8BFF),
    selected = Color(0x333D8BFF),
    ok = Color(0xFF30D158),
    blue = Color(0xFF3D8BFF),
    green = Color(0xFF3DD07A),
    yellow = Color(0xFFF2C230),
)

val LocalHw = staticCompositionLocalOf { LightColors }

/** iOS-style text scale used across the design. */
object HwType {
    private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val cardTitle = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val section = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val subheadStrong = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val tab = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
    val big = TextStyle(fontSize = 72.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2.5).sp, lineHeightStyle = trim)
    val bigClock = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp, lineHeightStyle = trim)
    val bigUnit = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun HeadwayTheme(dark: Boolean, content: @Composable () -> Unit) {
    val hw = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = hw.tint, background = hw.bg, surface = hw.card, onSurface = hw.text, surfaceContainerLow = hw.bg)
    } else {
        lightColorScheme(primary = hw.tint, background = hw.bg, surface = hw.card, onSurface = hw.text, surfaceContainerLow = hw.bg)
    }
    CompositionLocalProvider(LocalHw provides hw) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
