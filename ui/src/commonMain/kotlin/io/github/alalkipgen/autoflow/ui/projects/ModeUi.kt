package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.i18n.UiText

/** Localized mode name for Home, Projects and Continue (D-77): an explicit branch per mode, never a fallback. */
internal fun Mode.label(): UiText = when (this) {
    Mode.MUSIC_VIDEO -> UiText.MUSIC_VIDEO
    Mode.MOVIE_RECAP -> UiText.MOVIE_RECAP
    Mode.SUBTITLES -> UiText.V3_SUBTITLES
    Mode.VOICE_AUDIO -> UiText.V3_VOICE_AUDIO
}

/** Whether this build has screens for the mode (each new mode decides explicitly). */
internal fun Mode.openableHere(): Boolean = when (this) {
    Mode.MUSIC_VIDEO, Mode.MOVIE_RECAP, Mode.SUBTITLES -> true
    Mode.VOICE_AUDIO -> false // data only until its route lands (PLAN Phase 7 V-1c)
}

/** Whether Projects rows show the shot counter; tool projects have no shots. */
internal fun Mode.hasShots(): Boolean = when (this) {
    Mode.MUSIC_VIDEO, Mode.MOVIE_RECAP -> true
    Mode.SUBTITLES, Mode.VOICE_AUDIO -> false
}
