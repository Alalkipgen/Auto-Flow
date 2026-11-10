package io.github.alalkipgen.autoflow.ui.settings

/**
 * Build identity shown in Settings → About. The Android app binds it; [onVersionLongPress] opens the on-device
 * Self-test (PLAN S6). Optional in the Koin graph: without it Settings has no About card.
 */
class AppInfo(val version: String, val onVersionLongPress: (() -> Unit)? = null)
