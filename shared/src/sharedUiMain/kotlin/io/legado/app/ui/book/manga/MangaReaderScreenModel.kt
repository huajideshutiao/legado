package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 漫画阅读页 shared ScreenModel: 适配 [MangaReaderViewModelShared] 各 StateFlow
 * 为统一 [MangaReaderUiState], 供 [MangaReaderScreenContent] 消费。
 *
 * 图片提取 ([MangaImageExtractor]) 依赖平台 BookHelp, 待下沉; 此处先空实现,
 * actual 平台注入后替换即可。其余章节状态/翻页/加载逻辑全部复用 shared VM。
 *
 * 信息条 (电池/时间/进度) 对照 [io.legado.app.ui.book.read.ReaderScreenModel]:
 * 平台通过 [Platform.getBatteryLevel] 提供电量, [refreshBattery] 由路由定时调用;
 * 系统时间由本类协程每分钟刷新。
 */
class MangaReaderScreenModel : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    interface Platform {
        fun flowImages(bookChapter: BookChapter, content: String): Flow<String>
        val config: MangaReaderConfig
            get() = MangaReaderConfig.DEFAULT

        /** 当前电池电量 0-100, -1 表示不显示 (对照 ReaderPlatformProvider.getBatteryLevel) */
        fun getBatteryLevel(): Int = -1

        /**
         * 保存图片到本地 (对照 app 端 BaseReadViewModel.saveImage)。
         * actual 平台下载源图并写入 [destPath], 返回是否成功。
         */
        suspend fun saveImage(
            url: String,
            book: Book?,
            source: io.legado.app.data.entities.BookSource?,
            destPath: String
        ): Boolean = false

        /** 切换横/纵向翻页 (对照 app 端 MangaMenuAction.HORIZONTAL_SCROLL = !enable), 返回切换后的值 */
        fun toggleHorizontal(): Boolean = false

        /** 持久化颜色滤镜配置 (对照 app 端 MangaColorFilterDialog.onDismiss 写 AppConfig.mangaColorFilter) */
        fun updateColorFilter(config: MangaColorFilterConfig) {}

        /** 持久化灰度开关 (对照 app 端 MangaColorFilterDialog.upGray 写 AppConfig.enableMangaGray) */
        fun updateGray(enable: Boolean) {}

        /** 持久化页脚配置 (对照 app 端 MangaFooterSettingDialog.onDismiss 写 AppConfig.mangaFooterConfig) */
        fun updateFooterConfig(config: MangaFooterConfig) {}

        /** 切换隐藏漫画标题 (对照 app 端 MangaMenuAction.HIDE_TITLE = !enable), 返回切换后的值 */
        fun toggleHideTitle(): Boolean = false

        /** 切换禁用翻页动画 (对照 app 端 MangaMenuAction.DISABLE_PAGE_ANIM = !enable), 返回切换后的值 */
        fun toggleDisablePageAnim(): Boolean = false

        /** 切换 GIF 播完翻页 (对照 app 端 MangaMenuAction.GIF_AUTO_NEXT = !enable), 返回切换后的值 */
        fun toggleGifAutoNext(): Boolean = false

