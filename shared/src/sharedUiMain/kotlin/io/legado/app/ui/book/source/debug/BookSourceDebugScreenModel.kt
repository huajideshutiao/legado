package io.legado.app.ui.book.source.debug

import androidx.compose.runtime.mutableStateListOf
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.IntentData
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.Debug
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.CancellationException
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
 * 书源调试页 ScreenModel (shared sharedUiMain)。
 *
 * 调试状态机 (init/observe/startDebug) 与全部 mutableStateOf 状态从 app 端
 * [BookSourceDebugModel] / [BookSourceDebugActivity] 迁入本类:
 * - 持有 [BookSourceDebugUiState] 的 [MutableStateFlow], logs 透传 SnapshotStateList 引用
 * - 实现 [Debug.Callback], printLog 内更新 logs/loading 与各阶段源码字段
 * - [dispatch] 处理 query/submit/chip/selectExplore/refreshExplore 等事件
 * - 平台字符串 (my/system/fxDefault/错误格式化) 经构造函数注入; toast 副作用经 [onError] 注入
 */
class BookSourceDebugScreenModel(
    private val defaultTextMy: String,
    private val defaultTextFx: String,
    private val systemText: String,
    private val exploreErrorText: (String) -> String,
    private val exploreJsonErrorText: (Throwable) -> String,
) : ScreenModel, Debug.Callback {

    private val scope = screenModelScope("书源调试", IoDispatcher)

    private val logs = mutableStateListOf<String>()

    var exploreKinds: List<ExploreKind> = emptyList()
        private set

    /** 搜索框是否曾获得过焦点: 进入时旧界面焦点未释放, 首个 onFocusChanged(false) 不覆盖初值 helpVisible=true */
    private var hasFocusEver = false

    var bookSource: BookSource? = null
        private set

    var searchSrc: String? = null
        private set

    var bookSrc: String? = null
        private set

    var tocSrc: String? = null
        private set

    var contentSrc: String? = null
        private set

    var reviewSrc: String? = null
        private set

    /** startDebug 出错 (如 bookSource 为 null) 时触发, 由 Activity 注入 toast 副作用 */
    var onError: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(
        BookSourceDebugUiState(
            logs = logs,
            query = "",
            helpVisible = true,
            loading = false,
            textMy = defaultTextMy,
            textFx = defaultTextFx,
        )
    )
    val uiState: StateFlow<BookSourceDebugUiState> = _uiState.asStateFlow()

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

    /** 清焦信号 (对照 app 端 searchView.clearFocus()), 每次提交搜索都投递一次。 */
    private val _clearFocusFlow = signalFlow<Unit>()
    val clearFocusFlow: SharedFlow<Unit> = _clearFocusFlow.asSharedFlow()

    // ===== 调试状态机 (迁自 BookSourceDebugModel) =====

    fun init(sourceUrl: String?) {
        scope.launch(IoDispatcher) {
            try {
                // 优先取编辑页跳转前塞入的内存对象 (含未落库的最新编辑结果);
                // 书源管理页只带 url, 走 DB 兜底
                bookSource = IntentData.source as? BookSource
                    ?: sourceUrl?.let { AppDbProviders.get().bookSourceDao.getBookSource(it) }
            } finally {
                initHelpView()
            }
        }
    }

    private fun initHelpView() {
        bookSource?.searchRule?.checkKeyWord?.let {
            if (it.isNotBlank()) {
                _uiState.update { state -> state.copy(textMy = it) }
            }
        }
        initExploreKinds()
    }

    private fun initExploreKinds() {
        scope.launch(IoDispatcher) {
            try {
                val kinds = bookSource?.exploreKinds()?.filter { !it.url.isNullOrBlank() }.orEmpty()
                exploreKinds = kinds
                kinds.firstOrNull()?.let {
                    _uiState.update { state -> state.copy(textFx = "${it.title}::${it.url}") }
                    if (it.title.startsWith("ERROR:")) {
                        // 对照原版: 探索分类解析出错时隐藏帮助面板并收键盘,
                        // 重新点击搜索框可唤回 (对齐 app 端 SearchView.clearFocus 语义)
                        logs.add(exploreErrorText(it.url ?: ""))
                        _uiState.update { state -> state.copy(helpVisible = false) }
                        _clearFocusFlow.tryEmit(Unit)
                    }
                }
            } catch (e: NullPointerException) {
                logs.add(exploreJsonErrorText(e))
                _uiState.update { state -> state.copy(helpVisible = false) }
                _clearFocusFlow.tryEmit(Unit)
            }
        }
    }

    fun startDebug(key: String, start: (() -> Unit)? = null, error: (() -> Unit)? = null) {
        start?.invoke()
        scope.launch(IoDispatcher) {
            try {
                Debug.callback = this@BookSourceDebugScreenModel
                Debug.startDebug(this, bookSource!!, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error?.invoke()
            }
        }
    }

    override fun printLog(state: Int, msg: String) {
        when (state) {
            10 -> searchSrc = msg
            20 -> bookSrc = msg
            30 -> tocSrc = msg
            40 -> contentSrc = msg
            50 -> reviewSrc = msg
            else -> scope.launch(mainDispatcher) {
                logs.add(msg)
                if (state == -1 || state == 1000) {
                    _uiState.update { it.copy(loading = false) }
                }
            }
        }
    }

    override fun onCleared() {
        Debug.cancelDebug(true)
        scope.cancel()
    }

    // ===== 事件分发 =====

    fun dispatch(event: BookSourceDebugUiEvent) {
        when (event) {
            is BookSourceDebugUiEvent.Init -> init(event.sourceUrl)
            is BookSourceDebugUiEvent.QueryChange ->
                _uiState.update { it.copy(query = event.text) }

            BookSourceDebugUiEvent.SubmitQuery -> setQuery(_uiState.value.query, true)
            is BookSourceDebugUiEvent.SearchFocusChanged -> {
                if (event.focused) hasFocusEver = true
                // 从未获得过焦点前的首个失焦回调 (旧界面焦点未释放导致) 不覆盖初值 helpVisible=true;
                // 获得过焦点后 (提交/出错清焦、重新点击搜索框) 仍按焦点联动隐藏/唤回
                if (event.focused || hasFocusEver) {
                    _uiState.update { it.copy(helpVisible = event.focused) }
                }
            }

            BookSourceDebugUiEvent.ChipMyClick -> setQuery(_uiState.value.textMy, true)
            BookSourceDebugUiEvent.ChipSystemClick -> setQuery(systemText, true)
            BookSourceDebugUiEvent.ChipFxClick -> setQuery(_uiState.value.textFx, true)
            BookSourceDebugUiEvent.ChipDetailClick -> setQuery(_uiState.value.query, true)
            BookSourceDebugUiEvent.ChipTocClick -> prefixAutoComplete("++")
            BookSourceDebugUiEvent.ChipContentClick -> prefixAutoComplete("--")
            is BookSourceDebugUiEvent.SelectExplore -> {
                val explore = exploreKinds.getOrNull(event.index) ?: return
                val fx = "${explore.title}::${explore.url}"
                _uiState.update { it.copy(textFx = fx) }
                setQuery(fx, true)
            }

            BookSourceDebugUiEvent.RefreshExplore -> refreshExplore()
        }
    }

    private fun setQuery(text: String, submit: Boolean) {
        _uiState.update { it.copy(query = text) }
        if (submit) {
            // 对齐 app 端: 空白 query 用默认搜索词兜底, 始终走完整提交流程
            _uiState.update { it.copy(helpVisible = false) }
            _clearFocusFlow.tryEmit(Unit)
            startSearch(text.ifBlank { defaultTextMy })
        }
    }

    private fun prefixAutoComplete(prefix: String) {
        val q = _uiState.value.query
        if (q.isBlank() || q.length <= 2) {
            setQuery(prefix, false)
        } else {
            if (!q.startsWith(prefix)) {
                setQuery("$prefix$q", true)
            } else {
                setQuery(q, true)
            }
        }
    }

    private fun startSearch(key: String) {
        logs.clear()
        startDebug(
            key = key,
            start = { _uiState.update { it.copy(loading = true) } },
            error = { onError?.invoke() },
        )
    }

    private fun refreshExplore() {
        scope.launch(IoDispatcher) {
            bookSource?.clearExploreKindsCache()
            logs.clear()
            _uiState.update { it.copy(helpVisible = true) }
            initExploreKinds()
        }
    }
}

sealed interface BookSourceDebugUiEvent {
    data class Init(val sourceUrl: String?) : BookSourceDebugUiEvent
    data class QueryChange(val text: String) : BookSourceDebugUiEvent
    object SubmitQuery : BookSourceDebugUiEvent
    data class SearchFocusChanged(val focused: Boolean) : BookSourceDebugUiEvent
    object ChipMyClick : BookSourceDebugUiEvent
    object ChipSystemClick : BookSourceDebugUiEvent
    object ChipFxClick : BookSourceDebugUiEvent
    object ChipDetailClick : BookSourceDebugUiEvent
    object ChipTocClick : BookSourceDebugUiEvent
    object ChipContentClick : BookSourceDebugUiEvent
    data class SelectExplore(val index: Int) : BookSourceDebugUiEvent
    object RefreshExplore : BookSourceDebugUiEvent
}
