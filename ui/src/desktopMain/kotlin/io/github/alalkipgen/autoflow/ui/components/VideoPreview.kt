package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Desktop stub — video preview is not yet supported on desktop. */
@Composable
actual fun VideoPreview(path: String, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth().height(120.dp).background(AutoFlowColors.Panel),
        contentAlignment = Alignment.Center,
    ) {
        AfText("▶ Video preview (desktop not supported)", muted = true, size = 12.sp)
    }
}
