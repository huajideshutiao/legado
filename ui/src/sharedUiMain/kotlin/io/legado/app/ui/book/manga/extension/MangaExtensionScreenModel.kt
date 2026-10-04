package io.legado.app.ui.book.manga.extension

import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 漫画插件管理页 ScreenModel。
 *
 * 状态真源是平台服务 [MangaExtensionServiceProviders] 的 [MangaExtensionUiState]
 * (app 端由 AndroidMangaExtensionPlatform 桥接 MangaExtensionManager); 本类只做
 * 动作转发与首入口 init (幂等)。服务未注册端 (desktop 等)「我的」页入口已隐藏,
 * state 兜底空流仅为防御。
 */
class MangaExtensionScreenModel : ScreenModel {

    private val service = MangaExtensionServiceProviders.getOrNull()

    private val scope = screenModelScope("漫画插件管理")

    init {
        service?.init()
    }

    val state: StateFlow<MangaExtensionUiState> =
        service?.state ?: MutableStateFlow(MangaExtensionUiState())

    /** 刷新仓库并重拉可用插件列表 (失败静默, 仓库页有专门反馈)。 */
    fun refresh() {
        val svc = service ?: return
        scope.launch { runCatching { svc.refresh() } }
    }

    fun install(pkgName: String) {
        service?.install(pkgName)
    }

    fun update(pkgName: String) {
        service?.update(pkgName)
    }

    fun cancelInstall(pkgName: String) {
        service?.cancelInstall(pkgName)
    }

    fun uninstall(pkgName: String) {
        service?.uninstall(pkgName)
    }

    fun trust(pkgName: String) {
        service?.trust(pkgName)
    }

    val selectedLanguages: Set<String> = service?.selectedLanguages ?: emptySet()

    fun setLanguages(languages: Set<String>) {
        service?.setLanguages(languages)
    }

    fun toggleLanguage(language: String) {
        val current = service?.selectedLanguages ?: return
        setLanguages(if (language in current) current - language else current + language)
    }

    fun clearLanguages() {
        setLanguages(emptySet())
    }
}
