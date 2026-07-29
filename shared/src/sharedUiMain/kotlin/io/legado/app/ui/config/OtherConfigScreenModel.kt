package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ===== state / events =====

/**
 * 其它设置页 UI 状态 (immutable)。
 *
 * 7 个动态 summary (userAgent/bookTreeUri/checkSource/bitmapCache/preDownload/webPort/threadCount)
 * 由宿主在 OnSharedPreferenceChangeListener 回调或 Dialog 确认后通过
 * [OtherConfigScreenModel.dispatch] 推入 [OtherConfigUiEvent.UpdateXxxSummary];
 * 宿主负责解析平台资源 (R.string.pre_download_s 等) 后传字符串进来。
 *
 * @param userAgentSummary    User-Agent summary
 * @param bookTreeUriSummary  本地书籍目录 URI summary (SAF, 桌面端无)
 * @param checkSourceSummary  校验设置 summary
 * @param bitmapCacheSummary  图片缓存大小 summary
 * @param preDownloadSummary  预下载数量 summary
 * @param webPortSummary      Web 服务端口 summary
 * @param threadCountSummary  并发线程数 summary
 */
data class OtherConfigUiState(
    val userAgentSummary: String = "",
    val bookTreeUriSummary: String = "",
    val checkSourceSummary: String = "",
    val bitmapCacheSummary: String = "",
    val preDownloadSummary: String = "",
    val webPortSummary: String = "",
    val threadCountSummary: String = "",
)

/**
 * 其它设置页用户交互事件。
 *
 * - [UpdateUserAgentSummary] / [UpdateBookTreeUriSummary] / [UpdateCheckSourceSummary] /
 *   [UpdateBitmapCacheSummary] / [UpdatePreDownloadSummary] / [UpdateWebPortSummary] /
 *   [UpdateThreadCountSummary]: prefs 变更后宿主更新对应 summary
 * - 其余事件: 平台专属动作 (弹窗/NumberPicker/SAF/服务重启等) 由宿主注入 lambda 执行
 */
sealed interface OtherConfigUiEvent {
    data class UpdateUserAgentSummary(val summary: String) : OtherConfigUiEvent
    data class UpdateBookTreeUriSummary(val summary: String) : OtherConfigUiEvent
    data class UpdateCheckSourceSummary(val summary: String) : OtherConfigUiEvent
    data class UpdateBitmapCacheSummary(val summary: String) : OtherConfigUiEvent
    data class UpdatePreDownloadSummary(val summary: String) : OtherConfigUiEvent
    data class UpdateWebPortSummary(val summary: String) : OtherConfigUiEvent
    data class UpdateThreadCountSummary(val summary: String) : OtherConfigUiEvent
    object LocalPassword : OtherConfigUiEvent
    object UserAgent : OtherConfigUiEvent
    object BookTreeUri : OtherConfigUiEvent
    object CheckSource : OtherConfigUiEvent
    object UploadRule : OtherConfigUiEvent
    object BitmapCacheSize : OtherConfigUiEvent
    object PreDownloadNum : OtherConfigUiEvent
    object WebPort : OtherConfigUiEvent
    object CleanCache : OtherConfigUiEvent
    object ClearWebViewData : OtherConfigUiEvent
    object ShrinkDatabase : OtherConfigUiEvent
    object ThreadCount : OtherConfigUiEvent
    object CustomPageKey : OtherConfigUiEvent
}

// ===== ScreenModel =====

