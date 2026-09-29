package io.legado.app.ui.toolbox

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookLike
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

private const val KEY_SOURCE = "sourceToolbox_source"
private const val KEY_STAGE = "sourceToolbox_stage"
private const val KEY_URL = "sourceToolbox_url"
private const val KEY_INPUT = "sourceToolbox_input"

/** 规则/JS 的阶段环境, 对齐 WebBook 各阶段真实执行时的绑定 (key/page 变量在请求 URL 层由用户自填, 不在此注入) */
enum class ToolboxStage {
    /** 不绑 ruleData, 规则内 java.put/get 变量落在书源变量缓存 */
    GENERAL,

    /** 不绑 ruleData, 变量落在书源变量缓存 */
    SEARCH,

    /** 同搜索 */
    EXPLORE,

    /** 会话 Book, 阶段间共享; 解析不写回字段, JS 可经 book 绑定改写 */
    DETAIL,

    /** 会话 Book, 与详情阶段同一实例 */
    TOC,

    /** 会话 Book + 伪章节, 绑定给 JS 与规则解析 */
    CONTENT,
}

enum class ToolboxRuleMode { TEXT, TEXT_LIST, ELEMENTS }

data class ToolboxUiState(
    val sources: List<BookSource> = emptyList(),
    val sourceName: String = "",
    val stage: ToolboxStage = ToolboxStage.GENERAL,
    val urlText: String = "",
    val inputText: String = "",
    /** 最近一次请求的响应体, 也是规则区/JS 区的 result */
    val resBody: String = "",
    val resCode: Int = 0,
    val resMessage: String = "",
    val resElapsedMs: Long = 0,
    val resError: String? = null,
    val resIsJson: Boolean = false,
    val resTreeMode: Boolean = false,
    /** 请求进行中: 请求按钮禁用并显示转圈 */
    val busy: Boolean = false,
    /** 最近一次动作的结果 (规则解析多行 / JS 返回值单行) */
    val result: List<String> = emptyList(),
    val resultError: String? = null,
)

sealed interface ToolboxUiEvent {
    data class SelectSource(val source: BookSource) : ToolboxUiEvent
    data object ClearSource : ToolboxUiEvent
    data class StageChange(val stage: ToolboxStage) : ToolboxUiEvent
    data class UrlChange(val text: String) : ToolboxUiEvent
    data class InputChange(val text: String) : ToolboxUiEvent
    data object Request : ToolboxUiEvent
    data class RunRule(val mode: ToolboxRuleMode) : ToolboxUiEvent
    data object RunJs : ToolboxUiEvent
    data class ResTreeMode(val on: Boolean) : ToolboxUiEvent
}

/**
 * 书源工具箱页面状态: 请求 (AnalyzeUrl 全语法) / 规则解析 (AnalyzeRule 全语法) / JS 运行 (书源 jsLib)
 * 三区共用所选书源与阶段绑定; 除暂存响应体外全部输入经 CacheManager 持久化。
 *
 * 会话 [sessionBook] 在详情/目录/正文阶段间共享同一实例; 解析结果不写回字段,
 * JS 可经 book 绑定改写。
 * 页面退出随 ScreenModel 释放, 暂存与会话书不持久化。
 */
