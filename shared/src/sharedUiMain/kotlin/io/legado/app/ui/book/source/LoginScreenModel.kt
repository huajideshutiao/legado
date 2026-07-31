package io.legado.app.ui.book.source

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "loading" / "log"

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.SourceLoginContext
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
 * 书源登录页 shared ScreenModel: 托管 [LoginUiState]。
 *
 * 对照 app 端 `BaseSource.showLoginDialog` 的两条分支:
 * - loginUi 非空 -> 表单登录, 由 Route 渲染 [SourceLoginDialog];
 * - 否则 loginUrl 非空 -> WebView 登录 (对照 `WebViewActivity` isLogin=true), cookie 持久化
 *   由平台 WebView slot 在 onPageFinished 内调
 *   `CookieStoreProviders.get().setCookie(source.getKey(), cookie)` 完成;
 * - [loginComplete] 投递 [loginCompleteFlow] 信号, 由 Route pop。
 *
 * 源对象优先取路由携带的 [SourceLoginContext] (对照原版 `IntentData.nowSource`), 拿不到再按
 * sourceUrl 查库; HttpTTS (key 形如 `httpTts:$id`) 不在 bookSourceDao, 只能走前者或 id 反查。
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
            is LoginUiEvent.Init -> init(event.sourceUrl, event.dataKey)
            LoginUiEvent.LoginComplete -> loginComplete()
            LoginUiEvent.Refresh -> _refreshFlow.tryEmit(Unit)
            LoginUiEvent.ShowAppLog -> _state.update { it.copy(showAppLog = true) }
            LoginUiEvent.DismissAppLogDialog -> _state.update { it.copy(showAppLog = false) }
        }
    }

    // 源对象优先取路由携带的内存上下文, 其次查库 (书源 / HttpTTS)
    private fun init(sourceUrl: String, dataKey: String?) {
        // IntentData 是 one-shot, 重进组合时 key 已被消费, 已加载过就不再覆盖
        if (_state.value.source != null) return
        scope.launch {
            val context = SourceLoginContext.take(dataKey)
            val source = context?.source ?: loadSource(sourceUrl)
            _state.update {
                it.copy(
                    source = source,
                    book = context?.book,
                    chapter = context?.chapter,
                    loginUrl = source?.loginUrl ?: "",
                    sourceName = source?.getTag() ?: "",
                    loading = false,
                )
            }
        }
    }

    private suspend fun loadSource(sourceUrl: String): BaseSource? {
        val db = AppDbProviders.get()
        // HttpTTS.getKey() = "httpTts:$id", 不在 bookSourceDao
        if (sourceUrl.startsWith(HTTP_TTS_KEY_PREFIX)) {
            val id = sourceUrl.removePrefix(HTTP_TTS_KEY_PREFIX).toLongOrNull() ?: return null
            return db.httpTTSDao.get(id)
        }
        return db.bookSourceDao.getBookSource(sourceUrl)
    }

    private fun loginComplete() {
        _loginCompleteFlow.tryEmit(Unit)
    }

    override fun onCleared() {
        scope.cancel()
    }

    private companion object {
        const val HTTP_TTS_KEY_PREFIX = "httpTts:"
    }
}

/**
 * 登录页展示状态。
 *
 * @param source 已加载源 (null 表示加载中/失败)
 * @param book 登录 JS 的 book 绑定 (对照原版 IntentData.nowBook)
 * @param chapter 登录 JS 的 chapter 绑定 (对照原版 IntentData.nowChapter)
 * @param loginUrl WebView 加载 URL (source.loginUrl)
 * @param sourceName 源标签 (source.getTag()), 供标题栏副标题
 * @param pageTitle WebView 页面标题 (onPageFinished 回传, 初始空 → 显示 "loading")
 * @param loading 源加载中
 * @param showAppLog 是否展示日志对话框
 */
data class LoginUiState(
    val source: BaseSource? = null,
    val book: BaseBook? = null,
    val chapter: BookChapter? = null,
    val loginUrl: String = "",
    val sourceName: String = "",
    val pageTitle: String = "",
    val loading: Boolean = true,
    val showAppLog: Boolean = false,
)

sealed interface LoginUiEvent {
    /** 路由进入: 解析源 (dataKey 优先, 其次查库) */
    data class Init(val sourceUrl: String, val dataKey: String? = null) : LoginUiEvent

    /** 用户点击确认登录: 确认登录完成 */
    object LoginComplete : LoginUiEvent

    /** 重建平台 WebView 以加载当前地址 */
    object Refresh : LoginUiEvent

    /** 溢出菜单: 查看日志 */
    object ShowAppLog : LoginUiEvent

    /** 关闭日志对话框 */
    object DismissAppLogDialog : LoginUiEvent
}
