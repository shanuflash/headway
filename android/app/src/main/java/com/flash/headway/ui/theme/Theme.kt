package com.flash.headway.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flash.headway.R
import com.flash.headway.data.Line

/*
 * Material 3 Expressive theme. On Android 12+ the scheme comes from the wallpaper (dynamic color);
 * before that it is the scheme the web app uses, generated with material-color-utilities from the
 * brand blue #0b63ce (2025 spec: Vibrant accents on TonalSpot neutrals).
 *
 * The line colours and the amber "last trains" warning are fixed semantic colours on purpose: a
 * wallpaper must never turn the Green Line blue or mute the final-hour warning.
 */

private val BrandLight = lightColorScheme(
    primary = Color(0xFF0058BB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF00214F),
    inversePrimary = Color(0xFF84ADFF),
    secondary = Color(0xFF3853B7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE1FF),
    onSecondaryContainer = Color(0xFF203EA2),
    tertiary = Color(0xFF883C93),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8D8FF),
    onTertiaryContainer = Color(0xFF60136D),
    error = Color(0xFFA83836),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF6E0A12),
    background = Color(0xFFFAF9FE),
    onBackground = Color(0xFF1B1D24),
    surface = Color(0xFFFAF9FE),
    onSurface = Color(0xFF1B1D24),
    surfaceVariant = Color(0xFFE0E2ED),
    onSurfaceVariant = Color(0xFF5C5F68),
    surfaceDim = Color(0xFFD8D9E4),
    surfaceBright = Color(0xFFFAF9FE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF6),
    surfaceContainerHigh = Color(0xFFE7E7F1),
    surfaceContainerHighest = Color(0xFFE0E2ED),
    outline = Color(0xFF787A84),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2F3038),
    inverseOnSurface = Color(0xFFF0F0F8),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFF84ADFF),
    onPrimary = Color(0xFF002C65),
    primaryContainer = Color(0xFF0E438F),
    onPrimaryContainer = Color(0xFFD8E2FF),
    inversePrimary = Color(0xFF0058BB),
    secondary = Color(0xFFB6C4FF),
    onSecondary = Color(0xFF00287A),
    secondaryContainer = Color(0xFF2A43A6),
    onSecondaryContainer = Color(0xFFDBE1FF),
    tertiary = Color(0xFFFAB0FF),
    onTertiary = Color(0xFF6B1F77),
    tertiaryContainer = Color(0xFF6D2779),
    onTertiaryContainer = Color(0xFFFFD6FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D0E12),
    onBackground = Color(0xFFE3E5F0),
    surface = Color(0xFF0D0E12),
    onSurface = Color(0xFFE3E5F0),
    surfaceVariant = Color(0xFF23262D),
    onSurfaceVariant = Color(0xFFA8ABB5),
    surfaceDim = Color(0xFF0D0E12),
    surfaceBright = Color(0xFF292C34),
    surfaceContainerLowest = Color(0xFF08090C),
    surfaceContainerLow = Color(0xFF111318),
    surfaceContainer = Color(0xFF171A1F),
    surfaceContainerHigh = Color(0xFF1D2026),
    surfaceContainerHighest = Color(0xFF262A31),
    outline = Color(0xFF73757E),
    outlineVariant = Color(0xFF454850),
    inverseSurface = Color(0xFFE3E5F0),
    inverseOnSurface = Color(0xFF2F3038),
)

/** Fixed semantic colours: amber warning tonal pair and the three line colours, per theme. */
private class Semantic(
    val warn: Color,
    val onWarn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val blue: Color,
    val green: Color,
    val yellow: Color,
    val yellowText: Color,
)

private val SemanticLight = Semantic(
    warn = Color(0xFF885200), onWarn = Color.White, warnContainer = Color(0xFFFFDDBB), onWarnContainer = Color(0xFF673D00),
    blue = Color(0xFF005BC0), green = Color(0xFF006E28), yellow = Color(0xFFF2C230), yellowText = Color(0xFF755B00),
)

private val SemanticDark = Semantic(
    warn = Color(0xFFFFB868), onWarn = Color(0xFF482900), warnContainer = Color(0xFF4D2E00), onWarnContainer = Color(0xFFFFDDBB),
    blue = Color(0xFF80ABFF), green = Color(0xFF5DBF6B), yellow = Color(0xFFF2C230), yellowText = Color(0xFFF0C02E),
)