class SourceToolboxScreenModel(
    private val toast: (String) -> Unit,
    private val noResponseText: String,
    private val sourceClearedText: String,
) : ScreenModel {

    private val scope = screenModelScope("书源工具箱")

    private val _state = MutableStateFlow(ToolboxUiState())
    val uiState: StateFlow<ToolboxUiState> = _state.asStateFlow()

    @Volatile
    private var selectedSource: BookSource? = null

    /** 详情规则写回 → 目录/正文规则读回的会话书 */
    private val sessionBook = Book()

    /** 正文阶段的伪章节 */
    private val pseudoChapter = ToolboxPseudoChapter()

    /** 未选书源时的 evalJS 载体 (jsLib 为空走独立 scope), 不入库; 保留实例使 JS 绑定身份稳定,
     *  JS 对该实例的写入 (变量/请求头) 会跨运行残留 */
    private val fallbackSource = BookSource()

    /** 互斥执行: 新动作取消上一个 (请求/规则/JS 共用) */
    private var job: Job? = null

    /** 低频动作 (选源/切阶段) 的落盘: CacheManager 写是阻塞 DB 操作, 不在 dispatch 线程直调 */
    private fun persistNow(key: String, value: String) {
        scope.launch(IoDispatcher) { CacheManager.put(key, value) }
    }

    @Volatile
    private var lastResponseBody: String? = null

    init {
        // CacheManager 读写阻塞 DB, 恢复草稿与查库一起在 IO 线程做
        scope.launch(IoDispatcher) {
            val savedUrl = CacheManager.get(KEY_URL)
            val savedInput = CacheManager.get(KEY_INPUT)
            val savedStage = CacheManager.get(KEY_STAGE)
            val savedSource = CacheManager.get(KEY_SOURCE)
            update {
                it.copy(
                    urlText = savedUrl.orEmpty(),
                    inputText = savedInput.orEmpty(),
                    stage = ToolboxStage.entries.find { stage -> stage.name == savedStage }
                        ?: ToolboxStage.GENERAL,
                )
            }
            val sources = AppDbProviders.get().bookSourceDao.all()
            update { it.copy(sources = sources) }
            selectedSource = sources.find { source -> source.getKey() == savedSource }
            selectedSource?.let { applySource(it) }
        }
    }

    fun dispatch(event: ToolboxUiEvent) {
        when (event) {
            is ToolboxUiEvent.SelectSource -> {
                selectedSource = event.source
                applySource(event.source)
                persistNow(KEY_SOURCE, event.source.getKey())
            }

            ToolboxUiEvent.ClearSource -> {
                selectedSource = null
                update { it.copy(sourceName = "") }
                persistNow(KEY_SOURCE, "")
                toast(sourceClearedText)
            }

            is ToolboxUiEvent.StageChange -> {
                update { it.copy(stage = event.stage) }
                persistNow(KEY_STAGE, event.stage.name)
            }

            is ToolboxUiEvent.UrlChange -> update { it.copy(urlText = event.text) }

            is ToolboxUiEvent.InputChange -> update { it.copy(inputText = event.text) }

            ToolboxUiEvent.Request -> request()
            is ToolboxUiEvent.RunRule -> runRule(event.mode)
            ToolboxUiEvent.RunJs -> runJs()
            is ToolboxUiEvent.ResTreeMode -> update { it.copy(resTreeMode = event.on) }
        }
    }

    private fun applySource(source: BookSource) {
        update { it.copy(sourceName = source.bookSourceName) }
    }

    private fun update(block: (ToolboxUiState) -> ToolboxUiState) {
        _state.value = block(_state.value)
    }

    /** 当前阶段的规则/JS 绑定数据 (null = 不绑定) */
    private fun stageRuleData(): RuleDataInterface? = when (_state.value.stage) {
        ToolboxStage.DETAIL, ToolboxStage.TOC, ToolboxStage.CONTENT -> sessionBook
        else -> null
    }

    private fun stageChapter(): BookChapterLike? =
        if (_state.value.stage == ToolboxStage.CONTENT) pseudoChapter else null

    private fun runAction(
        onError: (Throwable) -> Unit,
        block: suspend () -> Unit,
    ) {
        job?.cancel()
        var current: Job? = null
        current = scope.launch(IoDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                // 仅当被取消的仍是当前动作才复位 busy: 防旧动作的取消复位覆盖新请求的置位
                if (job === current) update { it.copy(busy = false) }
                throw e
            } catch (e: Throwable) {
                onError(e)
            }
        }
        job = current
    }

    private fun request() {
        val urlText = _state.value.urlText
        // 空 (纯空白) URL 无请求目标, 直接不动作
        if (urlText.isBlank()) return
        update { it.copy(busy = true) }
        runAction(
            onError = { e ->
                // 界面响应区已显示为错误, 不再让规则/JS 在旧响应体上执行
                lastResponseBody = null
                update {
                    it.copy(
                        busy = false,
                        resError = e.toString(),
                        resCode = 0,
                        resBody = "",
                        resIsJson = false,
                    )
                }
            },
        ) {
            // 输入草稿在触发运行时才落盘, 编辑过程不写库
            CacheManager.put(KEY_URL, urlText)
            val source = selectedSource
            val start = TimeSource.Monotonic.markNow()
            val analyzeUrl = AnalyzeUrlFactories.create(
                rawUrl = urlText,
                baseUrl = source?.getKey().orEmpty(),
                source = source,
                ruleData = stageRuleData(),
                chapter = stageChapter(),
                coroutineContext = currentCoroutineContext(),
            )
            val res = analyzeUrl.getStrResponseAwait()
            val body = res.body.orEmpty()
            lastResponseBody = res.body
            update {
                it.copy(
                    busy = false,
                    resCode = res.code(),
                    resMessage = res.message(),
                    resElapsedMs = start.elapsedNow().inWholeMilliseconds,
                    resBody = body,
                    resError = null,
                    resTreeMode = false,
                    // 与树视图宽松解析同口径: 宽松合法 JSON 也开树, 严格失败走树内错误提示
                    resIsJson = runCatching { KS_JSON.parseToJsonElement(body) }.isSuccess,
                )
            }
        }
    }

    private fun runRule(mode: ToolboxRuleMode) {
        val inputText = _state.value.inputText
        val body = lastResponseBody
        if (body == null) {
            toast(noResponseText)
            return
        }
        runAction(
            onError = { e ->
                update { it.copy(result = emptyList(), resultError = e.toString()) }
            },
        ) {
            CacheManager.put(KEY_INPUT, inputText)
            val source = selectedSource
            val rule = AnalyzeRuleFactories.create(stageRuleData(), source, false)
            rule.setContent(body, source?.getKey().orEmpty())
            if (_state.value.stage == ToolboxStage.CONTENT) rule.chapter = pseudoChapter
            val result = when (mode) {
                ToolboxRuleMode.TEXT -> listOf(rule.getString(inputText))
                ToolboxRuleMode.TEXT_LIST -> rule.getStringList(inputText).orEmpty()
                ToolboxRuleMode.ELEMENTS -> rule.getElements(inputText).map { it.toString() }
            }
            update { it.copy(result = result, resultError = null) }
        }
    }

    private fun runJs() {
        val inputText = _state.value.inputText
        runAction(
            onError = { e ->
                update { it.copy(result = emptyList(), resultError = e.toString()) }
            },
        ) {
            CacheManager.put(KEY_INPUT, inputText)
            val source = selectedSource ?: fallbackSource
            val body = lastResponseBody
            val out = source.evalJS(inputText) {
                this["result"] = body
                this["book"] = stageRuleData() as? BookLike
                this["chapter"] = stageChapter()
            }
            val result = out?.toString().orEmpty()
            update {
                it.copy(
                    result = if (result.isEmpty()) emptyList() else listOf(result),
                    resultError = null,
                )
            }
        }
    }
}

private class ToolboxPseudoChapter : BookChapterLike {
    override val title: String = ""
    override val variableMap = hashMapOf<String, String>()

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap[key] = value
        }
    }

    override fun getBigVariable(key: String): String? = variableMap[key]
}
