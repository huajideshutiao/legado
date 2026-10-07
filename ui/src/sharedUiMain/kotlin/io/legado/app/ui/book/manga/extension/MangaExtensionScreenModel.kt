package io.legado.app.ui.book.manga.extension

import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 搜索输入防抖 (同 Mihon `ExtensionsViewModel`: `searchQuery.debounce(0.25s)`)。 */
private const val SearchDebounceMillis = 250L

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
     * 刷新仓库并重拉可用插件列表 (逐仓库失败由平台层记日志; 仓库页有专门反馈)。
     *
     * 整页转圈**不在这里**置位: 平台实现的 `refresh()` 会把独立的 refreshing 流置 true,
     * 经 combine 进入 [MangaExtensionUiState.refreshing] (combine 每路输入都保留当前值,
     * 不会被别的发射覆盖), UI 按 `loading || refreshing` 呈现。
     */
    fun refresh() {
        val svc = service ?: return
        scope.launch { svc.refresh() }
    }

    // region 页面内搜索 (不持久化, 随页面销毁即丢)

    private val _searchQuery = MutableStateFlow<String?>(null)

    /** 搜索框实时文本 (null=搜索模式关闭); 只影响本页列表显示。 */
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    /** 防抖后的查询 (列表过滤用); 输入停 [SearchDebounceMillis] 后生效。 */
    @OptIn(FlowPreview::class)
    val searchFilterQuery: StateFlow<String?> = _searchQuery
        .debounce(SearchDebounceMillis)
        .stateIn(scope, SharingStarted.Lazily, null)

    fun setSearchQuery(query: String?) {
        _searchQuery.value = query
    }

    // endregion

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

    /** 写入单个配置项 (经平台侧 shim setter 落扩展偏好), 成功后重读配置刷新弹窗当前值。 */
    fun setPreference(key: String, value: MangaPrefValue) {
        val svc = service ?: return
        val pkgName = _prefDialog.value?.pkgName ?: return
        scope.launch {
            svc.setPreferenceValue(pkgName, key, value)
            reloadPreferenceItems(pkgName)
        }
    }

    /** 点击配置项 (平台侧 `Preference.performClick()`), 完成后重读配置刷新弹窗。 */
    fun performPreferenceClick(item: MangaPrefItem) {
        val svc = service ?: return
        val pkgName = _prefDialog.value?.pkgName ?: return
        scope.launch {
            svc.performPreferenceClick(pkgName, item.index)
            reloadPreferenceItems(pkgName)
        }
    }

    /** 开关类配置项切到 [newValue] (平台侧 `callChangeListener` + `setChecked`)。 */
    fun applyPreferenceChange(item: MangaPrefItem, newValue: Boolean) {
        val svc = service ?: return
        val pkgName = _prefDialog.value?.pkgName ?: return
        scope.launch {
            svc.applyPreferenceChange(pkgName, item.index, newValue)
            reloadPreferenceItems(pkgName)
        }
    }

    /** 输入框对话框绑定 (对应 androidx `OnBindEditTextListener` 的触发时机), 不改值不需重读。 */
    fun bindEditTextPreference(item: MangaPrefItem) {
        val svc = service ?: return
        val pkgName = _prefDialog.value?.pkgName ?: return
        scope.launch { svc.bindEditTextPreference(pkgName, item.index) }
    }

    /** 交互可能联动改值 (回调返回 true), 重读配置刷新弹窗当前值。 */
    private suspend fun reloadPreferenceItems(pkgName: String) {
        val svc = service ?: return
        val items = svc.buildPreferenceItems(pkgName)
        _prefDialog.update { current ->
            if (current?.pkgName == pkgName) current.copy(items = items) else current
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

    /** 类型筛选 (只作用「可用」列表; 持久化), 与语言筛选同一套 chips 语义。 */
    fun selectKind(filter: MangaExtensionKindFilter) {
        service?.setKindFilter(filter)
    }

    /** 内容分级筛选 (同 [selectKind])。 */
    fun selectContent(filter: MangaContentFilter) {
        service?.setContentFilter(filter)
    }
}