        @Composable
        fun Image(
            url: String,
            modifier: Modifier,
            horizontal: Boolean,
            book: Book?,
            source: io.legado.app.data.entities.BookSource?,
            colorFilterConfig: MangaColorFilterConfig,
            grayEnabled: Boolean,
        )
    }

    object Providers {
        @Volatile
        private var impl: Platform? = null
        fun register(platform: Platform) {
            impl = platform
        }

        fun getOrNull(): Platform? = impl
    }

    private val platform = Providers.getOrNull()
    private val imageExtractor = platform?.let { p ->
        object : MangaImageExtractor {
            override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
                p.flowImages(bookChapter, content)
        }
    } ?: object : MangaImageExtractor {
        override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
            MangaImageExtractorShared.extractImageUrls(content).asFlow()
    }

    private val shared = MangaReaderViewModelShared(
        scope = scope,
        imageExtractor = imageExtractor,
        config = platform?.config ?: MangaReaderConfig.DEFAULT,
    )

    private val _state = MutableStateFlow(
        MangaReaderUiState(
            horizontal = (platform?.config ?: MangaReaderConfig.DEFAULT).horizontal,
            autoPageSpeed = (platform?.config ?: MangaReaderConfig.DEFAULT).autoPageSpeed,
            colorFilterConfig = (platform?.config ?: MangaReaderConfig.DEFAULT).colorFilterConfig,
            grayEnabled = (platform?.config ?: MangaReaderConfig.DEFAULT).grayEnabled,
            footerConfig = (platform?.config ?: MangaReaderConfig.DEFAULT).footerConfig,
            hideMangaTitle = (platform?.config ?: MangaReaderConfig.DEFAULT).hideMangaTitle,
            disablePageAnim = (platform?.config ?: MangaReaderConfig.DEFAULT).disablePageAnim,
            gifAutoNext = (platform?.config ?: MangaReaderConfig.DEFAULT).gifAutoNext,
        )
    )
    val state: StateFlow<MangaReaderUiState> = _state.asStateFlow()
    val currentBook: Book? get() = shared.book.value
    val currentSource get() = shared.bookSource.value
    val platformRenderer: Platform? get() = platform
    val readerConfig: MangaReaderConfig get() = platform?.config ?: MangaReaderConfig.DEFAULT

    // 信息条: 电池电量 (对照 ReaderScreenModel._batteryLevel)
    private val _batteryLevel = MutableStateFlow(platform?.getBatteryLevel() ?: -1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    // 信息条: 系统时间, 每分钟刷新
    private val _systemTime = MutableStateFlow(formatTime())
    val systemTime: StateFlow<String> = _systemTime.asStateFlow()

    init {
        // 合并 shared 各 StateFlow → 统一 UiState; chapterSize 为普通字段, 随内容流变化时读取
        combine(
            shared.book, shared.durChapter, shared.mangaContent,
            shared.durChapterIndex, shared.loading,
        ) { book, durChapter, mangaContent, durChapterIndex, loading ->
            val images = mangaContent?.items
                ?.filterIsInstance<MangaPage>()
                ?.map { it.mImageUrl }
                ?: emptyList()
            val imageCount = shared.currentImageCount
            _state.value.copy(
                bookName = book?.name ?: "",
                chapterTitle = durChapter?.title ?: "",
                images = images,
                curChapterIndex = durChapterIndex,
                chapterSize = shared.chapterSize,
                currentPage = shared.durChapterPos.value.coerceIn(
                    0,
                    (imageCount - 1).coerceAtLeast(0)
                ),
                pageCount = imageCount,
                loading = loading,
            )
        }.combine(shared.error) { uiState, error ->
            uiState.copy(error = error?.first)
        }.combine(shared.durChapterPos) { uiState, pos ->
            val imageCount = shared.currentImageCount
            uiState.copy(
                currentPage = pos.coerceIn(0, (imageCount - 1).coerceAtLeast(0)),
                progressPercent = computeProgress(
                    uiState.curChapterIndex, uiState.chapterSize, pos, imageCount
                ),
            )
        }.onEach { _state.value = it }.launchIn(scope)

        // 系统时间每分钟刷新 (对照 app 端 ACTION_TIME_TICK 广播)
        scope.launch {
            while (isActive) {
                delay(60_000L)
                _systemTime.value = formatTime()
            }
        }
    }

    /** 平台收到电量变化时调用, 更新信息条电量 */
    fun refreshBattery() {
        _batteryLevel.value = platform?.getBatteryLevel() ?: -1
    }

    /** 切换横/纵向翻页 (对照 app 端 MangaMenuAction.HORIZONTAL_SCROLL) */
    fun toggleHorizontal() {
        val newHorizontal = platform?.toggleHorizontal() ?: return
        _state.value = _state.value.copy(horizontal = newHorizontal)
    }

    /** 更新颜色滤镜配置并持久化 (对照 app 端 MangaColorFilterDialog.Callback.updateColorFilter) */
    fun updateColorFilter(config: MangaColorFilterConfig) {
        platform?.updateColorFilter(config)
        _state.value = _state.value.copy(colorFilterConfig = config)
    }

    /** 更新灰度开关并持久化 (对照 app 端 MangaColorFilterDialog.upGray) */
    fun updateGray(enable: Boolean) {
        platform?.updateGray(enable)
        _state.value = _state.value.copy(grayEnabled = enable)
    }

    /** 更新页脚配置并持久化 (对照 app 端 MangaFooterSettingDialog.onDismiss) */
    fun updateFooterConfig(config: MangaFooterConfig) {
        platform?.updateFooterConfig(config)
        _state.value = _state.value.copy(footerConfig = config)
    }

    /** 切换隐藏漫画标题 (对照 app 端 MangaMenuAction.HIDE_TITLE), 触发 shared 重新加载章节内容 */
    fun toggleHideTitle() {
        val newHide = platform?.toggleHideTitle() ?: return
        _state.value = _state.value.copy(hideMangaTitle = newHide)
        // 重新加载当前章节, 让 ReaderLoading 头按新配置增减 (对照 app 端 viewModel.loadContent)
        shared.loadContent()
    }

    /** 切换禁用翻页动画 (对照 app 端 MangaMenuAction.DISABLE_PAGE_ANIM) */
    fun toggleDisablePageAnim() {
        val newDisable = platform?.toggleDisablePageAnim() ?: return
        _state.value = _state.value.copy(disablePageAnim = newDisable)
    }

    /** 切换 GIF 播完翻页 (对照 app 端 MangaMenuAction.GIF_AUTO_NEXT) */
    fun toggleGifAutoNext() {
        val newEnable = platform?.toggleGifAutoNext() ?: return
        _state.value = _state.value.copy(gifAutoNext = newEnable)
    }

    /** 构造当前阅读位置书签 (对照 ReadMangaActivity.addBookmark) */
    fun buildBookmark(): Bookmark? {
        val book = currentBook ?: return null
        val chapterIndex = shared.durChapterIndex.value
        val pos = shared.durChapterPos.value
        val imageCount = shared.currentImageCount
        val chapterName = shared.durChapter.value?.title ?: book.durChapterTitle ?: ""
        return Bookmark(bookName = book.name, bookAuthor = book.author).apply {
            this.chapterIndex = chapterIndex
            this.chapterPos = pos
            this.chapterName = chapterName
            this.bookText =
                "第${pos + 1}页 / 共${if (imageCount > 0) "${imageCount}页" else "未知"}"
        }
    }

    private fun computeProgress(
        chapterIndex: Int,
        chapterSize: Int,
        pos: Int,
        imageCount: Int
    ): String {
        if (chapterSize == 0) return "0.0%"
        if (imageCount == 0) {
            return "%.1f%%".format((chapterIndex + 1.0) / chapterSize * 100)
        }
        var percent = "%.1f%%".format(
            (chapterIndex * 1.0 / chapterSize + 1.0 / chapterSize * (pos + 1) / imageCount) * 100
        )
        if (percent == "100.0%" && (chapterIndex + 1 != chapterSize || pos + 1 != imageCount)) {
            percent = "99.9%"
        }
        return percent
    }

    private fun formatTime(): String =
        formatTimeOfDay(systemCurrentTimeMillis())

    fun dispatch(event: MangaReaderUiEvent) {
        when (event) {
            is MangaReaderUiEvent.Init -> {
                // shared.initData 从 IntentData.book 取书
                IntentData.book = event.book
                // 对照 app 端 applyBookmarkPosition: chapterIndex>=0 时跳转到指定章节位置
                shared.initData(
                    overrideIndex = event.chapterIndex ?: -1,
                    overridePos = event.chapterPos ?: 0,
                )
            }
            // toFirst=true: 对照 Activity 点击区域 action 3/4, 用户主动切章跳首页+显示 loading
            MangaReaderUiEvent.NextChapter -> shared.moveToNextChapter(true)
            MangaReaderUiEvent.PrevChapter -> shared.moveToPrevChapter(true)
            is MangaReaderUiEvent.OpenChapter -> shared.openChapter(event.index, event.position)
            MangaReaderUiEvent.Retry -> shared.loadOrUpContent()
            // 刷新当前章: 删缓存后重载 (对照 app 端 MangaMenuAction.REFRESH)
            MangaReaderUiEvent.Refresh -> currentBook?.let { shared.refreshContentDur(it) }
            // 换源回填: 复用 shared.onSourceChanged 直接装载新源/目录
            is MangaReaderUiEvent.ChangeSource -> {
                IntentData.book = event.book
                scope.launch {
                    shared.onSourceChanged(event.book, event.toc)
                }
            }
        }
    }

    override fun onCleared() {
        shared.onCleared()
        scope.cancel()
    }
}

