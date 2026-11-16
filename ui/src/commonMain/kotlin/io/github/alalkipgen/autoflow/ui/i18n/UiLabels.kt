package io.github.alalkipgen.autoflow.ui.i18n

import androidx.compose.runtime.Composable
import io.github.alalkipgen.autoflow.core.recap.*

/** Presentation only: model values, serialization, hashes and rendering factors stay unchanged. */
fun FrameBackground.uiLabel(language: UiLanguage): String = when (this) {
    FrameBackground.BARS -> UiText.BG_BARS.text(language)
    FrameBackground.BLUR -> UiText.BG_BLUR.text(language)
    FrameBackground.COLOUR -> UiText.COLOUR.text(language)
    FrameBackground.IMAGE -> UiText.BG_IMAGE.text(language)
}
@Composable fun FrameBackground.uiLabel(): String = uiLabel(LocalUiLanguage.current)

fun CaptionSize.uiLabel(language: UiLanguage): String = when (this) {
    CaptionSize.S -> UiText.SMALL.text(language)
    CaptionSize.M -> UiText.MEDIUM.text(language)
    CaptionSize.L -> UiText.LARGE.text(language)
}
@Composable fun CaptionSize.uiLabel(): String = uiLabel(LocalUiLanguage.current)

fun WatermarkAnchor.uiLabel(language: UiLanguage): String = when (this) {
    WatermarkAnchor.TOP_LEFT -> UiText.ANCHOR_TL.text(language)
    WatermarkAnchor.TOP_RIGHT -> UiText.ANCHOR_TR.text(language)
    WatermarkAnchor.BOTTOM_LEFT -> UiText.ANCHOR_BL.text(language)
    WatermarkAnchor.BOTTOM_RIGHT -> UiText.ANCHOR_BR.text(language)
    WatermarkAnchor.CENTER -> UiText.ANCHOR_CENTER.text(language)
}
@Composable fun WatermarkAnchor.uiLabel(): String = uiLabel(LocalUiLanguage.current)

fun Look.uiLabel(language: UiLanguage): String = when (this) {
    Look.MIRROR -> UiText.LOOK_MIRROR.text(language)
    Look.ZOOM -> UiText.LOOK_ZOOM.text(language)
    Look.FILL -> UiText.LOOK_FILL.text(language)
    Look.WARM -> UiText.LOOK_WARM.text(language)
}
@Composable fun Look.uiLabel(): String = uiLabel(LocalUiLanguage.current)

fun BackgroundMode.uiLabel(language: UiLanguage): String = when (this) {
    BackgroundMode.BLUR -> UiText.LAYER_BLUR.text(language)
    BackgroundMode.IMAGE -> UiText.LAYER_IMAGE.text(language)
    BackgroundMode.COLOUR -> UiText.COLOUR.text(language)
}
@Composable fun BackgroundMode.uiLabel(): String = uiLabel(LocalUiLanguage.current)

fun ExportQuality.uiLabel(language: UiLanguage): String = when (this) {
    ExportQuality.AUTO -> UiText.AUTO.text(language)
    ExportQuality.P720 -> UiText.QUALITY_720.text(language)
    ExportQuality.P1080 -> UiText.QUALITY_1080.text(language)
    ExportQuality.P1440 -> UiText.QUALITY_1440.text(language)
}
@Composable fun ExportQuality.uiLabel(): String = uiLabel(LocalUiLanguage.current)

/** Only for the app-owned colour preset palette, never for user-entered text. */
fun colourPresetLabel(name: String, language: UiLanguage): String = when (name) {
    "Black" -> UiText.COLOUR_BLACK.text(language)
    "White" -> UiText.COLOUR_WHITE.text(language)
    "Grey" -> UiText.COLOUR_GREY.text(language)
    "Purple" -> UiText.COLOUR_PURPLE.text(language)
    "Yellow" -> UiText.COLOUR_YELLOW.text(language)
    "Cyan" -> UiText.COLOUR_CYAN.text(language)
    "Red" -> UiText.COLOUR_RED.text(language)
    "Orange" -> UiText.COLOUR_ORANGE.text(language)
    "Green" -> UiText.COLOUR_GREEN.text(language)
    "Blue" -> UiText.COLOUR_BLUE.text(language)
    else -> name
}
@Composable fun colourPresetLabel(name: String): String = colourPresetLabel(name, LocalUiLanguage.current)

fun movieVolumeLabel(value: Double, language: UiLanguage): String = when (value) {
    0.0 -> UiText.OFF.text(language)
    0.2 -> UiText.LOW.text(language)
    1.0 -> UiText.FULL.text(language)
    else -> "${(value * 100).toInt()} %"
}
@Composable fun movieVolumeLabel(value: Double): String = movieVolumeLabel(value, LocalUiLanguage.current)
fun bgmVolumeLabel(value: Double, language: UiLanguage): String = when (value) {
    0.1 -> UiText.QUIET.text(language)
    0.18 -> UiText.MEDIUM.text(language)
    0.3 -> UiText.LOUD.text(language)
    else -> "${(value * 100).toInt()} %"
}
@Composable fun bgmVolumeLabel(value: Double): String = bgmVolumeLabel(value, LocalUiLanguage.current)

fun ttsVoiceKind(voice: String, language: UiLanguage): String = when (voice) {
    "Charon" -> UiText.VOICE_KIND_INFORMATIVE.text(language)
    "Kore" -> UiText.VOICE_KIND_FIRM.text(language)
    "Puck" -> UiText.VOICE_KIND_UPBEAT.text(language)
    "Zephyr" -> UiText.VOICE_KIND_BRIGHT.text(language)
    "Fenrir" -> UiText.VOICE_KIND_EXCITABLE.text(language)
    "Aoede" -> UiText.VOICE_KIND_BREEZY.text(language)
    else -> voice
}
@Composable fun ttsVoiceKind(voice: String): String = ttsVoiceKind(voice, LocalUiLanguage.current)
