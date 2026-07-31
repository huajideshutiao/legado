package io.legado.desktop.ui

import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.toReadRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

object DesktopPlatformCapabilities : PlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun exitApplication() = Unit

    override fun openExternalUrl(url: String) {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }

    override fun shareText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun copyToClipboard(text: String) {
        shareText(text)
    }

    // 本地书文件字节数 (对照 app 端 FileDoc.fromFile(bookUrl).size; bookUrl 形如 file:///path)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(Dispatchers.IO) {
        val path = if (bookUrl.startsWith("file:")) {
            URI(bookUrl).path ?: bookUrl.removePrefix("file:")
        } else {
            bookUrl
        }
        File(path).length()
    }

    override fun openImportFile(filePath: String) {
        scope.launch {
            runCatching { FileBook.importLocalFile(filePath) }
                .onSuccess { book ->
                    AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
                }.onFailure { error ->
                    System.err.println("导入关联书籍失败: ${error.message}")
                }
        }
    }
}
