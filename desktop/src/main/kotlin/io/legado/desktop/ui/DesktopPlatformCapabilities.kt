package io.legado.desktop.ui

import io.legado.app.ui.root.PlatformCapabilities
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

object DesktopPlatformCapabilities : PlatformCapabilities {
    override fun exitApplication() = Unit

    override fun openExternalUrl(url: String) {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }

    override fun shareText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