/**
 * Roles the screens use, resolved from the M3 colour scheme plus the fixed semantic colours.
 * The names are the app's own (card, sub, tint…) so every screen reads the same way.
 */
@Immutable
data class HwColors(
    val dark: Boolean,
    /** Screen background: surface. */
    val bg: Color,
    /** Cards and grouped rows: surface-container. */
    val card: Color,
    /** Raised fills inside cards (chips, idle toggles): surface-container-highest. */
    val fill: Color,
    val fill2: Color,
    /** The hero panel inside a route card: surface-container-lowest. */
    val hero: Color,
    /** Sheets: surface-container-low. */
    val sheet: Color,
    val text: Color,
    val sub: Color,
    val sep: Color,
    val chev: Color,
    val hair: Color,
    val outline: Color,
    val button: Color,
    /** Pre-M3 names, kept so older call sites compile: tonal surfaces now, no glass or rims. */
    val glassTop: Color,
    val glassBottom: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val warnBg: Color,
    val warn: Color,
    val onWarn: Color,
    val onWarnBg: Color,
    val warnButton: Color,
    val infoBg: Color,
    val info: Color,
    val tint: Color,
    val onTint: Color,
    val tintSoft: Color,
    val selected: Color,
    val onSelected: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiaryBg: Color,
    val onTertiaryBg: Color,
    val danger: Color,
    val dangerBg: Color,
    val onDangerBg: Color,
    val ok: Color,
    val inverse: Color,
    val onInverse: Color,
    val blue: Color,
    val green: Color,
    val yellow: Color,
    val yellowText: Color,
) {
    fun line(line: Line): Color = when (line) {
        Line.BLUE -> blue
        Line.GREEN -> green
        Line.YELLOW -> yellow
    }

    /** A line colour that is safe for text on surfaces (yellow is too light to read). */
    fun lineText(line: Line): Color = if (line == Line.YELLOW) yellowText else line(line)
}

private fun hwColors(s: ColorScheme, dark: Boolean): HwColors {
    val k = if (dark) SemanticDark else SemanticLight
    return HwColors(
        dark = dark,
        bg = s.surface,
        card = s.surfaceContainer,
        fill = s.surfaceContainerHighest,
        fill2 = s.surfaceContainerHighest,
        hero = s.surfaceContainerLowest,
        sheet = s.surfaceContainerLow,
        text = s.onSurface,
        sub = s.onSurfaceVariant,
        sep = s.outlineVariant,
        chev = s.onSurfaceVariant,
        hair = s.outlineVariant,
        outline = s.outline,
        button = s.surfaceContainerHigh,
        glassTop = s.surfaceContainer,
        glassBottom = s.surfaceContainer,
        rimTop = Color.Transparent,
        rimBottom = Color.Transparent,
        warnBg = k.warnContainer,
        warn = k.warn,
        onWarn = k.onWarn,
        onWarnBg = k.onWarnContainer,
        warnButton = k.warn.copy(alpha = 0.14f),
        infoBg = s.secondaryContainer,
        info = s.onSecondaryContainer,
        tint = s.primary,
        onTint = s.onPrimary,
        tintSoft = s.secondaryContainer,
        selected = s.secondaryContainer,
        onSelected = s.onSecondaryContainer,
        secondary = s.secondary,
        onSecondary = s.onSecondary,
        tertiaryBg = s.tertiaryContainer,
        onTertiaryBg = s.onTertiaryContainer,
        danger = s.error,
        dangerBg = s.errorContainer,
        onDangerBg = s.onErrorContainer,
        ok = s.primary,
        inverse = s.inverseSurface,
        onInverse = s.inverseOnSurface,
        blue = k.blue,
        green = k.green,
        yellow = k.yellow,
        yellowText = k.yellowText,
    )
}

val LocalHw = staticCompositionLocalOf { hwColors(BrandLight, false) }

// ---------------- type ----------------

/** Google Sans Flex (variable). Bundled so it works offline; the rounded cut is for the hero digits. */
private fun flex(weight: Int, round: Float = 0f) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("ROND", round)),
)

val SansFlex = FontFamily(flex(400), flex(500), flex(600), flex(700))

