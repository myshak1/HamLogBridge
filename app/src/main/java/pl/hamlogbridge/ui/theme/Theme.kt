package pl.hamlogbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Instrument-panel palette: night-operating background, phosphor green for
 * anything received, amber for transmit and warnings, signal red for failures.
 */
val Ink = Color(0xFF070C10)
val Panel = Color(0xFF0E161D)
val PanelHigh = Color(0xFF15212B)
val Phosphor = Color(0xFF4BE39A)
val PhosphorDim = Color(0xFF2C8C61)
val Amber = Color(0xFFF2B441)
val SignalRed = Color(0xFFE2564D)
val Ice = Color(0xFF5AC8E8)
val TextHigh = Color(0xFFDCE6EC)
val TextMuted = Color(0xFF7C8F9B)

private val Dark = darkColorScheme(
    primary = Phosphor,
    onPrimary = Ink,
    primaryContainer = PhosphorDim,
    onPrimaryContainer = Ink,
    secondary = Ice,
    onSecondary = Ink,
    tertiary = Amber,
    onTertiary = Ink,
    background = Ink,
    onBackground = TextHigh,
    surface = Panel,
    onSurface = TextHigh,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextMuted,
    error = SignalRed,
    onError = Ink,
    outline = Color(0xFF2A3A46)
)

private val Light = lightColorScheme(
    primary = Color(0xFF116B45),
    secondary = Color(0xFF11607C),
    tertiary = Color(0xFF8A5A00),
    background = Color(0xFFF3F6F7),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E)
)

/** Callsigns, frequencies and decode lines are monospaced: the columns carry meaning. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 0.sp)
val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
val MonoBig = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Medium)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp
    )
)

@Composable
fun HamLogBridgeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        content = content
    )
}
