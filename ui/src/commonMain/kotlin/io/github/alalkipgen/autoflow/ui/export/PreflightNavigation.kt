package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.core.recap.PreflightCheck
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.RecapStep

/** Core's explicit destinations, never a parsed/localized message or a second approval policy. */
internal fun preflightStep(item: PreflightCheck.Item): RecapStep? = when (item.fix) {
    "Voice" -> RecapStep.VOICE
    "Script" -> RecapStep.STORY
    else -> null
}

/** A stale report/callback must not navigate a replacement project or a running export. Read-only. */
internal fun preflightNavigationAllowed(model: ExportModel, displayed: OpenProject?, item: PreflightCheck.Item): Boolean {
    val current = model.state.value
    return displayed != null && model.projects.value == displayed && current.projectDir == displayed.dir &&
        !current.busy && item in current.recap?.preflight.orEmpty() && preflightStep(item) != null
}