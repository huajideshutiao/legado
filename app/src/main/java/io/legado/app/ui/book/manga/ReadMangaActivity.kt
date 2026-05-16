package io.legado.app.ui.book.manga

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.animation.LinearInterpolator
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.bumptech.glide.util.FixedPreloadSizeProvider
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseReadActivity
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityMangaBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaEpaperDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.ScrollTimer
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.MangaMenu
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.canScroll
import io.legado.app.utils.fastBinarySearch
import io.legado.app.utils.findCenterViewPosition
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.text.DecimalFormat
import kotlin.math.ceil

class ReadMangaActivity : BaseReadActivity<ActivityMangaBinding, ReadMangaViewModel>(),
    ChangeBookSourceDialog.CallBack, MangaMenu.CallBack,
    MangaColorFilterDialog.Callback, ScrollTimer.ScrollCallback, MangaEpaperDialog.Callback {

    override val currentBook: Book?
        get() = viewModel.curBook

    private val mLayoutManager by lazy {
        MangaLayoutManager(this)
    }
    private val mAdapter: MangaAdapter by lazy {
        MangaAdapter(this)
    }

    private val mSizeProvider by lazy {
        FixedPreloadSizeProvider<Any>(resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
    }

    private val mPagerSnapHelper: PagerSnapHelper by lazy {
        PagerSnapHelper()
    }

    private lateinit var mMangaFooterConfig: MangaFooterConfig
    private val mLabelBuilder by lazy { StringBuilder() }

    private var mMenu: Menu? = null

    private var mRecyclerViewPreloader: RecyclerViewPreloader<Any>? = null

    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null
    private val mScrollTimer by lazy {
        ScrollTimer(this, binding.recyclerView, lifecycleScope).apply {
            setSpeed(AppConfig.mangaAutoPageSpeed)
        }
    }
    private var enableAutoScrollPage = false
    private var enableAutoScroll = false
    private val mLinearInterpolator by lazy {
        LinearInterpolator()
    }

    private val loadMoreView by lazy {
        LoadMoreView(this).apply {
            setBackgroundColor(getCompatColor(R.color.book_ant_10))
            setLoadingColor(R.color.white)
            setLoadingTextColor(R.color.white)
        }
    }

    //打开目录返回选择章节返回结果
    private val tocActivity = registerForActivityResult(TocActivityResult()) {
        it?.let {
            viewModel.openChapter(it.first, it.second)
        }
    }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                viewModel.loadOrUpContent()
            }
        }

    private var imageSrc: String? = null
    private val saveImage by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(imageSrc, uri)
        }
        }
    }

    override val binding by viewBinding(ActivityMangaBinding::inflate)
    override val viewModel by viewModels<ReadMangaViewModel>()
    private val loadingViewVisible get() = binding.flLoading.isVisible
    private val df by lazy {
        DecimalFormat("0.0%")
    }
    private val nextPageThrottle by lazy { throttle(200L, trailing = false) { scrollToNext() } }
    private val prevPageThrottle by lazy { throttle(200L, trailing = false) { scrollToPrev() } }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upSystemUiVisibility(false)
        initRecyclerView()
        binding.tvRetry.setOnClickListener {
            binding.llLoading.isVisible = true
            binding.llRetry.isGone = true
            viewModel.loadOrUpContent()
        }
        binding.pbLoading.isVisible = !AppConfig.isEInkMode
        mAdapter
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading && viewModel.hasNextChapter) {
                loadMoreView.startLoad()
                viewModel.loadOrUpContent()
            }
        }
        loadMoreView.gone()
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
            startLoad()
        }
        viewModel.syncProgressLiveData.observe(this) {
            sureNewProgress(it)
        }
    }

    override fun onBottomDialogChange() {
        if (bottomDialog > 0) {
            binding.mangaMenu.runMenuOut()
        } else {
            binding.mangaMenu.runMenuIn()
        }
    }

    override fun observeLiveBus() {
        observeEvent<MangaFooterConfig>(EventBus.UP_MANGA_CONFIG) {
            mMangaFooterConfig = it
            val item = mAdapter.getItem(binding.recyclerView.findCenterViewPosition())
            upInfoBar(item)
        }
    }

    private fun initRecyclerView() {
        val mangaColorFilter =
            GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
                ?: MangaColorFilterConfig()
        mAdapter.run {
            setMangaImageColorFilter(mangaColorFilter)
            enableMangaEInk(AppConfig.enableMangaEInk, AppConfig.mangaEInkThreshold)
            enableGray(AppConfig.enableMangaGray)
            book = viewModel.curBook
            bookSource = viewModel.curBookSource
        }
        setHorizontalScroll(AppConfig.enableMangaHorizontalScroll)
        binding.recyclerView.run {
            adapter = mAdapter
            itemAnimator = null
            layoutManager = mLayoutManager
            setHasFixedSize(true)
            setDisableClickScroll(AppConfig.disableClickScroll)
            setDisableMangaScale(AppConfig.disableMangaScale)
            setRecyclerViewPreloader(AppConfig.mangaPreDownloadNum)
            longTapListener = {
                val centerPosition = findCenterViewPosition()
                val item = mAdapter.getItem(centerPosition)
                if (item is MangaPage) {
                    saveImage(item.mImageUrl)
                    true
                } else {
                    false
                }
            }
            setPreScrollListener { _, _, _, position ->
                if (mAdapter.isNotEmpty()) {
                    val item = mAdapter.getItem(position)
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
                            binding.mangaMenu.upSeekBar(item.index, item.imageCount)
                            upInfoBar(item)
                        }
                    }
                }
            }
        }
        binding.webtoonFrame.run {
            onAction {
                click(it)
            }
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
            val data = withContext(IO) { viewModel.buildMangaContent() }
            val pos = data.pos
            val list = data.items
            val curFinish = data.curFinish
            val nextFinish = data.nextFinish
            mAdapter.book = viewModel.curBook
            mAdapter.bookSource = viewModel.curBookSource
            mAdapter.submitList(list) {
                if (loadingViewVisible && curFinish) {
                    binding.infobar.isVisible = true
                    upInfoBar(list[pos])
                    mLayoutManager.scrollToPositionWithOffset(pos, 0)
                    binding.flLoading.isGone = true
                    loadMoreView.visible()
                    binding.mangaMenu.upSeekBar(
                        viewModel.durChapterPos, viewModel.curMangaChapter!!.imageCount
                    )
                }

                if (curFinish) {
                    if (!viewModel.hasNextChapter) {
                        loadMoreView.noMore("暂无章节了！")
                    } else if (nextFinish) {
                        loadMoreView.stopLoad()
                    } else {
                        loadMoreView.startLoad()
                    }
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
            binding.infobar.isGone = hideFooter
            binding.infobar.textInfoAlignment = footerOrientation

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
        binding.infobar.update(
            if (mLabelBuilder.isEmpty()) "" else mLabelBuilder.toString()
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.readStartTime = System.currentTimeMillis()
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && viewModel.inBookshelf) {
                viewModel.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = true
        }
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (viewModel.inBookshelf) {
            viewModel.saveRead()
            if (!BuildConfig.DEBUG) {
                if (AppConfig.syncBookProgressPlus) {
                    viewModel.syncProgress()
                } else {
                    viewModel.uploadProgress()
                }
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        viewModel.cancelPreDownloadTask()
        networkChangedListener.unRegister()
        mScrollTimer.isEnabledPage = false
        mScrollTimer.isEnabled = false
    }

    fun loadFail(msg: String, retry: Boolean) {
        lifecycleScope.launch {
            if (loadingViewVisible) {
                binding.llLoading.isGone = true
                binding.llRetry.isVisible = true
                binding.tvRetry.isVisible = retry
                binding.tvMsg.text = msg
            } else {
                loadMoreView.error(null, "加载失败，点击重试")
            }
        }
    }

    override fun onDestroy() {
        CbzFile.clear()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(this).clearMemory()
    }

    fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                viewModel.setProgress(progress)
            }
            noButton()
        }
    }

    fun showLoading() {
        lifecycleScope.launch {
            binding.flLoading.isVisible = true
        }
    }

    fun startLoad() {
        lifecycleScope.launch {
            loadMoreView.startLoad()
        }
    }

    override fun scrollBy(distance: Int) {
        if (!binding.recyclerView.canScroll(1)) {
            return
        }
        val time = ceil(16f / distance * 10000).toInt()
        binding.recyclerView.smoothScrollBy(10000, 10000, mLinearInterpolator, time)
    }

    override fun scrollPage() {
        scrollToNext()
    }

    override val oldBook: Book?
        get() = viewModel.curBook

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isImage) {
            binding.flLoading.isVisible = true
            viewModel.changeTo(source, book, toc)
        } else {
            toastOnUi("所选择的源不是漫画源")
        }
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        mAdapter.setMangaImageColorFilter(config)
        updateWindowBrightness(config.l)
    }

    @SuppressLint("StringFormatMatches")
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_manga, menu)
        upMenu(menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    /**
     * 菜单
     */
    @SuppressLint("StringFormatMatches", "NotifyDataSetChanged")
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> {
                binding.mangaMenu.runMenuOut()
                viewModel.curBook?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_catalog -> {
                IntentData.book = viewModel.curBook
                IntentData.chapterList = viewModel.chapterListData.value
                tocActivity.launch("")
            }

            R.id.menu_refresh -> {
                binding.flLoading.isVisible = true
                viewModel.curBook?.let {
                    viewModel.refreshContentDur(it)
                }
            }

            R.id.menu_pre_manga_number -> {
                showNumberPickerDialog(
                    0,
                    R.string.pre_download,
                    AppConfig.mangaPreDownloadNum
                ) {
                    AppConfig.mangaPreDownloadNum = it
                    item.title = getString(R.string.pre_download_m, it)
                    setRecyclerViewPreloader(it)
                }
            }

            R.id.menu_disable_manga_scale -> {
                item.isChecked = !item.isChecked
                AppConfig.disableMangaScale = item.isChecked
                setDisableMangaScale(item.isChecked)
            }

            R.id.menu_disable_click_scroll -> {
                item.isChecked = !item.isChecked
                AppConfig.disableClickScroll = item.isChecked
                setDisableClickScroll(item.isChecked)
            }

            R.id.menu_enable_auto_page -> {
                item.isChecked = !item.isChecked
                val menuMangaAutoPageSpeed = mMenu?.findItem(R.id.menu_manga_auto_page_speed)
                mScrollTimer.isEnabledPage = item.isChecked
                menuMangaAutoPageSpeed?.isVisible = item.isChecked
                enableAutoScrollPage = item.isChecked
                enableAutoScroll = false
                mScrollTimer.isEnabled = false
                mMenu?.findItem(R.id.menu_enable_auto_scroll)?.isChecked = false
            }

            R.id.menu_manga_auto_page_speed -> {
                showNumberPickerDialog(
                    1, R.string.setting_manga_auto_page_speed,
                    AppConfig.mangaAutoPageSpeed
                ) {
                    AppConfig.mangaAutoPageSpeed = it
                    item.title = getString(R.string.manga_auto_page_speed, it)
                    mScrollTimer.setSpeed(it)
                    if (enableAutoScrollPage) {
                        mScrollTimer.isEnabledPage = true
                    }
                }
            }

            R.id.menu_manga_footer_config -> {
                showDialogFragment(MangaFooterSettingDialog())
            }

            R.id.menu_click_regional_config -> {
                showDialogFragment<ClickActionConfigDialog>()
            }

            R.id.menu_enable_horizontal_scroll -> {
                item.isChecked = !item.isChecked
                AppConfig.enableMangaHorizontalScroll = item.isChecked
                mMenu?.findItem(R.id.menu_disable_horizontal_page_snap)?.isVisible = item.isChecked
                setHorizontalScroll(item.isChecked)
                mAdapter.notifyDataSetChanged()
            }

            R.id.menu_manga_color_filter -> {
                binding.mangaMenu.runMenuOut()
                showDialogFragment(MangaColorFilterDialog())
            }

            R.id.menu_enable_auto_scroll -> {
                item.isChecked = !item.isChecked
                mScrollTimer.isEnabled = item.isChecked
                mMenu?.findItem(R.id.menu_enable_auto_page)?.isChecked = false
                enableAutoScroll = item.isChecked
                enableAutoScrollPage = false
                mScrollTimer.isEnabledPage = false
                mMenu?.findItem(R.id.menu_manga_auto_page_speed)?.isVisible = item.isChecked
                if (enableAutoScroll) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else if (AppConfig.enableMangaHorizontalScroll) {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }

            R.id.menu_hide_manga_title -> {
                item.isChecked = !item.isChecked
                AppConfig.hideMangaTitle = item.isChecked
                viewModel.loadContent()
            }

            R.id.menu_epaper_manga -> {
                item.isChecked = !item.isChecked
                AppConfig.enableMangaEInk = item.isChecked
                mMenu?.findItem(R.id.menu_gray_manga)?.isChecked = false
                AppConfig.enableMangaGray = false
                mMenu?.findItem(R.id.menu_epaper_manga_setting)?.isVisible = item.isChecked
                mAdapter.enableMangaEInk(item.isChecked, AppConfig.mangaEInkThreshold)
            }

            R.id.menu_epaper_manga_setting -> {
                showDialogFragment(MangaEpaperDialog())
            }

            R.id.menu_disable_horizontal_page_snap -> {
                item.isChecked = !item.isChecked
                AppConfig.disableHorizontalPageSnap = item.isChecked
                if (item.isChecked) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }

            R.id.menu_disable_manga_page_anim -> {
                item.isChecked = !item.isChecked
                AppConfig.disableMangaPageAnim = item.isChecked
            }

            R.id.menu_gray_manga -> {
                item.isChecked = !item.isChecked
                AppConfig.enableMangaGray = item.isChecked
                mMenu?.findItem(R.id.menu_epaper_manga)?.isChecked = false
                AppConfig.enableMangaEInk = false
                mMenu?.findItem(R.id.menu_epaper_manga_setting)?.isVisible = false
                mAdapter.enableGray(item.isChecked)
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun openBookInfoActivity() {
        viewModel.curBook?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
                IntentData.book = it
            }
        }
    }

    override fun upSystemUiVisibility(menuIsVisible: Boolean) {
        toggleSystemBar(menuIsVisible)
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = !menuIsVisible
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = !menuIsVisible
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setRecyclerViewPreloader(maxPreload: Int) {
        if (mRecyclerViewPreloader != null) {
            binding.recyclerView.removeOnScrollListener(mRecyclerViewPreloader!!)
        }
        mRecyclerViewPreloader = RecyclerViewPreloader(
            Glide.with(this), mAdapter, mSizeProvider, maxPreload
        )
        binding.recyclerView.addOnScrollListener(mRecyclerViewPreloader!!)
    }

    private fun setHorizontalScroll(enable: Boolean) {
        mAdapter.isHorizontal = enable
        if (enable) {
            if (!enableAutoScroll) {
                if (AppConfig.disableHorizontalPageSnap) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }
            mLayoutManager.orientation = LinearLayoutManager.HORIZONTAL
        } else {
            mPagerSnapHelper.attachToRecyclerView(null)
            mLayoutManager.orientation = LinearLayoutManager.VERTICAL
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun upMenu(menu: Menu) {
        this.mMenu = menu
        menu.findItem(R.id.menu_pre_manga_number).title =
            getString(R.string.pre_download_m, AppConfig.mangaPreDownloadNum)
        menu.findItem(R.id.menu_disable_manga_scale).isChecked = AppConfig.disableMangaScale
        menu.findItem(R.id.menu_disable_click_scroll).isChecked = AppConfig.disableClickScroll
        menu.findItem(R.id.menu_manga_auto_page_speed).title =
            getString(R.string.manga_auto_page_speed, AppConfig.mangaAutoPageSpeed)
        menu.findItem(R.id.menu_enable_horizontal_scroll).isChecked =
            AppConfig.enableMangaHorizontalScroll
        menu.findItem(R.id.menu_epaper_manga).isChecked = AppConfig.enableMangaEInk
        menu.findItem(R.id.menu_epaper_manga_setting).isVisible = AppConfig.enableMangaEInk
        menu.findItem(R.id.menu_disable_horizontal_page_snap).run {
            isVisible = AppConfig.enableMangaHorizontalScroll
            isChecked = AppConfig.disableHorizontalPageSnap
        }
        menu.findItem(R.id.menu_disable_manga_page_anim).isChecked = AppConfig.disableMangaPageAnim
        menu.findItem(R.id.menu_gray_manga).isChecked = AppConfig.enableMangaGray
    }

    private fun setDisableMangaScale(disable: Boolean) {
        binding.webtoonFrame.disableMangaScale = disable
        binding.recyclerView.disableMangaScale = disable
        if (disable) {
            binding.recyclerView.resetZoom()
        }
    }

    private fun setDisableClickScroll(disable: Boolean) {
        binding.webtoonFrame.disabledClickScroll = disable
    }

    private fun scrollToNext() {
        scrollPageTo(1)
    }

    private fun scrollToPrev() {
        scrollPageTo(-1)
    }

    private fun scrollPageTo(direction: Int) {
        if (!binding.recyclerView.canScroll(direction)) {
            appCtx.toastOnUi(R.string.bottom_line)
            return
        }
        var dx = 0
        var dy = 0
        if (AppConfig.enableMangaHorizontalScroll) {
            dx = binding.recyclerView.run {
                width - paddingStart - paddingEnd
            }
        } else {
            dy = binding.recyclerView.run {
                height - paddingTop - paddingBottom
            }
        }
        dx *= direction
        dy *= direction
        if (AppConfig.disableMangaPageAnim) {
            binding.recyclerView.scrollBy(dx, dy)
        } else {
            binding.recyclerView.smoothScrollBy(dx, dy)
        }
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
            0 -> if (!binding.mangaMenu.isVisible && !loadingViewVisible) {
                binding.mangaMenu.runMenuIn()
            }

            1 -> scrollToNext()
            2 -> scrollToPrev()
            3 -> viewModel.moveToNextChapter(true)
            4 -> viewModel.moveToPrevChapter(true)
            8 -> showDialogFragment(ContentEditDialog())
            10 -> {
                IntentData.book = viewModel.curBook
                IntentData.chapterList = viewModel.chapterListData.value
                tocActivity.launch("")
            }

            12 -> viewModel.syncProgress(
                { progress -> sureNewProgress(progress) },
                { toastOnUi(R.string.upload_book_success) },
                { toastOnUi(R.string.sync_book_progress_success) })
        }
    }

    override fun skipToPage(index: Int) {
        val durChapterIndex = viewModel.durChapterIndex
        val itemPos = mAdapter.getItems().fastBinarySearch {
            val chapterIndex: Int
            val pageIndex: Int
            if (it is BaseMangaPage) {
                chapterIndex = it.chapterIndex
                pageIndex = it.index
            } else {
                error("unknown item type")
            }
            val delta = chapterIndex - durChapterIndex
            if (delta != 0) {
                delta
            } else {
                pageIndex - index
            }
        }
        if (itemPos > -1) {
            mLayoutManager.scrollToPositionWithOffset(itemPos, 0)
            upInfoBar(mAdapter.getItem(itemPos))
            viewModel.durChapterPos = index
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (binding.mangaMenu.isVisible) {
            return super.onKeyDown(keyCode, event)
        }
        when {
            isPrevKey(keyCode) -> {
                prevPageThrottle()
                return true
            }

            isNextKey(keyCode) -> {
                nextPageThrottle()
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                prevPageThrottle()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                nextPageThrottle()
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {
                prevPageThrottle()
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                nextPageThrottle()
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
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

    override fun updateEepaper(value: Int) {
        mAdapter.updateThreshold(value)
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
}
