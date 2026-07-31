package io.legado.app.ui.book.source

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "loading" / "log"

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书源登录页 (URL/WebView 登录) shared ScreenModel: 托管 [LoginUiState]。
 *
 * 对照 app 端 `WebViewActivity` (isLogin=true) 的登录流程:
 * - [init] 按 sourceUrl 从 DAO 加载 [BaseSource], 取 `source.loginUrl` 作为 WebView URL;
 * - cookie 持久化由平台 WebView slot 在 onPageFinished 内调
 *   `CookieStoreProviders.get().setCookie(source.getKey(), cookie)` 完成,
 *   本 Model 不直接操作平台 WebView;
 * - [loginComplete] 投递 [loginCompleteFlow] 信号, 由 Route pop。
 *
 * 与 [SourceLoginDialog] (form 登录, loginUi 非空) 互补: 本类仅处理 URL 登录场景。
 */
class LoginScreenModel : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /**
     * 事件流工厂: replay=1 + DROP_OLDEST, 语义对齐 LiveData.postValue。
     *
     * 不能用 StateFlow: StateFlow 按值去重, 重复投递相同值不会触发下游。
     */
    private fun <T> signalFlow() = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 刷新信号: 每次点刷新都投递一次, 由 Route 重建平台 WebView。 */
    private val _refreshFlow = signalFlow<Unit>()
    val refreshFlow: SharedFlow<Unit> = _refreshFlow.asSharedFlow()

    /** 登录完成信号 (对照 app 端 menu_ok 后 finish()), 由 Route pop。 */
    private val _loginCompleteFlow = signalFlow<Unit>()
    val loginCompleteFlow: SharedFlow<Unit> = _loginCompleteFlow.asSharedFlow()

    fun dispatch(event: LoginUiEvent) {
        when (event) {
            is LoginUiEvent.Init -> init(event.sourceUrl)
            LoginUiEvent.LoginComplete -> loginComplete()
            LoginUiEvent.Refresh -> _refreshFlow.tryEmit(Unit)
            LoginUiEvent.ShowAppLog -> _state.update { it.copy(showAppLog = true) }
            LoginUiEvent.DismissAppLogDialog -> _state.update { it.copy(showAppLog = false) }
        }
    }

    // 按 sourceUrl 加载书源, 取 loginUrl 供 WebView slot 加载
    private fun init(sourceUrl: String) {
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(sourceUrl)
            _state.update {
                it.copy(
                    source = source,
                    loginUrl = source?.loginUrl ?: "",
                    sourceName = source?.getTag() ?: "",
                    loading = false,
                )
            }
        }
    }

    private fun loginComplete() {
        _loginCompleteFlow.tryEmit(Unit)
    }

    override fun onCleared() {
        scope.cancel()
    }
}

/**
 * 登录页展示状态。
 *
 * @param source 已加载书源 (null 表示加载中/失败)
 * @param loginUrl WebView 加载 URL (source.loginUrl)
 * @param sourceName 源标签 (source.getTag()), 供标题栏副标题
 * @param pageTitle WebView 页面标题 (onPageFinished 回传, 初始空 → 显示 "loading")
 * @param loading 书源加载中
 * @param showAppLog 是否展示日志对话框
 */
data class LoginUiState(
    val source: BaseSource? = null,
    val loginUrl: String = "",
    val sourceName: String = "",
    val pageTitle: String = "",
    val loading: Boolean = true,
    val showAppLog: Boolean = false,
)

sealed interface LoginUiEvent {
    /** 路由进入: 按 sourceUrl 加载书源 */
    data class Init(val sourceUrl: String) : LoginUiEvent

    /** 用户点击确认登录: 确认登录完成 */
    object LoginComplete : LoginUiEvent

    /** 重建平台 WebView 以加载当前地址 */
    object Refresh : LoginUiEvent

    /** 溢出菜单: 查看日志 */
    object ShowAppLog : LoginUiEvent

    /** 关闭日志对话框 */
    object DismissAppLogDialog : LoginUiEvent
}
