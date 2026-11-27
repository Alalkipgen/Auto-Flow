package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.dubbing.DubFitKind
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The window's width in dp: Dubbing's desktop panes start at 840 dp of WINDOW (not of the area left after the rail). */
@Composable
internal fun dubWindowWidthDp(): Float = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }

/** Desktop D3 (Group 4 H) from this window width: two panes. */
internal const val DUB_WIDE_DP = 840f

/** Desktop D3 from this window width: Dub script's subtitle and spoken fields side by side. */
internal const val DUB_COLUMNS_DP = 1200f

/**
 * Desktop D3 (Group 4 H): [main] (flex) and the [side] column on the right (500 dp, at most 42 % of the area; it scrolls on
 * its own), both under the step bar and above the step's sticky bar.
 */
@Composable
internal fun DubPanes(main: @Composable () -> Unit, side: @Composable ColumnScope.() -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideWidth = minOf(500.dp, maxWidth * .42f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { main() }
            Column(Modifier.width(sideWidth).fillMaxHeight().paneScroll(rememberScrollState())
                .padding(top = screenPadding, end = screenPadding, bottom = screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) { side() }
        }
    }
}

/** "#23 · 2:21.0 → 2:23.1": a line's number and its times (the desktop cards' titles). */
internal fun lineTitle(n: Int, startMs: Long, endMs: Long) = "#$n · ${TimeText.show(startMs)} → ${TimeText.show(endMs)}"

/**
 * The selected line's waveform (desktop Voice, Group 4 H, `dub-line-wave`): `waveform` tile, "#23 · 2:21.0 → 2:23.1" with
 * the fit pill, then the take's bars over the line's room — the room shaded Cyan, the take Purple (its part past the room
 * Amber) — and the legend DUB_TAKE · DUB_SLOT. [peaks] = the take's loudness (0–1) when its file is plain PCM; else the
 * bars are flat (the length is real, the shape is not drawn).
 */
@Composable
internal fun LineWaveCard(r: DubVoiceRow, peaks: List<Float>?, modifier: Modifier = Modifier) {
    val take = r.take
    TileCard(lineTitle(r.n, r.startMs, r.endMs), CardTile(AfIcons.Fill.Wave, AutoFlowColors.Purple), modifier.testTag("dub-line-wave"),
        action = { FitPill(r, Modifier) }) {
        val room = r.slotMs.coerceAtLeast(1)
        val length = maxOf(room, take?.ms ?: 0L)
        val box = RoundedCornerShape(10.dp)
        val label = listOfNotNull(take?.let { tr(UiText.DUB_TAKE, "s" to sec(it.ms)) }, tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0))
            .joinToString(" · ")
        Canvas(Modifier.fillMaxWidth().height(64.dp).clip(box).background(Color.White.copy(alpha = .03f)).semantics { contentDescription = label }) {
            val roomW = size.width * room / length
            drawRect(AutoFlowColors.Cyan.copy(alpha = .12f), size = Size(roomW, size.height))
            drawLine(AutoFlowColors.Cyan.copy(alpha = .7f), Offset(roomW, 0f), Offset(roomW, size.height), strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            if (take != null) {
                val takeW = size.width * take.ms / length
                val bar = 3.dp.toPx(); val gap = 2.dp.toPx()
                val count = (takeW / (bar + gap)).toInt().coerceAtLeast(1)
                for (i in 0 until count) {
                    val x = i * (bar + gap)
                    val level = peaks?.takeIf { it.isNotEmpty() }?.let { p -> p[(i * p.size / count).coerceIn(0, p.lastIndex)] } ?: .5f
                    val h = (size.height - 12.dp.toPx()) * level.coerceIn(.08f, 1f)
                    drawRoundRect(if (x + bar > roomW) AutoFlowColors.Amber else AutoFlowColors.Purple, Offset(x, (size.height - h) / 2), Size(bar, h),
                        CornerRadius(bar / 2))
                }
            }
        }
        WrapRow(spacing = 14.dp) {
            take?.let { Legend(AutoFlowColors.Purple, tr(UiText.DUB_TAKE, "s" to sec(it.ms))) }
            Legend(AutoFlowColors.Cyan, tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0))
        }
    }
}

@Composable
private fun Legend(colour: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(colour))
    AfText(My.breakSyllables(text), muted = true, size = 12.sp)
}

/** A take file's loudness in [n] steps (0–1), when it is a plain 16-bit PCM `.wav`; null for anything else. */
internal fun wavePeaks(bytes: ByteArray, n: Int = 120): List<Float>? {
    fun u16(i: Int) = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
    if (bytes.size < 44 || bytes.decodeToString(0, 4) != "RIFF" || bytes.decodeToString(8, 12) != "WAVE") return null
    var i = 12; var channels = 0; var bits = 0
    while (i + 8 <= bytes.size) {
        val id = bytes.decodeToString(i, i + 4); val len = u32(i + 4).coerceAtMost((bytes.size - i - 8).toLong()).toInt()
        if (id == "fmt " && len >= 16) { if (u16(i + 8) != 1) return null; channels = u16(i + 10); bits = u16(i + 22) }
        if (id == "data") {
            if (bits != 16 || channels < 1) return null
            val frame = 2 * channels
            val frames = len / frame
            if (frames < n) return null
            val start = i + 8
            val out = (0 until n).map { k ->
                var peak = 0
                for (f in (k * frames / n) until ((k + 1) * frames / n)) {
                    val v = u16(start + f * frame).toShort().toInt()
                    if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                }
                peak / 32768f
            }
            val top = out.maxOrNull()?.takeIf { it > 0f } ?: return out
            return out.map { it / top }
        }
        i += 8 + len + (len and 1)
    }
    return null
}

/**
 * The selected line's facts (desktop Dub script, Group 4 H): `note-pencil` tile, "#23 · 2:21.0 → 2:23.1" with the fit pill
 * when it has a take, the room and the take as facts, and the source (English) line in an inner box.
 */
@Composable
internal fun SelectedLineCard(r: DubScriptRow, source: String, voice: DubVoiceRow?) {
    TileCard(lineTitle(r.n, r.startMs, r.endMs), CardTile(AfIcons.Fill.NotePencil, AutoFlowColors.Purple),
        action = voice?.takeIf { it.take != null || it.old }?.let { v -> { FitPill(v, Modifier) } }) {
        GlassFact(AfIcons.Regular.Timer, My.breakSyllables(tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0)), colour = AutoFlowColors.Muted, caption = true)
        voice?.take?.let { GlassFact(AfIcons.Regular.Wave, My.breakSyllables(tr(UiText.DUB_TAKE, "s" to sec(it.ms))), colour = AutoFlowColors.Muted, caption = true) }
        if (source.isNotBlank()) {
            val box = RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxWidth().clip(box).background(Color.White.copy(alpha = .04f)).border(1.dp, Color.White.copy(alpha = .08f), box)
                .padding(horizontal = 12.dp, vertical = 10.dp)) { AfText(source, muted = true, size = 13.sp) }
        }
    }
}
