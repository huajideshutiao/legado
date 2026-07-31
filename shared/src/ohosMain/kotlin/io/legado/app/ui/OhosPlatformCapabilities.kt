package io.legado.app.ui

import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.toReadRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object OhosPlatformCapabilities : PlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun exitApplication() = Unit

    override fun openExternalUrl(url: String) {
        openURL(url)
    }

    override fun shareText(text: String) {
        Toasters.get().toast("当前平台暂未接入系统分享")
    }

    override fun copyToClipboard(text: String) {
        Toasters.get().toast("当前平台暂未接入剪贴板")
    }

    override fun openImportFile(filePath: String) {
        scope.launch {
            runCatching { FileBook.importLocalFile(filePath) }
                .onSuccess { AppNavigatorProviders.getOrNull()?.push(it.toReadRoute()) }
                .onFailure { error -> println("导入关联书籍失败: ${error.message}") }
        }
    }
}
