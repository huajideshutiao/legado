package io.legado.app.ui.compose.platform

/** 桌面端按宿主系统区分: macOS 用 Cmd, Windows/Linux 用 Ctrl。 */
internal actual val usesMetaAsCommandKey: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")
