package pl.hamlogbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.hamlogbridge.ui.theme.MonoSmall
import pl.hamlogbridge.ui.theme.TextMuted
import java.util.Locale

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MonoSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Horizontal phosphor bar: -24 dB is empty, +10 dB is full. */
@Composable
fun SnrBar(snr: Int, widthDp: Int = 34) {
    val fraction = ((snr + 24).coerceIn(0, 34)) / 34f
    val colour = when {
        snr >= 0 -> MaterialTheme.colorScheme.primary
        snr >= -12 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    }
    Box(
        Modifier
            .width(widthDp.dp)
            .height(9.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.04f))
                .height(9.dp)
                .background(colour)
        )
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MonoSmall, color = TextMuted)
        Text(value, style = MonoSmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun formatHz(hz: Long): String =
    if (hz <= 0) "---.---" else String.format(Locale.ROOT, "%.6f", hz / 1_000_000.0)

fun ageText(ts: Long?): String {
    if (ts == null) return "never"
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 2 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}

private val GRID_RE = Regex("^[A-R]{2}\\d{2}([A-X]{2})?$", RegexOption.IGNORE_CASE)
private val REPORT_RE = Regex("^R?[+-]\\d{2}$")
private val NON_CALL_TOKENS = setOf(
    "CQ", "DE", "QRZ", "TEST", "RR73", "RRR", "R73", "73", "POTA", "SOTA", "WWFF", "NIL", "QSL"
)

/** Rough FT8/FT4 message tokenizer - fields are whitespace-separated. */
private fun tokensOf(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

/** True for tokens shaped like a callsign - not a grid square, report, or protocol keyword. */
private fun looksLikeCallsign(tokenRaw: String): Boolean {
    val token = tokenRaw.trim('.', ',')
    if (token.length < 3 || token.length > 15) return false
    val upper = token.uppercase(Locale.ROOT)
    if (upper in NON_CALL_TOKENS) return false
    if (GRID_RE.matches(upper)) return false
    if (REPORT_RE.matches(upper)) return false
    if (!upper.all { it.isLetterOrDigit() || it == '/' }) return false
    return upper.any { it.isDigit() } && upper.any { it.isLetter() }
}

/** Whether a decode's message field mentions this callsign, compound suffixes (e.g. SP2KMO/P) included. */
fun containsCallsign(text: String, call: String): Boolean {
    val target = call.trim().uppercase(Locale.ROOT)
    if (target.isBlank()) return false
    return tokensOf(text).any { tokenRaw ->
        val token = tokenRaw.uppercase(Locale.ROOT)
        token == target || token.substringBefore('/') == target || token.substringAfter('/') == target
    }
}

/** Best-effort "other station" callsign in a decode line, for tap-to-look-up. */
fun extractDxCall(text: String, myCall: String): String? {
    val my = myCall.trim().uppercase(Locale.ROOT)
    val candidates = tokensOf(text).filter { looksLikeCallsign(it) }
    val other = candidates.firstOrNull { it.uppercase(Locale.ROOT).substringBefore('/') != my }
    return (other ?: candidates.firstOrNull())?.uppercase(Locale.ROOT)
}
