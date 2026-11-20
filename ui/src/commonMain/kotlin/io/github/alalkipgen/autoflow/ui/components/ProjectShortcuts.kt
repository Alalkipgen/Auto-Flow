package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag

/** UI-only callbacks shared with the visible controls; no project state or new copy/approval behavior. */
@Stable
class ProjectShortcuts internal constructor() {
    private var playOwner: Any? = null
    private var playPause: (() -> Unit)? = null
    private var navOwner: Any? = null
    private var previous: (() -> Unit)? = null
    private var next: (() -> Unit)? = null

    internal fun setPlayPause(owner: Any, action: () -> Unit) { playOwner = owner; playPause = action }
    internal fun clearPlayPause(owner: Any) { if (playOwner === owner) { playOwner = null; playPause = null } }
    internal fun setNavigation(owner: Any, previous: (() -> Unit)?, next: (() -> Unit)?) {
        navOwner = owner; this.previous = previous; this.next = next
    }
    internal fun clearNavigation(owner: Any) { if (navOwner === owner) { navOwner = null; previous = null; next = null } }

    internal fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) return false
        when (event.key) {
            Key.Spacebar -> (playPause ?: return false).invoke()
            // Consume boundary arrows on the project surface: no wrap or accidental spatial-focus jump.
            Key.DirectionLeft -> { if (navOwner == null) return false; previous?.invoke() }
            Key.DirectionRight -> { if (navOwner == null) return false; next?.invoke() }
            else -> return false
        }
        return true
    }
}

val LocalProjectShortcuts = staticCompositionLocalOf<ProjectShortcuts?> { null }

/**
 * F-6: the phone Edit / Preview page of a project screen. A narrow [io.github.alalkipgen.autoflow.ui.recap.ProjectPanes]
 * offers the page while it is shown; [ProjectTopBar] then draws one toggle in its existing row, so the page adds no
 * height above the editor (large Burmese text must still fit before any scroll).
 */
@Stable
class ProjectPreviewPage {
    var shown by mutableStateOf(false)
    private var offers by mutableIntStateOf(0)
    val available: Boolean get() = offers > 0
    internal fun offer() { offers++ }
    internal fun withdraw() { offers--; if (offers == 0) shown = false }
}

val LocalProjectPreviewPage = staticCompositionLocalOf<ProjectPreviewPage?> { null }

/**
 * Shortcuts belong to the project surface, never a focused descendant. This protects ALL text inputs (including
 * non-AfTextField inputs), button Space/Enter, scrubber arrows, selection and accessibility key handling without
 * a fragile per-input focus counter. Tab still reaches controls; click passive content to return here.
 * Observe only unconsumed final-pass taps. Never consume a down/up or steal a child click, drag or wheel gesture.
 * [onKey]: a screen's own keys, asked only for keys the shared shortcuts did not take (same focus rule).
 */
@Composable
fun ProjectShortcutHost(modifier: Modifier = Modifier, onKey: ((KeyEvent) -> Boolean)? = null, content: @Composable () -> Unit) {
    val shortcuts = remember { ProjectShortcuts() }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val page = remember { ProjectPreviewPage() }
    LaunchedEffect(Unit) { if (!hasFocus) focus.requestFocus() }
    CompositionLocalProvider(LocalProjectShortcuts provides shortcuts, LocalProjectPreviewPage provides page) {
        Box(modifier.fillMaxSize()
            .pointerInput(focus) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Final)
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                    if (up != null && !up.isConsumed) focus.requestFocus()
                }
            }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; hasFocus = it.hasFocus }
            .onKeyEvent { focused && (shortcuts.handle(it) || onKey?.invoke(it) == true) }
            .focusable()
            .testTag("project-shortcuts")) { content() }
    }
}

/** Null targets match disabled/absent previous/next controls. The owner guard prevents stale disposal. */
@Composable
fun BlockShortcuts(previous: (() -> Unit)?, next: (() -> Unit)?) {
    val shortcuts = LocalProjectShortcuts.current ?: return
    val owner = remember { Any() }
    SideEffect { shortcuts.setNavigation(owner, previous, next) }
    DisposableEffect(shortcuts) { onDispose { shortcuts.clearNavigation(owner) } }
}

/** Same Play/Pause callback as the visible preview button; registration leaves with that preview. */
@Composable
fun PlayPauseShortcut(toggle: () -> Unit) {
    val shortcuts = LocalProjectShortcuts.current ?: return
    val owner = remember { Any() }
    SideEffect { shortcuts.setPlayPause(owner, toggle) }
    DisposableEffect(shortcuts) { onDispose { shortcuts.clearPlayPause(owner) } }
}
