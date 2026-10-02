package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.book.changeSourceTo
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.chapter.ChapterLoadState
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.config.ClickActionConfig
import io.legado.app.ui.book.read.page.provider.ChapterContentParserShared
import io.legado.app.ui.book.read.page.readClickActionConfig
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.GSON
import io.legado.app.utils.format
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toJson
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** 正文 <img> src 提取 (对照 app 端 BookHelp.flowImages: 同一解析器 + 跳过空 src)。 */
private fun sharedFlowImages(content: String): Flow<String> =
    ChapterContentParserShared.extractImages(content)
        .map { it.src }
        .filter { it.isNotBlank() }
        .asFlow()

/**
 * 漫画阅读页 shared ScreenModel: 适配 [MangaReaderViewModelShared] 各 StateFlow
 * 为统一 [MangaReaderUiState], 供 [MangaReaderScreenContent] 消费。
 *
 * 图片提取 ([MangaImageExtractor]) 默认走 [ChapterContentParserShared], 平台可覆写
 * (app 端走 BookHelp.flowImages)。其余章节状态/翻页/加载逻辑全部复用 shared VM。
 *
 * 信息条 (电池/时间/进度) 对照 [io.legado.app.ui.book.read.ReaderScreenModel]:
 * 平台通过 [Platform.getBatteryLevel] 提供电量, [refreshBattery] 由路由定时调用;
 * 系统时间由本类协程每分钟刷新。
 */
class MangaReaderScreenModel : ScreenModel {

    private val scope = screenModelScope("漫画阅读")

    interface Platform {
        fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
            sharedFlowImages(content)

        /**
         * 当前电池电量 0-100。
         * 读取失败/无电池统一回落 100 (用户拍板 2026-08: 电量恒显示; 台式机 AC 供电视为满电),
         * 不再使用 -1=不显示语义 (原 app/shared 契约, 与 desktop 100 不一致, 已统一)。
         */
        fun getBatteryLevel(): Int = 100

        /**
         * 保存图片到本地 (对照 app 端 BaseReadViewModel.saveImage)。
         * 平台先取得原始字节，再按实际格式生成文件名，最后调用平台文件选择器。
         * 返回: true=保存成功, false=保存失败, null=用户取消选择 (静默不提示)。
         */
        suspend fun saveImage(
            url: String,
            book: Book?,
            source: BookSource?,
        ): Boolean? = false

        /**
         * 预加载图片到内存缓存 (对照 app 端 Coil3 memoryCachePolicy(WRITE_ONLY) 预载)。
         * 默认空实现: 不支持预载的平台照常按需加载。
         */
        suspend fun preloadImage(
            url: String,
            book: Book,
            source: BookSource?
        ) {
        }

