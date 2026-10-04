package io.legado.app.ui.book.tvbox

import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 影视源 (TVBox) 管理页 ScreenModel。
 *
 * 状态真源是平台服务 [TvBoxServiceProviders] 的 [TvBoxUiState] (app 端由
 * AndroidTvBoxPlatform 桥接 TvBoxManager); 本类只做动作转发与首入口 init (幂等)。
 * 服务未注册端 (desktop 等)「我的」页入口已隐藏, state 兜底空流仅为防御。
 */
class TvBoxScreenModel : ScreenModel {

    private val service = TvBoxServiceProviders.getOrNull()

    private val scope = screenModelScope("影视源管理")

    init {
        service?.init()
    }

    val state: StateFlow<TvBoxUiState> =
        service?.state ?: MutableStateFlow(TvBoxUiState())

    fun addConfigSource(url: String) {
        val svc = service ?: return
        scope.launch { svc.importConfig(url) }
    }

    fun refresh() {
        val svc = service ?: return
        scope.launch { svc.refresh() }
    }

    fun removeSource(url: String) {
        val svc = service ?: return
        scope.launch { svc.removeSource(url) }
    }

    fun activateSource(url: String) {
        val svc = service ?: return
        scope.launch { svc.activateSource(url) }
    }

    fun toggleSite(siteKey: String, enabled: Boolean) {
        val svc = service ?: return
        scope.launch { svc.setSiteEnabled(siteKey, enabled) }
    }

    fun probeJars() {
        service?.probeJars()
    }

    fun clearError() {
        service?.clearError()
    }
}