/** Fully rounded terminals: the countdown's "hero moment". */
val SansFlexRounded = FontFamily(flex(500, 100f), flex(700, 100f))

private fun baseline(t: Typography) = t.copy(
    displayLarge = t.displayLarge.copy(fontFamily = SansFlex),
    displayMedium = t.displayMedium.copy(fontFamily = SansFlex),
    displaySmall = t.displaySmall.copy(fontFamily = SansFlex),
    headlineLarge = t.headlineLarge.copy(fontFamily = SansFlex),
    headlineMedium = t.headlineMedium.copy(fontFamily = SansFlex),
    headlineSmall = t.headlineSmall.copy(fontFamily = SansFlex),
    titleLarge = t.titleLarge.copy(fontFamily = SansFlex),
    titleMedium = t.titleMedium.copy(fontFamily = SansFlex),
    titleSmall = t.titleSmall.copy(fontFamily = SansFlex),
    bodyLarge = t.bodyLarge.copy(fontFamily = SansFlex),
    bodyMedium = t.bodyMedium.copy(fontFamily = SansFlex),
    bodySmall = t.bodySmall.copy(fontFamily = SansFlex),
    labelLarge = t.labelLarge.copy(fontFamily = SansFlex),
    labelMedium = t.labelMedium.copy(fontFamily = SansFlex),
    labelSmall = t.labelSmall.copy(fontFamily = SansFlex),
)

/**
 * The app's text styles on the M3 type scale. Emphasized styles only change weight
 * (400 -> 500, 500 -> 700), as the M3 Expressive spec does.
 */
object HwType {
    private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private fun s(size: Int, line: Int, weight: Int = 400, tracking: Double = 0.0) =
        TextStyle(fontFamily = SansFlex, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), letterSpacing = tracking.sp)

    /** Screen title: headline-large, emphasized and a size up for the large flexible app bar. */
    val largeTitle = s(40, 48, 500, -0.25)
    /** Route and line card titles: title-large emphasized. */
    val cardTitle = s(22, 28, 500)
    /** Empty-state headline: headline-large emphasized. */
    val title = s(32, 40, 500)
    /** Sheet titles: headline-small emphasized. */
    val sheetTitle = s(24, 32, 500)
    /** Section labels: label-large emphasized (coloured primary by the caller). */
    val section = s(14, 20, 700, 0.1)
    /** title-medium. */
    val headline = s(16, 24, 500, 0.15)
    /** body-large. */
    val body = s(16, 24, 400, 0.5)
    /** body-medium. */
    val subhead = s(14, 20, 400, 0.25)
    /** label-large emphasized. */
    val subheadStrong = s(14, 20, 700, 0.1)
    /** body-small. */
    val footnote = s(12, 16, 400, 0.4)
    /** label-small. */
    val caption = s(11, 16, 500, 0.5)
    /** label-medium. */
    val tab = s(12, 16, 500, 0.5)
    val big = TextStyle(fontFamily = SansFlexRounded, fontSize = 88.sp, lineHeight = 88.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp, lineHeightStyle = trim)
    val bigClock = TextStyle(fontFamily = SansFlexRounded, fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, lineHeightStyle = trim)
    val bigUnit = s(24, 32, 600)
}

// ---------------- shape ----------------

/** The M3 corner scale. Expressive adds large-increased (20), extra-large-increased (32) and XXL (48). */
object HwShape {
    val extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    val small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val largeIncreased = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    val extraLargeIncreased = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
    val extraExtraLarge = androidx.compose.foundation.shape.RoundedCornerShape(48.dp)
}

private val AppShapes = Shapes(
    extraSmall = HwShape.extraSmall,
    small = HwShape.small,
    medium = HwShape.medium,
    large = HwShape.large,
    extraLarge = HwShape.extraLarge,
)

@Composable
fun HeadwayTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(dark) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> BrandDark
            else -> BrandLight
        }
    }
    val type = remember { baseline(Typography()) }
    CompositionLocalProvider(LocalHw provides hwColors(scheme, dark)) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = AppShapes,
            typography = type,
            content = content,
        )
    }
}

/** For places that need a scheme colour outside composition-local reads. */
val currentScheme: ColorScheme
    @Composable get() = MaterialTheme.colorScheme
