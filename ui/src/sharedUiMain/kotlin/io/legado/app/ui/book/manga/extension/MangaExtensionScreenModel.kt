package io.legado.app.ui.book.manga.extension

import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /**
     * 刷新仓库并重拉可用插件列表 (失败静默, 仓库页有专门反馈)。
     *
     * 整页转圈**不在这里**置位: 平台实现的 `refresh()` 会把独立的 refreshing 流置 true,
     * 经 combine 进入 [MangaExtensionUiState.refreshing] (combine 每路输入都保留当前值,
     * 不会被别的发射覆盖), UI 按 `loading || refreshing` 呈现。
     */
    fun refresh() {
        val svc = service ?: return
        scope.launch { runCatching { svc.refresh() } }
    }

    // region 插件自带配置 (ConfigurableSource)

    private val _prefDialog = MutableStateFlow<MangaPrefDialogState?>(null)

    /** 当前打开的配置对话框 (null=未打开)。 */
    val prefDialog: StateFlow<MangaPrefDialogState?> = _prefDialog.asStateFlow()

    /** 打开插件自带配置: 平台侧读 shim PreferenceScreen 并转数据类, 失败按空表呈现。 */
    fun openPrefDialog(pkgName: String) {
        val svc = service ?: return
        _prefDialog.value = MangaPrefDialogState(pkgName = pkgName)
        scope.launch {
            runCatching { svc.buildPreferenceItems(pkgName) }
                .onSuccess { items ->
                    _prefDialog.update { current ->
                        if (current?.pkgName == pkgName) current.copy(loading = false, items = items) else current
                    }
                }
                .onFailure {
                    // 日志由平台层记录, 这里只把失败态带给 UI
                    _prefDialog.update { current ->
                        if (current?.pkgName == pkgName) current.copy(loading = false, failed = true) else current
                    }
                }
        }
    }

    /** 写入单个配置项 (扩展下次读取即生效), 成功后重读配置刷新弹窗当前值。 */
    fun setPreference(key: String, value: MangaPrefValue) {
        val svc = service ?: return
        val pkgName = _prefDialog.value?.pkgName ?: return
        scope.launch {
            runCatching { svc.setPreferenceValue(pkgName, key, value) }
                .onSuccess {
                    runCatching { svc.buildPreferenceItems(pkgName) }
                        .onSuccess { items ->
                            _prefDialog.update { current ->
                                if (current?.pkgName == pkgName) current.copy(items = items) else current
                            }
                        }
                }
        }
    }

    fun dismissPrefDialog() {
        _prefDialog.value = null
    }

    // endregion

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

    fun setLanguages(languages: Set<String>) {
        service?.setLanguages(languages)
    }

    /** 单选语言筛选: null=全部 (空集), 其余=仅保留该语言; 语言 chips 点选转发。 */
    fun selectLanguage(language: String?) {
        setLanguages(language?.let { setOf(it) } ?: emptySet())
    }
}
