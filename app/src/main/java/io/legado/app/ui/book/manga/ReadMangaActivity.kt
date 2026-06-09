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
import androidx.recyclerview.widget.RecyclerView
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
import io.legado.app.data.entities.BookProgress
import io.legado.app.databinding.ActivityMangaBinding
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.MangaVH
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
    MangaMenu.CallBack,
    MangaColorFilterDialog.Callback, ScrollTimer.ScrollCallback {

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
    private var enableAutoPage = false
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
            enableGray(AppConfig.enableMangaGray)
            gifAutoNext = AppConfig.enableMangaHorizontalScroll && AppConfig.enableMangaGifAutoNext
            //仅当滚动已停止(IDLE)且此页居中时，才认定为“停稳的当前页”，可作为播完翻页的目标。
            //滑动途中/预布局阶段一律返回 false，避免相邻页被提前装填而提前播完。
            isArmTargetPage = { pos ->
                binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    pos == binding.recyclerView.findCenterViewPosition()
            }
            onTurnPage = { scrollPageTo(1, silent = true) }
            book = viewModel.curBook
            bookSource = viewModel.curBookSource
        }
        setHorizontalScroll(AppConfig.enableMangaHorizontalScroll)
        binding.recyclerView.run {
            adapter = mAdapter
            itemAnimator = null
            layoutManager = mLayoutManager
            setHasFixedSize(true)
            setRecyclerViewPreloader(AppConfig.mangaPreDownloadNum)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var lastCenter = RecyclerView.NO_POSITION

                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val position = rv.findCenterViewPosition()
                    if (position == RecyclerView.NO_POSITION || position == lastCenter) return
                    lastCenter = position
                    if (!mAdapter.isNotEmpty()) return
                    val item = mAdapter.getItem(position)
                    if (item !is BaseMangaPage) return
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

                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    //仅在滚动彻底停止后，才让停稳的居中页 GIF 从第一帧单次播放并准备翻页，
                    //其余页恢复无限循环。这样可避免预布局/滑动途中误装填导致提前播完、停在末帧。
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        syncGifAutoNextForCurrentPage()
                    }
                }
            })
        }
        binding.webtoonFrame.run {
            onAction {
                click(it)
            }
            longTapListener = {
                val centerPosition = binding.recyclerView.findCenterViewPosition()
                val item = mAdapter.getItem(centerPosition)
                if (item is MangaPage) {
                    saveImage(item.mImageUrl)
                    true
                } else {
                    false
                }
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
                    //初始定位用 scrollToPosition，不会触发 IDLE 回调，手动装填首个当前页的 GIF
                    binding.recyclerView.post { syncGifAutoNextForCurrentPage() }
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
        ReadTimeRecorder.start(ReadTimeRecorder.Source.MANGA, viewModel.curBook?.name ?: "")
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && viewModel.inBookshelf) {
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
        if (!isChangingConfigurations) {
            ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.MANGA)
        }
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

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        mAdapter.setMangaImageColorFilter(config)
    }

    override fun updateGray(enable: Boolean) {
        mAdapter.enableGray(enable)
    }

    @SuppressLint("StringFormatMatches")
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_manga, menu)
        upMenu(menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_review)?.isVisible =
            viewModel.curBookSource?.ruleReview?.reviewUrl.isNullOrBlank() == false
        return super.onMenuOpened(featureId, menu)
    }

    /**
     * 菜单
     */
    @SuppressLint("StringFormatMatches", "NotifyDataSetChanged")
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
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

            R.id.menu_enable_auto_page -> {
                item.isChecked = !item.isChecked
                enableAutoPage = item.isChecked
                mMenu?.findItem(R.id.menu_manga_auto_page_speed)?.isVisible = item.isChecked
                applyAutoPage()
            }

            R.id.menu_manga_auto_page_speed -> {
                showNumberPickerDialog(
                    1, R.string.setting_manga_auto_page_speed,
                    AppConfig.mangaAutoPageSpeed
                ) {
                    AppConfig.mangaAutoPageSpeed = it
                    item.title = getString(R.string.manga_auto_page_speed, it)
                    mScrollTimer.setSpeed(it)
                    if (enableAutoPage) {
                        applyAutoPage()
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
                mMenu?.findItem(R.id.menu_manga_gif_auto_next)?.isVisible = item.isChecked
                mAdapter.gifAutoNext = item.isChecked && AppConfig.enableMangaGifAutoNext
                setHorizontalScroll(item.isChecked)
                mAdapter.notifyDataSetChanged()
                if (enableAutoPage) applyAutoPage()
                binding.recyclerView.post {
                    if (mAdapter.gifAutoNext) syncGifAutoNextForCurrentPage()
                    else resetAllGifAutoNext()
                }
            }

            R.id.menu_manga_color_filter -> {
                binding.mangaMenu.runMenuOut()
                showDialogFragment(MangaColorFilterDialog())
            }

            R.id.menu_hide_manga_title -> {
                item.isChecked = !item.isChecked
                AppConfig.hideMangaTitle = item.isChecked
                viewModel.loadContent()
            }

            R.id.menu_disable_manga_page_anim -> {
                item.isChecked = !item.isChecked
                AppConfig.disableMangaPageAnim = item.isChecked
            }

            R.id.menu_manga_gif_auto_next -> {
                item.isChecked = !item.isChecked
                AppConfig.enableMangaGifAutoNext = item.isChecked
                mAdapter.gifAutoNext =
                    AppConfig.enableMangaHorizontalScroll && item.isChecked
                if (mAdapter.gifAutoNext) {
                    syncGifAutoNextForCurrentPage()
                } else {
                    resetAllGifAutoNext()
                }
            }

            R.id.menu_review -> viewModel.openCommentDialog(this)
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
        if (enableAutoPage) {
            if (menuIsVisible) {
                mScrollTimer.isEnabled = false
                mScrollTimer.isEnabledPage = false
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
            mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
            mLayoutManager.orientation = LinearLayoutManager.HORIZONTAL
        } else {
            mPagerSnapHelper.attachToRecyclerView(null)
            mLayoutManager.orientation = LinearLayoutManager.VERTICAL
        }
    }

    /**
     * 应用自动翻页设置：水平模式按页定时翻页，垂直模式持续匀速滚动。
     */
    private fun applyAutoPage() {
        if (enableAutoPage) {
            if (AppConfig.enableMangaHorizontalScroll) {
                mScrollTimer.isEnabled = false
                mScrollTimer.isEnabledPage = true
            } else {
                mScrollTimer.isEnabledPage = false
                mScrollTimer.isEnabled = true
            }
        } else {
            mScrollTimer.isEnabledPage = false
            mScrollTimer.isEnabled = false
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun upMenu(menu: Menu) {
        this.mMenu = menu
        menu.findItem(R.id.menu_pre_manga_number).title =
            getString(R.string.pre_download_m, AppConfig.mangaPreDownloadNum)
        menu.findItem(R.id.menu_manga_auto_page_speed).title =
            getString(R.string.manga_auto_page_speed, AppConfig.mangaAutoPageSpeed)
        menu.findItem(R.id.menu_enable_horizontal_scroll).isChecked =
            AppConfig.enableMangaHorizontalScroll
        menu.findItem(R.id.menu_disable_manga_page_anim).isChecked = AppConfig.disableMangaPageAnim
        menu.findItem(R.id.menu_hide_manga_title).isChecked = AppConfig.hideMangaTitle
        menu.findItem(R.id.menu_manga_gif_auto_next).run {
            isVisible = AppConfig.enableMangaHorizontalScroll
            isChecked = AppConfig.enableMangaGifAutoNext
        }
    }

    private fun scrollToNext() {
        scrollPageTo(1)
    }

    private fun scrollToPrev() {
        scrollPageTo(-1)
    }

    /**
     * 滚动停稳后调用：让当前居中页的 GIF 从第一帧单次播放并准备翻页，
     * 其余已附着页恢复无限循环。这是 GIF 自动翻页的唯一装填入口，
     * 确保只有真正停稳的当前页才进入“播完翻页”状态。
     */
    private fun syncGifAutoNextForCurrentPage() {
        val rv = binding.recyclerView
        if (!mAdapter.gifAutoNext) {
            return
        }
        val center = rv.findCenterViewPosition()
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i))
            if (holder is MangaVH<*>) {
                if (holder.bindingAdapterPosition == center && center != RecyclerView.NO_POSITION) {
                    holder.playGifForCurrentPage()
                } else {
                    holder.stopGifAutoNext()
                }
            }
        }
    }

    /** 关闭 GIF 自动翻页时调用：让所有已附着页恢复无限循环。 */
    private fun resetAllGifAutoNext() {
        val rv = binding.recyclerView
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i))
            if (holder is MangaVH<*>) {
                holder.stopGifAutoNext()
            }
        }
    }

    /**
     * 翻页，返回是否真的翻动了。
     * @param silent 受阻时是否静默（不弹“已到边界”提示）。GIF 自动翻页用 true，
     *   以便调用方在受阻时让动图继续循环、下一轮再尝试；手动翻页用 false。
     */
    private fun scrollPageTo(direction: Int, silent: Boolean = false): Boolean {
        val rv = binding.recyclerView
        if (!rv.canScroll(direction)) {
            if (!silent) appCtx.toastOnUi(R.string.bottom_line)
            return false
        }
        val dx =
            if (AppConfig.enableMangaHorizontalScroll) (rv.width - rv.paddingStart - rv.paddingEnd) * direction else 0
        val dy =
            if (AppConfig.enableMangaHorizontalScroll) 0 else (rv.height - rv.paddingTop - rv.paddingBottom) * direction
        if (AppConfig.disableMangaPageAnim) {
            rv.scrollBy(dx, dy)
            //无翻页动画时 scrollBy 不会触发 IDLE 状态回调，手动装填新当前页的 GIF
            if (mAdapter.gifAutoNext) rv.post { syncGifAutoNextForCurrentPage() }
        } else {
            rv.smoothScrollBy(dx, dy)
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
}