/** 漫画阅读页 UI 状态, 字段对齐 [MangaReaderScreenContent] 入参。 */
data class MangaReaderUiState(
    val bookName: String = "",
    val chapterTitle: String = "",
    val images: List<String> = emptyList(),
    val curChapterIndex: Int = 0,
    val chapterSize: Int = 0,
    val horizontal: Boolean = false,
    val autoPageSpeed: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 0,
    val pageCount: Int = 0,
    val progressPercent: String = "0.0%",
    val colorFilterConfig: MangaColorFilterConfig = MangaColorFilterConfig(),
    val grayEnabled: Boolean = false,
    val footerConfig: MangaFooterConfig = MangaFooterConfig(),
    val hideMangaTitle: Boolean = false,
    val disablePageAnim: Boolean = false,
    val gifAutoNext: Boolean = false,
)

/** ScreenModel 可处理的 UI 事件 (平台相关回调如 onBack/onOpenToc 仍走 Route)。 */
sealed interface MangaReaderUiEvent {
    /**
     * 初始化书籍 (Route 解析 BookRef.asBook() 后注入)。
     *
     * @param chapterIndex 书签跳转目标章节索引, null 表示不覆盖 (对应 app 端 intent chapterIndex 缺省)
     * @param chapterPos 书签跳转目标章节位置, 仅 chapterIndex 非空时生效 (对应 app 端 intent chapterPos)
     */
    data class Init(
        val book: Book,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : MangaReaderUiEvent

    /** 下一章 */
    object NextChapter : MangaReaderUiEvent

    /** 上一章 */
    object PrevChapter : MangaReaderUiEvent
    data class OpenChapter(val index: Int, val position: Int = 0) : MangaReaderUiEvent

    /** 错误重试 */
    object Retry : MangaReaderUiEvent

    /** 刷新当前章节 (对照 app 端 MangaMenuAction.REFRESH → viewModel.refreshContentDur) */
    object Refresh : MangaReaderUiEvent

    /**
     * 整书换源回填 (对应 RouteResults.CHANGE_SOURCE 回传 source + book + toc)。
     * 复用 [MangaReaderViewModelShared.onSourceChanged] 装载新源/目录并重新加载内容。
     */
    data class ChangeSource(
        val book: Book,
        val toc: List<BookChapter>,
    ) : MangaReaderUiEvent
}
