package io.github.alalkipgen.autoflow.ui.media

import java.io.File

internal actual fun deleteScratchFile(path: String): Boolean = File(path).delete()