/**
 * 其它设置页 shared ScreenModel: 托管 [OtherConfigUiState], 通过 [dispatch] 处理事件。
 *
 * 平台专属动作 (本地密码/UA 编辑框/SAF 文档选取器/CheckSourceConfig Dialog/
 * DirectLinkUploadConfig Dialog/NumberPicker/清缓存/收缩数据库/WebView 数据清理/
 * 自定义翻页按键) 经构造函数 lambda 注入, 由宿主实现; shared 端不持有
 * Android Context / CheckSource / ImageProvider / WebService / PageKeyDialog。
 *
 * @param onLocalPassword      设置本地密码
 * @param onUserAgent          编辑 User-Agent
 * @param onBookTreeUri        选择本地书籍目录 (SAF)
 * @param onCheckSource        打开校验设置 Dialog
 * @param onUploadRule         打开直链上传规则 Dialog
 * @param onBitmapCacheSize    图片缓存大小 NumberPicker
 * @param onPreDownloadNum     预下载数量 NumberPicker
 * @param onWebPort            Web 服务端口 NumberPicker
 * @param onCleanCache         清缓存
 * @param onClearWebViewData   清 WebView 数据
 * @param onShrinkDatabase     收缩数据库
 * @param onThreadCount        并发线程数 NumberPicker
 * @param onCustomPageKey      自定义翻页按键 Dialog
 */
class OtherConfigScreenModel(
    private val onLocalPassword: () -> Unit,
    private val onUserAgent: () -> Unit,
    private val onBookTreeUri: () -> Unit,
    private val onCheckSource: () -> Unit,
    private val onUploadRule: () -> Unit,
    private val onBitmapCacheSize: () -> Unit,
    private val onPreDownloadNum: () -> Unit,
    private val onWebPort: () -> Unit,
    private val onCleanCache: () -> Unit,
    private val onClearWebViewData: () -> Unit,
    private val onShrinkDatabase: () -> Unit,
    private val onThreadCount: () -> Unit,
    private val onCustomPageKey: () -> Unit,
) : ScreenModel {

    private val _state = MutableStateFlow(OtherConfigUiState())
    val state: StateFlow<OtherConfigUiState> = _state.asStateFlow()

    fun dispatch(event: OtherConfigUiEvent) {
        when (event) {
            is OtherConfigUiEvent.UpdateUserAgentSummary ->
                _state.update { it.copy(userAgentSummary = event.summary) }

            is OtherConfigUiEvent.UpdateBookTreeUriSummary ->
                _state.update { it.copy(bookTreeUriSummary = event.summary) }

            is OtherConfigUiEvent.UpdateCheckSourceSummary ->
                _state.update { it.copy(checkSourceSummary = event.summary) }

            is OtherConfigUiEvent.UpdateBitmapCacheSummary ->
                _state.update { it.copy(bitmapCacheSummary = event.summary) }

            is OtherConfigUiEvent.UpdatePreDownloadSummary ->
                _state.update { it.copy(preDownloadSummary = event.summary) }

            is OtherConfigUiEvent.UpdateWebPortSummary ->
                _state.update { it.copy(webPortSummary = event.summary) }

            is OtherConfigUiEvent.UpdateThreadCountSummary ->
                _state.update { it.copy(threadCountSummary = event.summary) }

            OtherConfigUiEvent.LocalPassword -> onLocalPassword()
            OtherConfigUiEvent.UserAgent -> onUserAgent()
            OtherConfigUiEvent.BookTreeUri -> onBookTreeUri()
            OtherConfigUiEvent.CheckSource -> onCheckSource()
            OtherConfigUiEvent.UploadRule -> onUploadRule()
            OtherConfigUiEvent.BitmapCacheSize -> onBitmapCacheSize()
            OtherConfigUiEvent.PreDownloadNum -> onPreDownloadNum()
            OtherConfigUiEvent.WebPort -> onWebPort()
            OtherConfigUiEvent.CleanCache -> onCleanCache()
            OtherConfigUiEvent.ClearWebViewData -> onClearWebViewData()
            OtherConfigUiEvent.ShrinkDatabase -> onShrinkDatabase()
            OtherConfigUiEvent.ThreadCount -> onThreadCount()
            OtherConfigUiEvent.CustomPageKey -> onCustomPageKey()
        }
    }
}
