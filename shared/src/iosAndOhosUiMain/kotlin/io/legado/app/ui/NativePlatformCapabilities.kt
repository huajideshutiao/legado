package io.legado.app.ui

import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.config.MODE_EDIT_CONFIG
import io.legado.app.ui.config.MODE_EDIT_PREFS
import io.legado.app.ui.config.MODE_NEW_CONFIG
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.SharedPlatformCapabilities
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.launch

/**
 * iOS 与鸿蒙实现逐字相同、但与桌面不同的那部分 [SharedPlatformCapabilities]。
 *
 * 桌面这几项各有自己的形态 (系统浏览器 / 文件选择器 / 无内嵌 WebView 路由), 故不放
 * [SharedPlatformCapabilities]; 两端一行委托到叶子源集函数的 (openURL / 剪贴板 /
 * NativeImportBook) 留在各端 —— 那些是平台边界, 且 nativeMain 与本源集互不可见。
 */
interface NativePlatformCapabilities : SharedPlatformCapabilities {

    /** 移动端保留内嵌 WebViewRoute 路由语义 (对话框内嵌)。 */
    override fun openWebView(url: String, sourceKey: String, sourceName: String) {
        AppNavigatorProviders.getOrNull()?.push(AppRoute.WebView(url, sourceKey, sourceName))
    }

    override fun getWebServiceUrl(): String? =
        WebServerManager.hostAddress.takeIf { it.isNotEmpty() }

    /** 两端均无 assets, 直接开仓库上的文档 (对照 desktop 本地文件)。 */
    override fun showMdFile(title: String, fileName: String) {
        val path = if (fileName == "LICENSE.md") "LICENSE" else "app/src/main/assets/$fileName"
        runCatching { openExternalUrl("https://github.com/gedoor/legado/blob/master/$path") }
    }

    override fun enableCustomExport(): Boolean =
        PreferenceProviders.get().getBoolean(PreferKey.enableCustomExport, false)

    override fun getDeleteBookOriginal(): Boolean =
        PreferenceProviders.get().getBoolean(LocalConfigKeys.deleteBookOriginal, false)

    override fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean) {
        val mode = if (configIndex == null) MODE_NEW_CONFIG else MODE_EDIT_CONFIG
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$mode,${configIndex ?: -1},$isNight")
        )
    }

    override fun showCustomizeDayThemeDialog() {
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,false")
        )
    }

    override fun showCustomizeNightThemeDialog() {
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,true")
        )
    }

    /** 分享选中书源 (导出前强制关闭危险 API 开关, 见 [selectedSourcesJson])。 */
    override fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        capabilityScope.launch {
            val json = selectedSourcesJson(selection) ?: return@launch
            shareText(json)
        }
    }

    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        capabilityScope.launch {
            onSelected(PlatformServiceProviders.getOrNull()?.files?.pickDirectory())
        }
    }
}

/**
 * 选中书源转 JSON: 导出/分享前强制关掉危险 API 开关。
 *
 * `internal` 而非文件私有: iOS/鸿蒙各自的 `exportBookSourceSelection` (落盘方式不同, 未合并)
 * 也要用它。
 */
internal suspend fun selectedSourcesJson(selection: List<BookSourcePart>): String? {
    val urls = selection.map { it.bookSourceUrl }
    val sources = runCatching { AppDbProviders.get().bookSourceDao.getBookSourcesFix(urls) }
        .getOrElse {
            Toasters.get().toast("导出书源失败\n${it.message}")
            return null
        }
    sources.forEach { if (it.enableDangerousApi == true) it.enableDangerousApi = false }
    return GSON.toJson(sources)
}
