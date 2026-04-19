package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityAudioPlayBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getRepresentativeColor
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick
import java.util.Locale

/**
 * 音频播放
 */
@SuppressLint("ObsoleteSdkInt")
class AudioPlayActivity :
    VMBaseActivity<ActivityAudioPlayBinding, AudioPlayViewModel>(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack, AudioPlay.CallBack {

    override val binding by viewBinding(ActivityAudioPlayBinding::inflate)
    override val viewModel by viewModels<AudioPlayViewModel>()
    private val timerSliderPopup by lazy { TimerSliderPopup(this) }
    private val speedSliderPopup by lazy { SpeedSliderPopup(this) }
    private var adjustProgress = false
    private var playMode = AudioPlay.PlayMode.LIST_END_STOP

    private var needCheckPosition = true

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it.first != AudioPlay.book?.durChapterIndex || it.second == 0) {
                AudioPlay.skipTo(it.first)
            }
        }
    }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        AudioPlay.register(this)
        viewModel.titleData.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.initData(intent)
        initView()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !AudioPlay.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_wake_lock)?.isChecked = AppConfig.audioPlayUseWakeLock
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> AudioPlay.book?.let {
                showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
            }

            R.id.menu_login -> AudioPlay.bookSource?.let {
                IntentData.book = AudioPlay.book
                IntentData.chapter = AudioPlay.durChapter
                it.showLoginDialog(this)
            }

            R.id.menu_wake_lock -> AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
            R.id.menu_copy_audio_url -> sendToClip(AudioPlayService.url)
            R.id.menu_set_source_variable -> AudioPlay.bookSource?.showSourceVariableDialog(this)
            R.id.menu_set_book_variable -> AudioPlay.book?.showBookVariableDialog(
                this, AudioPlay.bookSource
            )

            R.id.menu_edit_source -> AudioPlay.bookSource?.let {
                IntentData.source = it
                sourceEditResult.launch {}
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.ivPlayMode.setOnClickListener {
            AudioPlay.changePlayMode()
        }
        binding.ivCover.setOnClickListener {
            it.isGone = true
        }

        observeEventSticky<AudioPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
            updatePlayModeIcon()
        }

        binding.fabPlayStop.setOnClickListener {
            playButton()
        }
        binding.fabPlayStop.onLongClick {
            AudioPlay.stop()
        }
        binding.ivSkipNext.setOnClickListener {
            AudioPlay.next()
        }
        binding.ivSkipPrevious.setOnClickListener {
            AudioPlay.prev()
        }
        binding.playerProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvDurTime.text = progress.toDurationTime()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                adjustProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                adjustProgress = false
                AudioPlay.adjustProgress(seekBar.progress)
            }
        })
        binding.ivChapter.setOnClickListener {
            IntentData.book = AudioPlay.book
            IntentData.chapterList = AudioPlay.chapterList
            tocActivityResult.launch("")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.ivFastForward.invisible()
        }
        binding.ivFastForward.setOnClickListener {
            speedSliderPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }
        binding.ivTimer.setOnClickListener {
            timerSliderPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }
        binding.llPlayMenu.applyNavigationBarPadding()
        binding.ivLrc.setOnPlayClickListener {
            AudioPlay.adjustProgress(it)
            binding.ivLrc.updateProgress(it)
            if (AudioPlay.status == Status.PAUSE) AudioPlay.resume(this)
        }
    }

    private fun updatePlayModeIcon() {
        binding.ivPlayMode.setImageResource(playMode.iconRes)
    }

    private fun playButton() {
        when (AudioPlay.status) {
            Status.PLAY -> AudioPlay.pause(this)
            Status.PAUSE -> AudioPlay.resume(this)
            else -> AudioPlay.loadOrUpPlayUrl()
        }
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc)
        } else {
            AudioPlay.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    AudioPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    AudioPlay.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        val book = AudioPlay.book ?: return super.finish()

        if (AudioPlay.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf {
                AudioPlay.stop()
                super.finish()
            }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    AudioPlay.book?.save()
                    AudioPlay.inBookshelf = true
                    appDb.bookChapterDao.insert(*AudioPlay.chapterList!!.toTypedArray())
                    setResult(RESULT_OK)
                }
                noButton {
                    viewModel.removeFromBookshelf {
                        AudioPlay.stop()
                        super.finish()
                    }

                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            AudioPlay.stop()
        }
        AudioPlay.unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            if (it == Status.PLAY) {
                binding.fabPlayStop.setImageResource(R.drawable.ic_pause_24dp)
            } else {
                binding.fabPlayStop.setImageResource(R.drawable.ic_play_24dp)
            }
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
//            viewModel.refreshData()
            binding.tvSubTitle.text = it
            binding.ivSkipPrevious.isEnabled = AudioPlay.durChapterIndex > 0
            binding.ivSkipNext.isEnabled =
                AudioPlay.durChapterIndex < AudioPlay.simulatedChapterSize - 1
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) {
            binding.playerProgress.max = it
            binding.tvAllTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) {
            if (!adjustProgress) binding.playerProgress.progress = it
            if (needCheckPosition) {
                AudioPlay.durLrcData?.let { lrc ->
                    needCheckPosition = false
                    var position = 0
                    for (i in lrc.indices) {
                        if (lrc[i].first <= it + 60) {
                            position = i
                        } else break
                    }
                    binding.ivLrc.updateProgress(position)
                }
            }
            binding.tvDurTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_LRCPROGRESS) {
            binding.ivLrc.updateProgress(it)
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) {
            binding.playerProgress.secondaryProgress = it

        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            binding.tvSpeed.text = String.format(Locale.ROOT, "%.1fX", it)
            binding.tvSpeed.visible()
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) {
            binding.tvTimer.text = "${it}m"
            binding.tvTimer.visible(it > 0)
        }
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            binding.progressLoading.visible(loading)
        }
    }

    override fun upLrc(lrc: List<Pair<Int, String>>) {
        runOnUiThread {
            binding.ivLrc.setLrcData(lrc)
        }
    }

    override fun upCover(url: String) {
        runOnUiThread {
            val glide = Glide.with(this)
            BookCover.load(glide, url, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl)
                .placeholder(binding.ivCover.drawable).into(binding.ivCover)
            BookCover.loadBlur(glide, url, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl)
                .into(object : CustomViewTarget<ImageView, Drawable>(binding.ivBg) {
                    override fun onResourceCleared(p0: Drawable?) {}
                    override fun onLoadFailed(p0: Drawable?) {}
                    override fun onResourceReady(
                        p0: Drawable, p1: Transition<in Drawable>?
                    ) {
                        if (binding.ivBg.drawable != null) {
                            // 2. 将旧图和新图包装进 TransitionDrawable
                            val transitionDrawable =
                                TransitionDrawable(arrayOf(binding.ivBg.drawable, p0))
                            transitionDrawable.isCrossFadeEnabled = true
                            view.setImageDrawable(transitionDrawable)
                            transitionDrawable.startTransition(300)
                        } else {
                            // 如果原本没图，直接显示新图
                            view.setImageDrawable(p0)
                        }
                        p0.toBitmapOrNull()?.let {
                            updateLrcColor(it)
                        }
                    }

                })

        }
    }

    private fun updateLrcColor(bitmap: Bitmap) {
        val meanColor = try {
            bitmap.getRepresentativeColor()
        } catch (_: Exception) {
            return
        }
        val secondaryHsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(meanColor, secondaryHsl)
        val isLight = secondaryHsl[2] > 0.6f

        if (isLight) {
            secondaryHsl[2] = (secondaryHsl[2] - 0.45f).coerceAtLeast(0.3f)
        } else {
            secondaryHsl[2] = (secondaryHsl[2] + 0.45f).coerceAtMost(0.7f)
        }
        val secondaryColor = androidx.core.graphics.ColorUtils.HSLToColor(secondaryHsl)

        val primaryHsl = secondaryHsl.copyOf()
        if (isLight) {
            primaryHsl[2] = (primaryHsl[2] - 0.35f).coerceAtLeast(0.2f)
        } else {
            primaryHsl[2] = (primaryHsl[2] + 0.35f).coerceAtMost(0.8f)
        }
        val primaryColor = androidx.core.graphics.ColorUtils.HSLToColor(primaryHsl)

        binding.ivLrc.setColors(primaryColor, secondaryColor)
        binding.playerProgress.progressTintList = ColorStateList.valueOf(primaryColor)
        binding.playerProgress.thumbTintList = ColorStateList.valueOf(primaryColor)
        binding.playerProgress.secondaryProgressTintList = ColorStateList.valueOf(secondaryColor)
    }

}
