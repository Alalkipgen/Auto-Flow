package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.joinPath

/** `~/.autoflow`, or the `-Dautoflow.home` override when it is set. */
fun AppDirs.Companion.desktop(
    userHome: String = System.getProperty("user.home"),
    override: String? = System.getProperty(HOME_OVERRIDE_PROPERTY),
): AppDirs = AppDirs(override?.takeIf { it.isNotBlank() } ?: joinPath(userHome.toPortablePath(), DIR_NAME))
