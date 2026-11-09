package io.legado.app.ui.book.manga.extension

import io.legado.app.constant.AppLog
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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

    /** 配置加载/刷新的请求身份: 每次打开一个新身份, 关闭或重开作废旧请求的发布资格。 */
    private var prefRequestId: Long = 0

    /** 当前这次打开的未完成加载与动作所属作用域 (挂在 ScreenModel scope 下)。 */
    private var prefOpenScope: CoroutineScope? = null

    private var prefOpenJob: Job? = null

    /** 打开插件自带配置: 平台侧读 shim PreferenceScreen 并转数据类, 读取失败呈 failed 态。 */
    fun openPrefDialog(pkgName: String) {
        val svc = service ?: return
        prefOpenJob?.cancel()
        val requestId = ++prefRequestId
        val openJob = Job(scope.coroutineContext[Job])
        val openScope = CoroutineScope(scope.coroutineContext + openJob)
        prefOpenJob = openJob
        prefOpenScope = openScope
        _prefDialog.value = MangaPrefDialogState(pkgName = pkgName, requestId = requestId)
        openScope.launch {
            val items = try {
                svc.buildPreferenceItems(pkgName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 平台侧已记日志; 只把失败态带给本次请求
                publishPrefFailure(requestId, pkgName)
                return@launch
            }
            publishPrefItems(requestId, pkgName, items)
        }
    }

    /** 写入单个配置项 (经平台侧 shim setter 落扩展偏好), 成功后重读配置刷新弹窗当前值。 */
    fun setPreference(item: MangaPrefItem, value: MangaPrefValue) {
        val svc = service ?: return
        val request = currentPrefRequest() ?: return
        launchPrefAction {
            svc.setPreferenceValue(item, value)
            reloadPreferenceItems(request.requestId, request.pkgName)
        }
    }

    /** 点击配置项 (平台侧 `Preference.performClick()`), 完成后重读配置刷新弹窗。 */
    fun performPreferenceClick(item: MangaPrefItem) {
        val svc = service ?: return
        val request = currentPrefRequest() ?: return
        launchPrefAction {
            svc.performPreferenceClick(item)
            reloadPreferenceItems(request.requestId, request.pkgName)
        }
    }

    /** 开关类配置项切到 [newValue] (平台侧 `callChangeListener` + `setChecked`)。 */
    fun applyPreferenceChange(item: MangaPrefItem, newValue: Boolean) {
        val svc = service ?: return
        val request = currentPrefRequest() ?: return
        launchPrefAction {
            svc.applyPreferenceChange(item, newValue)
            reloadPreferenceItems(request.requestId, request.pkgName)
        }
    }

    /**
     * 配置项动作统一收口: 动作归属本次打开的作用域, 关闭/重开随作用域取消; 插件异常 (含 ABI
     * 缺类) 记日志并结束本次动作, 维持既有列表交互, 取消照常向上传播。
     */
    private fun launchPrefAction(action: suspend () -> Unit) {
        val openScope = prefOpenScope ?: return
        openScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("插件配置操作失败", e)
            }
        }
    }

    /** 活编辑会话落值后重读配置刷新弹窗当前值 (活会话写入不经本类, 弹窗关闭回调触发)。 */
    fun reloadPreferences() {
        val request = currentPrefRequest() ?: return
        launchPrefAction { reloadPreferenceItems(request.requestId, request.pkgName) }
    }

    /**
     * 真实文本项点击: 平台完成点击并返回活编辑会话; 插件监听抛错与取消区分 (取消重抛, 故障含
     * ABI 缺类记录日志并保持无会话); 点击可能联动改值, 会话仅在本次打开仍有效时返回。
     */
    suspend fun textItemClick(item: MangaPrefItem): MangaPrefTextSession? {
        val svc = service ?: return null
        val request = currentPrefRequest() ?: return null
        val session = try {
            svc.clickTextEditor(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("插件配置点击失败", e)
            null
        }
        if (!isCurrentPrefRequest(request.requestId, request.pkgName)) return null
        reloadPreferenceItems(request.requestId, request.pkgName)
        return session.takeIf { isCurrentPrefRequest(request.requestId, request.pkgName) }
    }

    /** 交互可能联动改值 (回调返回 true), 按发起时的同一请求身份重读配置。 */
    private suspend fun reloadPreferenceItems(requestId: Long, pkgName: String) {
        if (!isCurrentPrefRequest(requestId, pkgName)) return
        val svc = service ?: return
        val items = try {
            svc.buildPreferenceItems(pkgName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 平台读取边界已记日志; 本次刷新结束, 维持既有列表
            return
        }
        publishPrefItems(requestId, pkgName, items)
    }

    /** 发起动作时归属的请求身份 (打开那一刻的 id 与包名)。 */
    private fun currentPrefRequest(): PrefRequest? {
        val current = _prefDialog.value ?: return null
        return PrefRequest(current.requestId, current.pkgName)
    }

    private fun isCurrentPrefRequest(requestId: Long, pkgName: String): Boolean =
        requestId == prefRequestId && _prefDialog.value?.pkgName == pkgName

    private class PrefRequest(val requestId: Long, val pkgName: String)

    /** 发布结果既要核请求身份, 也要核当前打开的对话框 (旧请求不得更新新弹窗)。 */
    private fun publishPrefItems(requestId: Long, pkgName: String, items: List<MangaPrefItem>) {
        if (!isCurrentPrefRequest(requestId, pkgName)) return
        _prefDialog.update { current ->
            if (current?.requestId == requestId && current.pkgName == pkgName) {
                current.copy(loading = false, failed = false, items = items)
            } else {
                current
            }
        }
    }

    private fun publishPrefFailure(requestId: Long, pkgName: String) {
        if (!isCurrentPrefRequest(requestId, pkgName)) return
        _prefDialog.update { current ->
            if (current?.requestId == requestId && current.pkgName == pkgName) {
                current.copy(loading = false, failed = true)
            } else {
                current
            }
        }
    }

    fun dismissPrefDialog() {
        _prefDialog.value = null
        // 入口关闭: 作废本次打开的发布资格并收尾全部未完成请求
        prefRequestId++
        prefOpenJob?.cancel()
        prefOpenJob = null
        prefOpenScope = null
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