        @Composable
        fun Image(
            url: String,
            modifier: Modifier,
            horizontal: Boolean,
            book: Book?,
            source: BookSource?,
            colorFilterConfig: MangaColorFilterConfig,
            grayEnabled: Boolean,
            onLoadState: (MangaCellState) -> Unit,
            retryTick: Int,
            onProgress: (String) -> Unit,
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

    private val platform get() = Providers.getOrNull()
    private val imageExtractor = platform.let { p ->
        object : MangaImageExtractor {
            override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
                p?.flowImages(bookChapter, content) ?: sharedFlowImages(content)
        }
    }

    /**
     * 漫画阅读配置快照: 直读 prefs (key 与默认值同 app 端 AppConfig 的漫画项)。
     * 每次求值都重读, 菜单改完立刻生效。
     */
    val readerConfig: MangaReaderConfig
        get() {
            val prefs = PreferenceProviders.get()
            return MangaReaderConfig(
                hideMangaTitle = prefs.getBoolean(PreferKey.hideMangaTitle, false),
                preDownloadNum = prefs.getInt(PreferKey.mangaPreDownloadNum, 10),
                syncBookProgressPlus = prefs.getBoolean(PreferKey.syncBookProgressPlus, false),
                horizontal = prefs.getBoolean(PreferKey.enableMangaHorizontalScroll, false),
                autoPageSpeed = prefs.getInt(PreferKey.mangaAutoPageSpeed, 3),
                grayEnabled = prefs.getBoolean(PreferKey.enableMangaGray, false),
                colorFilterConfig = GSON.fromJsonObject<MangaColorFilterConfig>(
                    prefs.getString(PreferKey.mangaColorFilter, "")
                ).getOrNull() ?: MangaColorFilterConfig(),
                gifAutoNext = prefs.getBoolean(PreferKey.enableMangaGifAutoNext, false),
                disablePageAnim = prefs.getBoolean(PreferKey.disableMangaPageAnim, false),
                footerConfig = GSON.fromJsonObject<MangaFooterConfig>(
                    prefs.getString(PreferKey.mangaFooterConfig, "")
                ).getOrNull() ?: MangaFooterConfig(),
            )
        }

    private val shared = MangaReaderViewModelShared(
        scope = scope,
        imageExtractor = imageExtractor,
        config = readerConfig,
    )

    // 初始 UI 状态与 shared 的配置快照同一份, 不重读一遍 prefs
    private val _state = MutableStateFlow(
        shared.config.let { config ->
            MangaReaderUiState(
                horizontal = config.horizontal,
                autoPageSpeed = config.autoPageSpeed,
                colorFilterConfig = config.colorFilterConfig,
                grayEnabled = config.grayEnabled,
                footerConfig = config.footerConfig,
                hideMangaTitle = config.hideMangaTitle,
                disablePageAnim = config.disablePageAnim,
                gifAutoNext = config.gifAutoNext,
                preDownloadNum = config.preDownloadNum,
                clickActionConfig = readClickActionConfig(),
            )
        }
    )
    val state: StateFlow<MangaReaderUiState> = _state.asStateFlow()
    val currentBook: Book? get() = shared.book.value

    /** 当前章节表 (供目录页经 IntentData 传递, 对照原版 chapterListData) */
    val chapterList: List<BookChapter> get() = shared.chapterList.value
    val currentChapter: BookChapter? get() = shared.durChapter.value
    val currentSource get() = shared.bookSource.value
    val platformRenderer: Platform? get() = platform

    // 信息条: 电池电量 (对照 ReaderScreenModel._batteryLevel; 平台缺失回落 100 恒显示)
    private val _batteryLevel = MutableStateFlow(platform?.getBatteryLevel() ?: 100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    // 信息条: 系统时间, 每分钟刷新
    private val _systemTime = MutableStateFlow(formatTime())
    val systemTime: StateFlow<String> = _systemTime.asStateFlow()

    init {
        // VM 状态流 → UiState 的增量合并。
        // 必须走 update{} 原子读改写: scope 是 Dispatchers.Default 线程池, 本收集器与
        // errorMsg/durChapterPos 两个收集器、以及 UI 线程的 dispatch() 直写并发操作同一个
        // _state。原来 `_state.value = <combine 内 copy 出的快照>` 是非原子读改写, 会整字段
        // 丢更新 (丢掉 horizontal 就是"翻页后横竖模式跳回")。
        // contentEpoch 必须参与本次发射: 它是「内容确实重建了」的身份信号 (身份而非内容,
        // 见 MangaReaderViewModelShared.contentEpoch)。内容层据此重启定位 effect —— 光靠
        // items 不行, 列表是 data class 实例, 新旧逐元素相等时 Compose 的 key 判等成立,
        // effect 不重启, 定位请求就永远没人消费。
        combine(
            shared.book, shared.durChapter,
            combine(shared.mangaContent, shared.contentEpoch) { content, epoch -> content to epoch },
            shared.durChapterIndex, shared.loadState,
        ) { book, durChapter, (mangaContent, contentEpoch), durChapterIndex, loadState ->
            // VM 侧的值在发射时刻取好, 不留到 update{} 里读 (update 失败重试会重复读)
            val imageCount = shared.currentImageCount
            val chapterSize = shared.chapterSize
            val durChapterPos = shared.durChapterPos.value
            // 有评论规则才显示"评论"菜单项 (对照 app 端 onMenuOpened 里 reviewUrl 判空)
            val hasReview = shared.bookSource.value?.reviewRule?.reviewUrl.isNullOrBlank() == false
            val items = mangaContent?.items?.filterIsInstance<BaseMangaPage>() ?: emptyList()
            val merge: (MangaReaderUiState) -> MangaReaderUiState = { cur ->
                cur.copy(
                    bookName = book?.name ?: "",
                    chapterTitle = durChapter?.title ?: "",
                    items = items,
                    curFinish = mangaContent?.curFinish == true,
                    curChapterIndex = durChapterIndex,
                    chapterSize = chapterSize,
                    currentPage = durChapterPos.coerceIn(0, (imageCount - 1).coerceAtLeast(0)),
                    pageCount = imageCount,
                    loadState = loadState,
                    hasReview = hasReview,
                    contentEpoch = contentEpoch,
                    contentBookUrl = mangaContent?.bookUrl.orEmpty(),
                    contentGeneration = mangaContent?.generation ?: 0,
                )
            }
            merge
        }.onEach { merge -> _state.update(merge) }.launchIn(scope)

        // 章内页码/进度 → state: 独立增量写, 翻页 (durChapterPos 发射) 不覆盖
        // horizontal/positionRequest 等直写字段 (原 3 级 combine 链 stage-2 丢弃 stage-1 输出,
        // 导致 items/bookName 永不更新; 此处拆链修复)
        scope.launch {
            shared.durChapterPos.collect { pos ->
                val imageCount = shared.currentImageCount
                _state.update {
                    it.copy(
                        currentPage = pos.coerceIn(0, (imageCount - 1).coerceAtLeast(0)),
                        progressPercent = computeProgress(
                            it.curChapterIndex, it.chapterSize, pos, imageCount
                        ),
                    )
                }
            }
        }

        // 系统时间刷新 (对照 ReaderScreenModel: 平台广播经 ReadBookEvents.timeChanged 推送,
        // 订阅者更新 StateFlow 驱动信息条重组; 无 ACTION_TIME_TICK 的平台由下方轮询兜底)
        scope.launch {
            ReadBookEvents.timeChanged.collect { _systemTime.value = formatTime() }
        }
        // 无 ACTION_TIME_TICK 的平台 (桌面/iOS/鸿蒙) 整分兜底刷新:
        // 对齐原版 TimeBatteryReceiver 的 ACTION_TIME_TICK 整分语义 —— 首次 delay 等到下个整分,
        // 之后每整分刷新; 若从 model 创建时刻起算, 刷新点漂移会导致时间周期性滞后最多 59 秒
        scope.launch {
            delay(60_000L - systemCurrentTimeMillis() % 60_000L)
            while (isActive) {
                _systemTime.value = formatTime()
                // 桌面端无 BATTERY_CHANGED 广播, 电池随同一整分轮询读取
                refreshBattery()
                delay(60_000L)
            }
        }
    }

    /** 平台收到电量变化时调用, 更新信息条电量 */
    fun refreshBattery() {
        _batteryLevel.value = platform?.getBatteryLevel() ?: 100
    }

    /**
     * 居中页变化 (对照 app 端 ReadMangaActivity.initRenderLayer 的 onCenterItemChanged):
     * 跨到相邻章即切章, 同章则同步章内页码并触发预下载。
     */
    fun onCenterItemChanged(item: BaseMangaPage, reanchored: Boolean = false) {
        val durIndex = shared.durChapterIndex.value
        // reanchored = items 重建后 LazyList 按 key 把视口锚回同一张图, 不是用户滚动:
        // 据此跨章会把刚切到的章立刻拽回去 (章节点了不跳的病根)。但页码有效且必须同步 ——
        // moveToNext/PrevChapter(toFirst=false) 不重置 durChapterPos, 跨章后靠这次上报归零,
        // 吞掉它页脚会停在上一章的末页页码。
        if (reanchored) {
            if (durIndex == item.chapterIndex) {
                shared.setDurChapterPos(item.index)
                shared.curPageChanged()
            }
            return
        }
        when {
            durIndex < item.chapterIndex -> {
                // 对照原版 ReadMangaActivity.onCenterItemChanged: 滚动切章直接
                // moveToNextChapter (toFirst=false 不重置 durChapterPos, 不设 item.index——
                // 原版同款; 滚动位置由渲染层保持, 进度 coerce 偏差与原版一致)
                shared.moveToNextChapter()
            }

            durIndex > item.chapterIndex -> {
                shared.moveToPrevChapter()
            }

            else -> {
                shared.setDurChapterPos(item.index)
                shared.curPageChanged()
            }
        }
    }

    /** SeekBar 拖动定位 (对照 app 端 skipToPage: 只更新章内页码, 列表定位在 Content 内完成) */
    fun seekToPage(index: Int) {
        shared.setDurChapterPos(index)
    }

    /** 图片预加载执行体 (对照 app 端 Coil3 WRITE_ONLY 预载), 平台默认空实现 */
    val preloadImage: (suspend (String, Book, BookSource?) -> Unit)?
        get() = platform?.let { p -> { url, book, source -> p.preloadImage(url, book, source) } }

    /**
     * 整书换源落库 (对照原版 BaseReadViewModel.changeTo, 漫画沿用基类实现)。
     *
     * migrateTo 迁移进度/分组等字段 → 已在书架的书删旧插新 + 落目录 → 内存装载新源/目录。
     * 守卫用 `!isNotShelf` (原版 inBookshelf 口径), 少了落库会出现书架重复书 + 进度丢失。
     */
    fun changeTo(source: BookSource, newBook: Book, toc: List<BookChapter>) {
        scope.launch {
            runCatching {
                currentBook?.changeSourceTo(newBook, toc)
                IntentData.book = newBook
                // 目录先进内存: 未入书架的书没落库, 少了这步会再回源拉一次目录
                shared.onSourceChanged(newBook, toc, source)
                // 换源后内容整体重建, 按迁移后的进度重新定位 (对照原版换源后回到原阅读位置)
                requestCurrentPosition()
            }.onFailure {
                AppLog.put("换源失败\n$it", it, true)
            }
        }
    }

    /** 切换横/纵向翻页 (对照 app 端 MangaMenuAction.HORIZONTAL_SCROLL) */
    fun toggleHorizontal() {
        val newHorizontal = togglePrefBoolean(PreferKey.enableMangaHorizontalScroll, false)
        _state.update { it.copy(horizontal = newHorizontal) }
        refreshSharedConfig()
    }

    /** 更新颜色滤镜配置并持久化 (对照 app 端 MangaColorFilterDialog.Callback.updateColorFilter) */
    fun updateColorFilter(config: MangaColorFilterConfig) {
        PreferenceProviders.get().putString(PreferKey.mangaColorFilter, config.toJson())
        _state.update { it.copy(colorFilterConfig = config) }
    }

    /** 更新灰度开关并持久化 (对照 app 端 MangaColorFilterDialog.upGray) */
    fun updateGray(enable: Boolean) {
        PreferenceProviders.get().putBoolean(PreferKey.enableMangaGray, enable)
        _state.update { it.copy(grayEnabled = enable) }
        refreshSharedConfig()
    }

    /** 更新页脚配置并持久化 (对照 app 端 MangaFooterSettingDialog.onDismiss) */
    fun updateFooterConfig(config: MangaFooterConfig) {
        PreferenceProviders.get().putString(PreferKey.mangaFooterConfig, GSON.toJson(config))
        _state.update { it.copy(footerConfig = config) }
    }

    /** 切换隐藏漫画标题 (对照 app 端 MangaMenuAction.HIDE_TITLE), 触发 shared 重新加载章节内容 */
    fun toggleHideTitle() {
        val newHide = togglePrefBoolean(PreferKey.hideMangaTitle, false)
        _state.update { it.copy(hideMangaTitle = newHide) }
        refreshSharedConfig()
        // 重新加载当前章节, 让 ReaderLoading 头按新配置增减 (对照 app 端 viewModel.loadContent)
        shared.loadContent()
    }

    /** 切换禁用翻页动画 (对照 app 端 MangaMenuAction.DISABLE_PAGE_ANIM) */
    fun toggleDisablePageAnim() {
        val newDisable = togglePrefBoolean(PreferKey.disableMangaPageAnim, false)
        _state.update { it.copy(disablePageAnim = newDisable) }
        refreshSharedConfig()
    }

    /** 切换 GIF 播完翻页 (对照 app 端 MangaMenuAction.GIF_AUTO_NEXT) */
    fun toggleGifAutoNext() {
        val newEnable = togglePrefBoolean(PreferKey.enableMangaGifAutoNext, false)
        _state.update { it.copy(gifAutoNext = newEnable) }
        refreshSharedConfig()
    }

    /** 设置预下载章节数并持久化 (对照 app 端 MangaMenuAction.PRE_DOWNLOAD_NUM) */
    fun setPreDownloadNum(num: Int) {
        PreferenceProviders.get().putInt(PreferKey.mangaPreDownloadNum, num)
        _state.update { it.copy(preDownloadNum = num) }
        refreshSharedConfig()
    }

    /** 设置自动翻页速度并持久化 (对照 app 端 MangaMenuAction.AUTO_PAGE_SPEED) */
    fun setAutoPageSpeed(speed: Int) {
        PreferenceProviders.get().putInt(PreferKey.mangaAutoPageSpeed, speed)
        _state.update { it.copy(autoPageSpeed = speed) }
        refreshSharedConfig()
    }

    /** 更新点击区域配置并持久化 (对照 app 端 ClickActionConfigDialog 即时写 AppConfig.clickActionXX) */
    fun updateClickActionConfig(config: ClickActionConfig) {
        PreferenceProviders.get().run {
            putInt(PreferKey.clickActionTL, config.tl)
            putInt(PreferKey.clickActionTC, config.tc)
            putInt(PreferKey.clickActionTR, config.tr)
            putInt(PreferKey.clickActionML, config.ml)
            putInt(PreferKey.clickActionMC, config.mc)
            putInt(PreferKey.clickActionMR, config.mr)
            putInt(PreferKey.clickActionBL, config.bl)
            putInt(PreferKey.clickActionBC, config.bc)
            putInt(PreferKey.clickActionBR, config.br)
        }
        _state.update { it.copy(clickActionConfig = config) }
    }

    /** 菜单勾选的布尔开关直写 prefs (key 与 app 端 AppConfig 一致), 返回切换后的新值。 */
    private fun togglePrefBoolean(key: String, defaultValue: Boolean): Boolean {
        val prefs = PreferenceProviders.get()
        val newValue = !prefs.getBoolean(key, defaultValue)
        prefs.putBoolean(key, newValue)
        return newValue
    }

    /** 菜单项切换后刷新 VM 的配置快照, 保证切章/重载 (hideMangaTitle/preDownloadNum 等) 读到新值。 */
    private fun refreshSharedConfig() {
        shared.config = readerConfig
    }

    /** 构造当前阅读位置书签 (对照 ReadMangaActivity.addBookmark) */    fun buildBookmark(): Bookmark? {
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

    /**
     * 发布一次性定位请求 (第 [chapterIndex] 章第 [page] 页)。
     *
     * 每次调用都产生**身份不同**的新请求 (id 基于 [MangaReaderUiState.lastRequestId] 单调自增,
     * 经 update 的 CAS 保证并发下单调; 消费清空后不复位, 旧回执永远撞不上新请求), 内容层
     * effect 以请求对象为 key, 因此"再点一次当前章"也能重启定位 —— 目标位置与是否重复无关,
     * 只与"这是一次新请求"有关 (只带位置不带身份的 key 会因逐字段相等被 Compose 判为未变,
     * 定位静默丢失)。请求携带发出时的书籍归属, 换书/换源后遗留请求不得被新内容消费。
     */
    private fun requestJumpTo(chapterIndex: Int, page: Int) {
        val book = shared.book.value ?: return
        _state.update {
            it.withJumpRequest(chapterIndex, page, book.bookUrl, shared.generation)
        }
    }

    /**
     * 按 shared 当前进度发定位请求 (重载/刷新/换源等"内容重建后回到原位置"的场景)。
     *
     * 页号取绝对值: 停在章末的进度用负数编码 (见 [MangaReaderViewModelShared.saveRead]),
     * 定位只需要章内位置。
     */
    private fun requestCurrentPosition() {
        requestJumpTo(
            shared.durChapterIndex.value,
            abs(shared.durChapterPos.value),
        )
    }

    /**
     * 内容层已消费定位请求 (对照官方 UI 事件口径: 消费后回报状态持有者清空, 否则下一轮内容
     * 重建会把视口拉回旧目标)。按 id 比对: 回报的是过期请求时不清新请求 (id 单调, 不复位)。
     */
    fun onPositionRequestConsumed(request: MangaPositionRequest) {
        _state.update { it.withRequestReceipt(request) }
    }

    fun dispatch(event: MangaReaderUiEvent) {
        when (event) {
            is MangaReaderUiEvent.Init -> {
                // shared.initData 从 IntentData.book 取书
                IntentData.book = event.book
                // 对照 app 端 applyBookmarkPosition: chapterIndex>=0 时跳转到指定章节位置。
                // 定位请求在初始化成功后才发: 此刻 shared.book 已是从库里装载的权威进度,
                // 用路由快照 (BookRef) 的 dur 字段会因快照过期定到错章
                shared.initData(
                    overrideIndex = event.chapterIndex ?: -1,
                    overridePos = event.chapterPos ?: 0,
                    success = {
                        val book = shared.book.value
                        if (book != null) {
                            requestJumpTo(book.durChapterIndex, abs(book.durChapterPos))
                        }
                    },
                )
            }
            // toFirst=true: 对照 Activity 点击区域 action 3/4, 用户主动切章跳首页+显示 loading。
            // 定位请求只在切章真的被接受后发: moveToNextChapter 按 simulatedChapterSize
            // (模拟追读解锁上界) 判定, 与 chapterSize 无关 —— 先发请求再调用会让被拒绝的
            // 切章仍执行定位, 出现“UI 越过解锁限制而 VM 仍在原章”的错位。
            // 接受后回读 durChapterIndex: 请求与真正生效的那一章同源 (方法内同步更新)。
            MangaReaderUiEvent.NextChapter -> {
                if (shared.moveToNextChapter(true)) {
                    requestJumpTo(shared.durChapterIndex.value, 0)
                }
            }

            MangaReaderUiEvent.PrevChapter -> {
                if (shared.moveToPrevChapter(true)) {
                    requestJumpTo(shared.durChapterIndex.value, 0)
                }
            }

            is MangaReaderUiEvent.OpenChapter -> {
                // 越界章号被 openChapter 拒绝时不发请求: 悬空请求会在下一批内容重建时
                // 落到别的章上 (请求与书籍/内容批次同源的前提是它确实对应一次切章)
                if (shared.openChapter(event.index, event.position)) {
                    requestJumpTo(shared.durChapterIndex.value, shared.durChapterPos.value)
                }
            }
            // 对照 app 端 tvRetry 点击: 先隐藏重试页再重新加载 (重载后回到原位置)
            MangaReaderUiEvent.Retry -> {
                requestCurrentPosition()
                shared.loadOrUpContent()
            }
            // 刷新当前章: 删缓存后重载 (对照 app 端 MangaMenuAction.REFRESH)。
            // 定位请求在重载边界 (openChapter 清空发布) 之后才发: 刷新目标是当前章, 请求若先发
            // 会在异步清空前的旧批次上被立即消费, 新批次到达后反而无人定位。
            MangaReaderUiEvent.Refresh -> currentBook?.let {
                shared.refreshContentDur(it) { requestCurrentPosition() }
            }
            // 换源回填: migrateTo + 落库 + 装载新源/目录 (对照原版 BaseReadViewModel.changeTo)
            is MangaReaderUiEvent.ChangeSource -> changeTo(event.source, event.book, event.toc)
        }
    }

    /** 进入阅读页 (对照 app 端 onResume): 开始阅读计时 */
    fun onEnter() = shared.onEnter()

    /** 离开阅读页 (对照 app 端 onPause): 结束计时 + 落库 + 上传进度 + 取消预下载 */
    fun onLeave() = shared.onLeave()

    /** 子页 (书籍详情等) 返回后补载缺失章节 (对照原版 ReadMangaViewModel.loadOrUpContent) */
    fun loadOrUpContent() = shared.loadOrUpContent()

    /** 用户确认同步云端进度 (对照 app 端 ReadMangaActivity.sureNewProgress okButton → viewModel.setProgress) */
    fun confirmSyncProgress(progress: BookProgress) {
        // 守卫拒绝 (章号越界/位置未变) 时不发定位请求: 该请求对应的章并未生效
        if (shared.confirmSyncProgress(progress)) {
            requestJumpTo(progress.durChapterIndex, abs(progress.durChapterPos))
        }
    }

    /** 用户取消同步云端进度 (对照 app 端 noButton) */
    fun dismissSyncProgress() = shared.dismissSyncProgress()

    override fun onPreRemoved() {
        // 导航 pop 动画开始前先落库 (对照原版返回键按下即 onPause → saveRead):
        // 不等动画播完后的 retain → onCleared, 退出漫画阅读回书架立即可见最新进度
        shared.saveRead()
    }

    override fun onCleared() {
        shared.onCleared()
        scope.cancel()
    }
}

/**
 * 一次性定位请求: 第 [chapterIndex] 章第 [page] 页, [id] 表达身份。
 *
 * [id] 不可省: 内容层 effect 以本对象为 key, 而 Compose 的 `remember(key)` 走 equals
 * (`GapComposer.kt:842`), 只带目标位置的话"再点一次当前章"的新请求与上一轮逐字段相等,
 * effect 不重启, 定位静默丢失 (漫画目录点当前章不跳的病根)。
 */
data class MangaPositionRequest(
    val id: Int,
    /** 发出时的书籍归属 (bookUrl): 消费方与内容自身快照核对, 异书请求作废 */
    val bookUrl: String,
    /** 发出时的装载代际: 内容批次代际落后于它即不可消费 (combine 异步滞后窗口的旧批) */
    val generation: Int,
    val chapterIndex: Int,
    val page: Int,
)

/** 发布定位请求的纯规则 (供回归测试): id 单调自增且不随消费复位, 请求携带书籍与代际归属。 */
internal fun MangaReaderUiState.withJumpRequest(
    chapterIndex: Int,
    page: Int,
    bookUrl: String,
    generation: Int,
): MangaReaderUiState {
    val id = lastRequestId + 1
    return copy(
        lastRequestId = id,
        positionRequest = MangaPositionRequest(
            id = id,
            bookUrl = bookUrl,
            generation = generation,
            chapterIndex = chapterIndex,
            page = page,
        ),
    )
}

/** 消费回执的纯规则 (供回归测试): 仅当前请求 (id 匹配) 被清, 过期回执不动新请求。 */
internal fun MangaReaderUiState.withRequestReceipt(
    receipt: MangaPositionRequest,
): MangaReaderUiState =
    if (positionRequest?.id == receipt.id) copy(positionRequest = null) else this

/** 漫画阅读页 UI 状态, 字段对齐 [MangaReaderScreenContent] 入参。 */
data class MangaReaderUiState(
    val bookName: String = "",
    val chapterTitle: String = "",
    val items: List<BaseMangaPage> = emptyList(),
    val curFinish: Boolean = false,
    val curChapterIndex: Int = 0,
    val chapterSize: Int = 0,
    val horizontal: Boolean = false,
    val autoPageSpeed: Int = 0,
    /** 章节装载状态 (空闲/加载中/失败, 单一状态源: shared VM 的 loadState 直传) */
    val loadState: ChapterLoadState = ChapterLoadState.Idle,
    /** 一次性定位请求 (初始打开/菜单切章/目录选章/书签跳转/刷新/云进度/换源发出) */
    val positionRequest: MangaPositionRequest? = null,
    /** 已发出的最后一个请求 id (单调真源): 与 positionRequest 分开持有, 消费清空后不复位 */
    val lastRequestId: Int = 0,
    /** items 所属内容批次的归属书籍 (同一快照): 消费定位请求时与请求核对, 空串 = 尚无批次 */
    val contentBookUrl: String = "",
    /** items 所属内容批次的装载代际 (同一快照): 请求只消费不早于自身代际的批次 */
    val contentGeneration: Int = 0,
    /** 内容重建身份号 (shared 每次发布新内容自增); 内容层用它判断"新内容到了, 该消费请求了" */
    val contentEpoch: Int = 0,
    val currentPage: Int = 0,
    val pageCount: Int = 0,
    val progressPercent: String = "0.0%",
    val colorFilterConfig: MangaColorFilterConfig = MangaColorFilterConfig(),
    val grayEnabled: Boolean = false,
    val footerConfig: MangaFooterConfig = MangaFooterConfig(),
    val hideMangaTitle: Boolean = false,
    val disablePageAnim: Boolean = false,
    val gifAutoNext: Boolean = false,
    val preDownloadNum: Int = 10,
    val hasReview: Boolean = false,
    val clickActionConfig: ClickActionConfig = ClickActionConfig(),
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
     * 走 [MangaReaderScreenModel.changeTo]: migrateTo + 落库 + 装载新源/目录。
     */
    data class ChangeSource(
        val source: BookSource,
        val book: Book,
        val toc: List<BookChapter>,
    ) : MangaReaderUiEvent
}
