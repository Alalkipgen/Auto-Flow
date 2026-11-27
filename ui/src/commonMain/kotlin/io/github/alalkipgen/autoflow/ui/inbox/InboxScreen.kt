package io.github.alalkipgen.autoflow.ui.inbox

import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.CardRemoval
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassCard
import io.github.alalkipgen.autoflow.ui.components.GlassCardHeader
import io.github.alalkipgen.autoflow.ui.components.GlassIconButton
import io.github.alalkipgen.autoflow.ui.components.MonoText
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.glassDashedBox
import io.github.alalkipgen.autoflow.ui.components.interactionOverlay
import io.github.alalkipgen.autoflow.ui.components.touchOverflow
import io.github.alalkipgen.autoflow.ui.components.touchTarget
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

/**
 * Inbox screen: lists `<project>/inbox/` files; lets the user attach a video or image to a shot. Glass look of the
 * Music Video plan F (sample 11): no page title (the project bar names the page); the folder path box + a round Refresh
 * button; the cyan Shared to Auto Flow card; one row card per file with its kind tile; amber / green callouts.
 */
@Composable
fun InboxScreen(model: InboxModel, modifier: Modifier = Modifier, shared: SharedInbox? = null) {
    val state by model.state.collectAsState(model.current())
    val project = state.inboxDir to state.projectId
    var scanned by remember(model, project) { mutableStateOf(InboxUiState()) }
    var deletedNotice by remember(model, shared, project) { mutableStateOf<UiNotice?>(null) }
    val sharedState = shared?.state?.collectAsState()?.value
    val language = LocalUiLanguage.current

    fun refresh() {
        shared?.refresh()
        scanned = model.refresh()
    }
    fun deletionResult(name: String, deleted: Boolean) {
        refresh()
        deletedNotice = UiNotice(if (deleted) UiText.INBOX_DELETE_DONE else UiText.INBOX_DELETE_FAILED, "name" to name)
    }
    // Refresh on first composition, when the project changes and when a share arrives (share-sheet import).
    LaunchedEffect(model, shared, project, sharedState?.arrivals) { refresh() }

    Column(
        modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.inboxDir?.let { InboxPathBox(it, Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
            GlassIconButton(AfIcons.Regular.Redo, tr(UiText.REFRESH), ::refresh, Modifier.testTag("inbox-refresh"))
        }
        AfText(tr(UiText.INBOX_HELP), muted = true, size = 12.sp)

        scanned.message?.let { message ->
            val done = scanned.notice?.key in ATTACHED
            GlassCallout(if (done) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, scanned.notice?.text(language) ?: message,
                if (done) AutoFlowColors.Green else AutoFlowColors.Amber, Modifier.testTag("inbox-message"))
        }
        deletedNotice?.let {
            val done = it.key == UiText.INBOX_DELETE_DONE
            GlassCallout(if (done) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(language)),
                if (done) AutoFlowColors.Green else AutoFlowColors.Amber,
                Modifier.testTag("inbox-deleted").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (shared != null && sharedState != null) {
            key(shared, project) {
                SharedCard(shared, sharedState, state.projectId != null, ::deletionResult, ::refresh)
            }
        }

        when {
            state.projectId == null -> AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true)
            scanned.items.isEmpty() -> InboxEmpty()
            else -> {
                scanned.items.forEach { item ->
                    key(model, item.projectId, item.path) {
                        InboxRemoval("inbox-file-${item.name}", item.name,
                            prepare = if (model.canDelete) ({ model.deleteRequest(item) }) else null,
                            onResult = ::deletionResult,
                            caption = tr(UiText.INBOX), kind = item.kind,
                        ) { modifier ->
                            InboxItemRow(item, state.shotNumbers, state.shotStartsMs, modifier) { shotNumber ->
                                model.attach(item, shotNumber)
                                refresh()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The notices that report a file attached to a shot (green); every other Inbox message is amber. */
private val ATTACHED = setOf(UiText.VIDEO_ATTACHED, UiText.IMAGE_ATTACHED)

/** The inbox folder (sample 11 `.pb9`): the Group 1 path box look, `folder` icon + FOLDER_PATH in one mono 11 sp text. */
@Composable
private fun InboxPathBox(path: String, modifier: Modifier) {
    val shape = GlassShapes.PathBox
    Row(modifier.clip(shape).background(GlassColors.PathFill).border(1.dp, GlassColors.PathBorder, shape)
        .padding(horizontal = 10.dp, vertical = 8.dp).testTag("inbox-folder"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(AfIcons.Regular.Folder, Modifier.padding(top = 1.dp), tint = AutoFlowColors.Muted, size = 16.dp)
        Box(Modifier.weight(1f)) { MonoText(tr(UiText.FOLDER_PATH, "it" to path), 11.sp, AutoFlowColors.Muted) }
    }
}

/** A file name that may wrap after ".", "_" or "-" (and between Burmese syllables), never inside a word at font 2.0. */
private fun softBreaks(name: String) = My.breakSyllables(name).replace(Regex("([._-])(?=.)"), "$1\u200B")

/** Nothing in the Inbox (sample 11b): the dashed box with the Duotone `tray` in Cyan and INBOX_EMPTY. */
@Composable
private fun InboxEmpty() {
    Column(Modifier.fillMaxWidth().glassDashedBox().padding(horizontal = 16.dp, vertical = 18.dp).testTag("inbox-empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(AfIcons.Duotone.Tray, tint = AutoFlowColors.Cyan, size = 30.dp)
        AfText(My.breakSyllables(tr(UiText.INBOX_EMPTY)), color = AutoFlowColors.Muted, type = AfType.Caption, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InboxRemoval(
    tag: String,
    name: String,
    prepare: (() -> InboxDeleteRequest?)?,
    onResult: (String, Boolean) -> Unit,
    caption: String,
    kind: InboxItemKind? = null,
    card: @Composable (Modifier) -> Unit,
) {
    val tagged = Modifier.testTag(tag)
    if (prepare == null) {
        card(tagged)
        return
    }
    val title = tr(UiText.INBOX_DELETE_TITLE, "name" to name)
    CardRemoval(
        tag, UiText.CARD_DELETE, title, UiText.INBOX_DELETE_BODY,
        prepareRemoval = {
            val request = prepare()
            if (request == null) { onResult(name, false); null }
            else ({ onResult(request.name, request.confirm()) })
        },
        accessibilityLabel = title,
        name = name,
        caption = caption,
        leading = kind?.let { { SoftTile(it.icon(), it.colour(), size = GlassShapes.DialogTile) } },
    ) { card(tagged.then(it)) }
}

/** An Inbox file's kind tile (plan F, sample 11): video `film-strip` lavender, image `image` blue, other `file` Muted. */
private fun InboxItemKind.icon(): ImageVector = when (this) {
    InboxItemKind.VIDEO -> AfIcons.Fill.FilmStrip
    InboxItemKind.IMAGE -> AfIcons.Fill.Image
    InboxItemKind.OTHER -> AfIcons.Fill.File
}
private fun InboxItemKind.colour(): Color = when (this) {
    InboxItemKind.VIDEO -> GlassColors.InboxVideo
    InboxItemKind.IMAGE -> GlassColors.InboxImage
    InboxItemKind.OTHER -> AutoFlowColors.Muted
}

@Composable
private fun InboxItemRow(
    item: InboxItem,
    shotNumbers: List<Int>,
    shotStartsMs: Map<Int, Long>,
    modifier: Modifier,
    onAttach: (Int) -> Unit,
) {
    var expanded by remember(item.path) { mutableStateOf(false) }
    val shape = GlassShapes.InboxRow
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassColors.InboxRowFill)
            .border(1.dp, GlassColors.InboxRowBorder, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(item.kind.icon(), item.kind.colour())
            AfText(softBreaks(item.name), Modifier.weight(1f), size = 14.sp, bold = true)
        }
        when {
            item.kind == InboxItemKind.OTHER ->
                GlassCallout(AfIcons.Fill.Warn, tr(UiText.CANNOT_ATTACH), AutoFlowColors.Amber, Modifier.testTag("inbox-cannot-attach"))
            !expanded -> GlassButton(tr(UiText.ATTACH_SHOT), { expanded = true }, Modifier.testTag("inbox-attach"),
                icon = AfIcons.Regular.Paperclip, small = true)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText(tr(UiText.CHOOSE_SHOT), color = AutoFlowColors.Muted, type = AfType.Micro)
                if (shotNumbers.isEmpty()) {
                    AfText(tr(UiText.NO_SHOTS_STORYBOARD), color = AutoFlowColors.Muted, type = AfType.Caption)
                } else {
                    WrapRow(spacing = 6.dp) {
                        shotNumbers.forEach { n -> ShotChip(n, shotStartsMs[n]) { expanded = false; onAttach(n) } }
                    }
                }
                GlassButton(tr(UiText.CANCEL), { expanded = false }, Modifier.testTag("inbox-attach-cancel"), icon = AfIcons.Regular.Close, small = true)
            }
        }
    }
}

/** A Choose shot chip (sample 11 `.fc`): the off filter chip look, "Shot n" + its start time in mono 11 sp Muted. */
@Composable
private fun ShotChip(number: Int, startMs: Long?, onClick: () -> Unit) {
    val shape = GlassShapes.Pill
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.touchOverflow(vertical = (AfSize.Touch - AfSize.FilterChip) / 2).testTag("inbox-shot-$number")
            .touchTarget(interaction, true, Role.Button, AfSize.FilterChip, onClick)
            .clip(shape).background(GlassColors.FilterOffFill).border(1.dp, GlassColors.FilterOffBorder, shape)
            .interactionOverlay(interaction, shape)
            .padding(horizontal = 13.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AfText("Shot $number", color = AutoFlowColors.Text, type = AfType.Micro)
        startMs?.let { AfText(ShotsModel.formatMs(it), color = AutoFlowColors.Muted, mono = true, size = 11.sp) }
    }
}

/** Files shared to Auto Flow from another app (sample 11): what happened, the waiting files and "Move here". */
@Composable
private fun SharedCard(
    shared: SharedInbox, st: SharedInboxState, projectOpen: Boolean,
    onDeleteResult: (String, Boolean) -> Unit, onMoved: () -> Unit,
) {
    if (st.waiting.isEmpty() && st.message == null) return
    GlassCard(Modifier.testTag("inbox-shared"), tint = AutoFlowColors.Cyan, tintAlpha = GlassColors.TintInboxShared,
        outline = GlassColors.InboxSharedLine, outlineWidth = 1.dp, gap = 10.dp) {
        GlassCardHeader(tr(UiText.SHARED_TO_AUTOFLOW), tile = { SoftTile(AfIcons.Fill.TrayArrowDown, AutoFlowColors.Cyan) })
        st.message?.let { AfText(st.notice?.text(LocalUiLanguage.current) ?: it, type = AfType.Caption) }
        if (st.waiting.isNotEmpty()) {
            st.waiting.forEach { name ->
                key(name) {
                    InboxRemoval("shared-file-$name", name,
                        prepare = if (shared.canDelete) ({ shared.deleteRequest(name) }) else null,
                        onResult = onDeleteResult,
                        caption = tr(UiText.SHARED_TO_AUTOFLOW),
                    ) { modifier ->
                        val kind = inboxKindOf(name)
                        Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SoftTile(kind.icon(), kind.colour(), size = 30.dp)
                            Box(Modifier.weight(1f)) { MonoText(My.breakSyllables(name), 12.5.sp, AutoFlowColors.Text) }
                        }
                    }
                }
            }
            if (projectOpen) {
                GlassButton(tr(UiText.MOVE_HERE_COUNT, "n" to st.waiting.size), { shared.moveHere(); onMoved() }, Modifier.testTag("inbox-move-here"),
                    kind = ButtonKind.Primary, icon = AfIcons.Regular.DownloadSimple, small = true)
            } else {
                GlassCallout(AfIcons.Fill.Warn, tr(UiText.OPEN_PROJECT_FIRST), AutoFlowColors.Amber, Modifier.testTag("inbox-shared-no-project"))
            }
        } else {
            GlassButton(tr(UiText.OK), shared::dismissMessage, Modifier.testTag("inbox-shared-ok"), small = true)
        }
    }
}
