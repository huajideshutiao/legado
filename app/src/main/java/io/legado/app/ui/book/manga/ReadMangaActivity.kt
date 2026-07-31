package io.legado.app.ui.book.manga

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.base.ComposeDialog
import io.legado.app.base.IBottomDialog
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.book.save
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.render.INFO_BAR_ALIGN_CENTER
import io.legado.app.ui.book.manga.render.MangaInfoBar
import io.legado.app.ui.book.manga.render.MangaRenderLayer
import io.legado.app.ui.book.manga.render.MangaRenderState
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ClickArea
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fastBinarySearch
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isNetworkAvailable
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.text.DecimalFormat

/**
 * 漫画阅读界面：纯 Compose。
 * 渲染层为 [MangaRenderLayer](图片流/缩放手势/预加载)与 [MangaInfoBar](信息条)，
 * 菜单层为 [MangaMenuOverlay]。并承接原 BaseReadActivity 职责(方向/刘海/finish 加书架/翻页键)。
 */
class ReadMangaActivity : BaseComposeActivity(), IBottomDialog,
    MangaColorFilterDialog.Callback {

    val viewModel by viewModels<ReadMangaViewModel>()

    private val currentBook get() = viewModel.curBook

    override var bottomDialog = 0
        set(value) {
            if (field != value) {
                field = value
                onBottomDialogChange()
            }
        }

    // ---- 渲染层：Compose 状态持有者(图片流/缩放/预加载/GIF/自动滚动) ----
    // app 端九宫格点击动作判定器, 注入 shared 版 MangaRenderState 的平台回调
    private val clickArea = ClickArea()
    private val renderState by lazy {
        MangaRenderState().apply {
            autoSpeed = AppConfig.mangaAutoPageSpeed
            clickActionAt = { x, y -> clickArea.getAction(x, y) }
            onContainerSizeExtra = { size -> clickArea.setRect(size.width, size.height) }
        }
    }

    // ---- 信息条(原 ReaderInfoBarView) ----
    private var infoBarVisible by mutableStateOf(false)
    private var infoBarText by mutableStateOf("")
    private var infoBarAlignment by mutableIntStateOf(INFO_BAR_ALIGN_CENTER)

    // ---- 菜单层：Compose 状态持有者 ----
    val mangaMenu: MangaMenu by lazy { MangaMenu(this) }

    // ---- 加载层(原 fl_loading/ll_loading/ll_retry) ----
    private var loadingVisible by mutableStateOf(true)
    private var retryVisible by mutableStateOf(false)
    private var loadingRowVisible by mutableStateOf(true)
    private var retryButtonVisible by mutableStateOf(true)
    private var retryMsg by mutableStateOf("")
    private val loadingViewVisible get() = loadingVisible

    private lateinit var mMangaFooterConfig: MangaFooterConfig
    private val mLabelBuilder by lazy { StringBuilder() }

    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    private var justInitData: Boolean = false
    private var syncDialog: ComposeDialog? = null
    var enableAutoPage = false
        private set

    private var imageSrc: String? = null
    private val saveImage by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(imageSrc, uri)
        }
        }
    }

    private val df by lazy {
        DecimalFormat("0.0%")
    }
    private val nextPageThrottle by lazy { throttle(200L, trailing = false) { scrollToNext() } }
    private val prevPageThrottle by lazy { throttle(200L, trailing = false) { scrollToPrev() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        setOrientation()
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Content() {
        // 监听目录页路由回传结果 (原 tocActivity registerForActivityResult)
        LaunchedEffect(Unit) {
            AppNavigatorProviders.getOrNull()?.results
                ?.filter { it.key == RouteResults.TOC }
                ?.collect { result ->
                    val payload = result.payload as? RouteResultPayload.Toc ?: return@collect
                    viewModel.openChapter(payload.chapterIndex, payload.chapterPos)
                }
        }
        // 监听书籍信息路由回传结果 (原 bookInfoActivity registerForActivityResult)
        LaunchedEffect(Unit) {
            AppNavigatorProviders.getOrNull()?.results
                ?.filter { it.key == RouteResults.BOOK_INFO }
                ?.collect { result ->
                    when (result.payload) {
                        is RouteResultPayload.Deleted -> {
                            setResult(RESULT_DELETED)
                            super.finish()
                        }

                        is RouteResultPayload.Ok -> {
                            setResult(RESULT_DELETED)
                            super.finish()
                        }

                        else -> viewModel.loadOrUpContent()
                    }
                }
        }
        Box(Modifier.fillMaxSize()) {
            MangaRenderLayer(renderState)
            if (infoBarVisible) {
                MangaInfoBar(
                    text = infoBarText,
                    alignment = infoBarAlignment,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .height(20.dp),
                )
            }
            MangaMenuOverlay(mangaMenu)
            LoadingOverlay()
        }
    }

    /** 原 fl_loading：居中加载/失败重试，非点击区触摸穿透(与原 View 一致) */
    @Composable
    private fun LoadingOverlay() {
        if (!loadingVisible) return
        Box(Modifier.fillMaxSize()) {
            if (loadingRowVisible) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!LocalEInk.current) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 4.dp,
                            backgroundColor = Color.Transparent,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.loading),
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            if (retryVisible) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = retryMsg, color = Color.White)
                    if (retryButtonVisible) {
                        Text(
                            text = "重新加载",
                            color = AppTheme.colors.accent,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .padding(16.dp)
                                .clickable {
                                    loadingRowVisible = true
                                    retryVisible = false
                                    viewModel.loadOrUpContent()
                                },
                        )
                    }
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upSystemUiVisibility(false)
        initRenderLayer()
        mMangaFooterConfig =
            GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig).getOrNull()
                ?: MangaFooterConfig()
        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.upContentLiveData.observe(this) {
            upContent()
        }
        viewModel.loadFailLiveData.observe(this) {
            loadFail(it.first, it.second)
        }
        viewModel.showLoadingLiveData.observe(this) {
            showLoading()
        }
        viewModel.startLoadLiveData.observe(this) {
            // 原版在跨章且下一章尚未加载时展示章末加载状态；Compose 版没有独立 footer，
            // 至少保留全屏加载反馈，避免用户翻到空白页后没有任何响应。
            showLoading()
        }
        viewModel.syncProgressLiveData.observe(this) {
            sureNewProgress(it)
        }
    }

    private fun onBottomDialogChange() {
        if (bottomDialog > 0) {
            mangaMenu.runMenuOut()
        } else {
            mangaMenu.runMenuIn()
        }
    }

    override fun observeLiveBus() {
        observeEvent<MangaFooterConfig>(EventBus.UP_MANGA_CONFIG) {
            mMangaFooterConfig = it
            val item = renderState.items.getOrNull(renderState.centerItemIndex())
            upInfoBar(item)
        }
    }

    private fun initRenderLayer() {
        val mangaColorFilter =
            GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
                ?: MangaColorFilterConfig()
        renderState.run {
            colorFilterConfig = mangaColorFilter
            grayEnabled = AppConfig.enableMangaGray
            gifAutoNext = AppConfig.enableMangaHorizontalScroll && AppConfig.enableMangaGifAutoNext
            horizontal = AppConfig.enableMangaHorizontalScroll
            preloadCount = AppConfig.mangaPreDownloadNum
            book = viewModel.curBook
            bookSource = viewModel.curBookSource
            onGifTurnPage = { scrollPageTo(1, silent = true) }
            onAutoPageTick = { scrollToNext() }
            onAction = { click(it) }
            onLongTap = {
                val item = items.getOrNull(centerItemIndex())
                if (item is MangaPage) {
                    saveImage(item.mImageUrl)
                    true
                } else {
                    false
                }
            }
            // 原 onScrolled + findCenterViewPosition：居中页变化驱动章节/进度/信息条
            onCenterItemChanged = { position ->
                val item = items.getOrNull(position)
                if (item is BaseMangaPage) {
                    if (viewModel.durChapterIndex < item.chapterIndex) {
                        viewModel.moveToNextChapter()
                    } else if (viewModel.durChapterIndex > item.chapterIndex) {
                        viewModel.moveToPrevChapter()
                    } else {
                        viewModel.durChapterPos = item.index
                        viewModel.curPageChanged()
                    }
                    if (item is MangaPage) {
                        mangaMenu.upSeekBar(item.index, item.imageCount)
                        upInfoBar(item)
                    }
                }
            }
            //仅在滚动彻底停止后，才让停稳的居中页 GIF 从第一帧单次播放并准备翻页，
            //其余页恢复无限循环，避免滑动途中误装填导致提前播完、停在末帧
            onScrollIdle = { syncGifAutoNextForCurrentPage() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.initData(intent)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initData(intent)
        justInitData = true
    }

    fun upContent() {
        lifecycleScope.launch {
            title = viewModel.curBook?.name
            mangaMenu.title = viewModel.curBook?.name
            val data = withContext(IO) { viewModel.buildMangaContent() }
            val pos = data.pos
            val list = data.items.filterIsInstance<BaseMangaPage>()
            val curFinish = data.curFinish
            renderState.book = viewModel.curBook
            renderState.bookSource = viewModel.curBookSource
            renderState.items = list
            if (loadingViewVisible && curFinish) {
                renderState.scrollToPosition(pos) {
                    infoBarVisible = true
                    upInfoBar(list[pos])
                    loadingVisible = false
                    mangaMenu.upSeekBar(
                        viewModel.durChapterPos, viewModel.curMangaChapter!!.imageCount
                    )
                    //初始定位不触发停稳回调，手动装填首个当前页的 GIF
                    renderState.syncGifAutoNextForCurrentPage()
                }
            }
        }
    }

    private fun upInfoBar(page: Any?) {
        if (page !is MangaPage) {
            return
        }
        val chapterIndex = page.chapterIndex
        val chapterSize = page.chapterSize
        val chapterPos = page.index
        val imageCount = page.imageCount
        val chapterName = page.mChapterName
        mMangaFooterConfig.run {
            mLabelBuilder.clear()
            infoBarVisible = !hideFooter
            infoBarAlignment = footerOrientation

            if (!hideChapterName) {
                mLabelBuilder.append(chapterName).append(" ")
            }

            if (!hidePageNumber) {
                if (!hidePageNumberLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_page_number))
                }
                mLabelBuilder.append("${chapterPos + 1}/${imageCount}").append(" ")
            }

            if (!hideChapter) {
                if (!hideChapterLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_chapter))
                }
                mLabelBuilder.append("${chapterIndex + 1}/${chapterSize}").append(" ")
            }

            if (!hideProgressRatio) {
                if (!hideProgressRatioLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_progress))
                }
                val percent = if (chapterSize == 0 || imageCount == 0 && chapterIndex == 0) {
                    "0.0%"
                } else if (imageCount == 0) {
                    df.format((chapterIndex + 1.0f) / chapterSize.toDouble())
                } else {
                    var percent =
                        df.format(
                            chapterIndex * 1.0f / chapterSize + 1.0f /
                                chapterSize * (chapterPos + 1) / imageCount.toDouble()
                        )
                    if (percent == "100.0%" && (chapterIndex + 1 != chapterSize || chapterPos + 1 != imageCount)) {
                        percent = "99.9%"
                    }
                    percent
                }
                mLabelBuilder.append(percent)
            }
        }
        infoBarText = if (mLabelBuilder.isEmpty()) "" else mLabelBuilder.toString()
    }

    override fun onResume() {
        super.onResume()
        ReadTimeRecorder.start(ReadTimeRecorder.Source.MANGA, viewModel.curBook?.name ?: "")
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && isNetworkAvailable() && !justInitData && viewModel.inBookshelf) {
                viewModel.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
        if (enableAutoPage) {
            applyAutoPage()
        }
    }

    override fun onPause() {
        super.onPause()
        ReadTimeRecorder.end(ReadTimeRecorder.Source.MANGA)
        if (viewModel.inBookshelf) {
            viewModel.saveRead()
            if (!BuildConfig.DEBUG) {
                if (AppConfig.syncBookProgressPlus) {
                    viewModel.syncProgress()
                } else {
                    viewModel.curBook?.let { viewModel.uploadProgress(it) }
                }
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        viewModel.cancelPreDownloadTask()
        networkChangedListener.unRegister()
        renderState.setAutoPageEnabled(false)
        renderState.setAutoScrollEnabled(false)
    }

    fun loadFail(msg: String, retry: Boolean) {
        lifecycleScope.launch {
            if (loadingViewVisible) {
                loadingRowVisible = false
                retryVisible = true
                retryButtonVisible = retry
                retryMsg = msg
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.MANGA)
        }
        CbzFile.clear()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        coil3.SingletonImageLoader.get(this).memoryCache?.clear()
    }

    fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.sync_book_progress_t) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                viewModel.setProgress(progress)
            }
            noButton()
        }
    }

    fun showLoading() {
        lifecycleScope.launch {
            loadingVisible = true
        }
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        renderState.colorFilterConfig = config
    }

    override fun updateGray(enable: Boolean) {
        renderState.grayEnabled = enable
    }

    /**
     * 菜单(原 onCompatOptionsItemSelected)
     */
    fun onMangaMenuAction(action: MangaMenuAction) {
        when (action) {
            MangaMenuAction.CATALOG -> {
                IntentData.book = viewModel.curBook
                IntentData.chapterList = viewModel.chapterListData.value
                viewModel.curBook?.let {
                    AppNavigatorProviders.getOrNull()?.push(
                        AppRoute.Toc(it.toRouteRef()),
                        resultKey = RouteResults.TOC,
                    )
                }
            }

            MangaMenuAction.REFRESH -> {
                loadingVisible = true
                viewModel.curBook?.let {
                    viewModel.refreshContentDur(it)
                }
            }

            MangaMenuAction.PRE_DOWNLOAD_NUM -> {
                showNumberPickerDialog(
                    0,
                    R.string.pre_download,
                    AppConfig.mangaPreDownloadNum
                ) {
                    AppConfig.mangaPreDownloadNum = it
                    renderState.preloadCount = it
                }
            }

            MangaMenuAction.AUTO_PAGE -> {
                enableAutoPage = !enableAutoPage
                applyAutoPage()
            }

            MangaMenuAction.AUTO_PAGE_SPEED -> {
                showNumberPickerDialog(
                    1, R.string.setting_manga_auto_page_speed,
                    AppConfig.mangaAutoPageSpeed
                ) {
                    AppConfig.mangaAutoPageSpeed = it
                    renderState.autoSpeed = it
                    if (enableAutoPage) {
                        applyAutoPage()
                    }
                }
            }

            MangaMenuAction.FOOTER_CONFIG -> {
                showDialogFragment(MangaFooterSettingDialog())
            }

            MangaMenuAction.CLICK_REGION_CONFIG -> {
                showDialogFragment<ClickActionConfigDialog>()
            }

            MangaMenuAction.HORIZONTAL_SCROLL -> {
                val enable = !AppConfig.enableMangaHorizontalScroll
                AppConfig.enableMangaHorizontalScroll = enable
                renderState.gifAutoNext = enable && AppConfig.enableMangaGifAutoNext
                renderState.horizontal = enable
                if (enableAutoPage) applyAutoPage()
                renderState.post {
                    if (renderState.gifAutoNext) renderState.syncGifAutoNextForCurrentPage()
                    else renderState.resetAllGifAutoNext()
                }
            }

            MangaMenuAction.COLOR_FILTER -> {
                mangaMenu.runMenuOut()
                showDialogFragment(MangaColorFilterDialog())
            }

            MangaMenuAction.HIDE_TITLE -> {
                AppConfig.hideMangaTitle = !AppConfig.hideMangaTitle
                viewModel.loadContent()
            }

            MangaMenuAction.DISABLE_PAGE_ANIM -> {
                AppConfig.disableMangaPageAnim = !AppConfig.disableMangaPageAnim
            }

            MangaMenuAction.GIF_AUTO_NEXT -> {
                val enable = !AppConfig.enableMangaGifAutoNext
                AppConfig.enableMangaGifAutoNext = enable
                renderState.gifAutoNext = AppConfig.enableMangaHorizontalScroll && enable
                if (renderState.gifAutoNext) {
                    renderState.syncGifAutoNextForCurrentPage()
                } else {
                    renderState.resetAllGifAutoNext()
                }
            }

            MangaMenuAction.REVIEW -> viewModel.openCommentDialog(this)

            MangaMenuAction.ADD_BOOKMARK -> addBookmark()
        }
    }

    private fun addBookmark() {
        val book = viewModel.curBook ?: return
        val chapters = viewModel.chapterListData.value
        val chapter = chapters?.getOrNull(viewModel.durChapterIndex)
        val pos = viewModel.durChapterPos
        val imageCount = viewModel.curMangaChapter?.imageCount ?: 0
        val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
            chapterIndex = viewModel.durChapterIndex
            chapterPos = pos
            chapterName = chapter?.title ?: book.durChapterTitle ?: ""
            bookText = "第${pos + 1}页 / 共${if (imageCount > 0) "${imageCount}页" else "未知"}"
        }
        showDialogFragment(BookmarkDialog(bookmark))
    }

    fun openBookInfoActivity() {
        viewModel.curBook?.let {
            AppNavigatorProviders.getOrNull()?.push(
                AppRoute.BookInfo(it.toRouteRef()),
                resultKey = RouteResults.BOOK_INFO,
            )
        }
    }

    fun upSystemUiVisibility(menuIsVisible: Boolean) {
        toggleSystemBar(menuIsVisible)
        if (enableAutoPage) {
            if (menuIsVisible) {
                renderState.setAutoScrollEnabled(false)
                renderState.setAutoPageEnabled(false)
            } else {
                applyAutoPage()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !mangaMenu.canShowMenu) {
                mangaMenu.runMenuIn()
                return true
            }
            if (!isDown && !mangaMenu.canShowMenu) {
                mangaMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 应用自动翻页设置：水平模式按页定时翻页，垂直模式持续匀速滚动。
     */
    private fun applyAutoPage() {
        if (enableAutoPage) {
            if (AppConfig.enableMangaHorizontalScroll) {
                renderState.setAutoScrollEnabled(false)
                renderState.setAutoPageEnabled(true)
            } else {
                renderState.setAutoPageEnabled(false)
                renderState.setAutoScrollEnabled(true)
            }
        } else {
            renderState.setAutoPageEnabled(false)
            renderState.setAutoScrollEnabled(false)
        }
    }

    private fun scrollToNext() {
        scrollPageTo(1)
    }

    private fun scrollToPrev() {
        scrollPageTo(-1)
    }

    /**
     * 翻页，返回是否真的翻动了。
     * @param silent 受阻时是否静默（不弹“已到边界”提示）。GIF 自动翻页用 true，
     *   以便调用方在受阻时让动图继续循环、下一轮再尝试；手动翻页用 false。
     */
    private fun scrollPageTo(direction: Int, silent: Boolean = false): Boolean {
        if (!renderState.canScroll(direction)) {
            if (!silent) appCtx.toastOnUi(R.string.bottom_line)
            return false
        }
        renderState.scrollPage(direction, animated = !AppConfig.disableMangaPageAnim)
        if (AppConfig.disableMangaPageAnim && renderState.gifAutoNext) {
            //无翻页动画时同步滚动不触发停稳回调，手动装填新当前页的 GIF
            renderState.post { renderState.syncGifAutoNextForCurrentPage() }
        }
        return true
    }

    private fun showNumberPickerDialog(
        min: Int,
        @StringRes titleRes: Int,
        initValue: Int,
        callback: (Int) -> Unit,
    ) {
        showNumberPicker(
            this,
            titleResId = titleRes,
            max = 9999, min = min, value = initValue
        ) {
            callback.invoke(it)
        }
    }

    private fun click(action: Int) {
        when (action) {
            0 -> if (!mangaMenu.isVisible && !loadingViewVisible) {
                mangaMenu.runMenuIn()
            }

            1 -> scrollToNext()
            2 -> scrollToPrev()
            3 -> viewModel.moveToNextChapter(true)
            4 -> viewModel.moveToPrevChapter(true)
            10 -> {
                IntentData.book = viewModel.curBook
                IntentData.chapterList = viewModel.chapterListData.value
                viewModel.curBook?.let {
                    AppNavigatorProviders.getOrNull()?.push(
                        AppRoute.Toc(it.toRouteRef()),
                        resultKey = RouteResults.TOC,
                    )
                }
            }
        }
    }

    fun skipToPage(index: Int) {
        val durChapterIndex = viewModel.durChapterIndex
        val items = renderState.items
        val itemPos = items.fastBinarySearch {
            val chapterIndex = it.chapterIndex
            val pageIndex = it.index
            val delta = chapterIndex - durChapterIndex
            if (delta != 0) {
                delta
            } else {
                pageIndex - index
            }
        }
        if (itemPos > -1) {
            renderState.scrollToPosition(itemPos)
            upInfoBar(items.getOrNull(itemPos))
            viewModel.durChapterPos = index
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when {
            isPrevKey(keyCode) || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP -> {
                prevPageThrottle()
                return true
            }

            isNextKey(keyCode) || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_SPACE -> {
                nextPageThrottle()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> return true
        }
        return super.onKeyUp(keyCode, event)
    }


    private fun saveImage(url: String) {
        this.imageSrc = url
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(url)
        } else {
            viewModel.saveImage(url, path.toUri())
        }
    }

    private fun selectSaveFolder(url: String) {
        this.imageSrc = url
        saveImage.launch {}
    }

    // ---- 原 BaseReadActivity 职责 ----

    /**
     * 屏幕方向
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun setOrientation() {
        when (AppConfig.screenOrientation) {
            "0" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            "1" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "2" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "3" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "4" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }

    /**
     * 适配刘海
     */
    private fun upLayoutInDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun finish() {
        val book = currentBook ?: return super.finish()

        if (viewModel.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    currentBook?.save()
                    viewModel.inBookshelf = true
                    setResult(RESULT_OK)
                    // 双轨: 同步 RouteResult 通道
                    AppNavigatorProviders.getOrNull()?.pop(RouteResultPayload.Ok)
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    private fun isPrevKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val prevKeysStr = AppConfig.prevKeys
        return prevKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }

    private fun isNextKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val nextKeysStr = AppConfig.nextKeys
        return nextKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }
}
