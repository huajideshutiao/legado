package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.base.IBottomDialog
import io.legado.app.constant.AppConst.charsets
import io.legado.app.data.entities.Book
import io.legado.app.help.book.save
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.BgTextConfigDialog
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.PaddingConfigDialog
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.isTv
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.showDialogFragment
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 阅读界面基类：纯 Compose 宿主。
 * 承接原 BaseReadActivity(方向/keepScreenOn/刘海/finish 加书架)与
 * 旧 View 版 BaseReadBookActivity(系统栏/底部弹窗计数/配置弹窗)的全部职责。
 */
abstract class BaseReadBookActivity : BaseComposeActivity(imageBg = false), IBottomDialog {

    val viewModel by viewModels<ReadBookViewModel>()

    open val currentBook: Book?
        get() = ReadBook.book

    final override var bottomDialog = 0
        set(value) {
            if (field != value) {
                field = value
                onBottomDialogChange()
            }
        }

    /** 菜单层(阅读菜单/搜索菜单/底部弹窗)是否可见，由子类的 Compose 状态提供 */
    protected abstract val menuLayoutIsVisible: Boolean

    /** 菜单可见时垫在导航栏区域的底栏色条(原 binding.navigationBar) */
    private val navBarStripVisible = mutableStateOf(false)

    private fun String.intOr(default: Int): Int {
        return if (isEmpty()) default else toIntOrNull() ?: default
    }

    private val selectBookFolderResult = registerHandleFile {
        it.uri?.let { uri ->
            ReadBook.book?.let { book ->
                FileDoc.fromUri(uri, true).find(book.originName)?.let { doc ->
                    book.bookUrl = doc.uri.toString()
                    book.save()
                    viewModel.loadChapterList(book)
                } ?: ReadBook.upMsg("找不到文件")
            }
        } ?: ReadBook.upMsg("没有权限访问")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ReadBook.msg = null
        setOrientation()
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
        // 渲染层(AndroidView 包裹的 ReadView/PageView)依赖原生 insets 分发，Compose 不消费
        (findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? AbstractComposeView)
            ?.consumeWindowInsets = false
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.permissionDenialLiveData.observe(this) {
            selectBookFolderResult.launch {
                mode = HandleFileContract.DIR_SYS
                title = "选择书籍所在文件夹"
            }
        }
        if (!LocalConfig.readHelpVersionIsLast) {
            if (isTv) {
                showCustomPageKeyConfig()
            } else {
                showClickRegionalConfig()
            }
        }
    }

    open fun onBottomDialogChange() {
        when (bottomDialog) {
            0 -> onMenuHide()
            1 -> onMenuShow()
        }
    }

    open fun onMenuShow() {
    }

    open fun onMenuHide() {
    }

    fun showPaddingConfig() {
        showDialogFragment<PaddingConfigDialog>()
    }

    fun showBgTextConfig() {
        showDialogFragment<BgTextConfigDialog>()
    }

    fun showClickRegionalConfig() {
        showDialogFragment<ClickActionConfigDialog>()
    }

    private fun showCustomPageKeyConfig() {
        PageKeyDialog().show(supportFragmentManager, "pageKey")
    }

    /**
     * 屏幕方向
     */
    @SuppressLint("SourceLockedOrientationActivity")
    fun setOrientation() {
        when (AppConfig.screenOrientation) {
            "0" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            "1" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "2" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "3" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "4" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }

    /**
     * 保持亮屏
     */
    fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 适配刘海
     */
    open fun upLayoutInDisplayCutoutMode() {
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
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    fun isPrevKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val prevKeysStr = AppConfig.prevKeys
        return prevKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }

    fun isNextKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val nextKeysStr = AppConfig.nextKeys
        return nextKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }

    /**
     * 更新状态栏,导航栏
     */
    fun upSystemUiVisibility(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true,
        useBgMeanColor: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.run {
                if (toolBarHide && ReadBookConfig.hideNavigationBar) {
                    hide(android.view.WindowInsets.Type.navigationBars())
                } else {
                    show(android.view.WindowInsets.Type.navigationBars())
                }
                if (toolBarHide && ReadBookConfig.hideStatusBar) {
                    hide(android.view.WindowInsets.Type.statusBars())
                } else {
                    show(android.view.WindowInsets.Type.statusBars())
                }
            }
        }
        upSystemUiVisibilityO(isInMultiWindow, toolBarHide)
        if (toolBarHide) {
            setLightStatusBar(ReadBookConfig.durConfig.curStatusIconDark())
        } else {
            val statusBarColor =
                if (ReadBookConfig.durConfig.curBgType() == 0
                    || useBgMeanColor
                ) {
                    ReadBookConfig.bgMeanColor
                } else {
                    ThemeStore.statusBarColor
                }
            setLightStatusBar(ColorUtils.isColorLight(statusBarColor))
        }
    }

    @Suppress("DEPRECATION")
    private fun upSystemUiVisibilityO(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true
    ) {
        var flag = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        if (!isInMultiWindow) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (ReadBookConfig.hideNavigationBar) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (toolBarHide) {
                flag = flag or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
        }
        if (ReadBookConfig.hideStatusBar && toolBarHide) {
            flag = flag or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        window.decorView.systemUiVisibility = flag
    }

    override fun upNavigationBarColor() {
        navBarStripVisible.value = menuLayoutIsVisible
        if (menuLayoutIsVisible) {
            super.upNavigationBarColor()
        } else {
            setNavigationBarColorAuto(ReadBookConfig.bgMeanColor)
        }
    }

    /** 菜单可见时露出的导航栏底色条(原 binding.navigationBar) */
    @Composable
    protected fun BoxScope.NavigationBarStrip() {
        if (navBarStripVisible.value) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(AppTheme.colors.bottomBackground)
            )
        }
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    fun showDownloadDialog() {
        ReadBook.book?.let { book ->
            alert(titleResource = R.string.offline_cache) {
                val start = mutableStateOf((book.durChapterIndex + 1).toString())
                val end = mutableStateOf(book.totalChapterNum.toString())
                customView {
                    // 复刻 dialog_download_choice：章节 [起] 至 [止]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(getString(R.string.chapter), color = AppTheme.colors.primaryText, fontSize = 16.sp)
                        AppNumberField(
                            value = start.value,
                            onValueChange = { start.value = it },
                            label = getString(R.string.start),
                            modifier = Modifier
                                .width(90.dp)
                                .padding(horizontal = 4.dp),
                        )
                        Text(getString(R.string.to), color = AppTheme.colors.primaryText, fontSize = 16.sp)
                        AppNumberField(
                            value = end.value,
                            onValueChange = { end.value = it },
                            label = getString(R.string.end),
                            modifier = Modifier
                                .width(90.dp)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
                okButton {
                    CacheBook.start(
                        this@BaseReadBookActivity, book,
                        start.value.intOr(0) - 1,
                        end.value.intOr(book.totalChapterNum) - 1
                    )
                }
                cancelButton()
            }
        }
    }

    fun showSimulatedReading() {
        val book = ReadBook.book ?: return
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val enabledState = mutableStateOf(book.config.readSimulating)
        val startState = mutableStateOf(book.getStartChapter().toString())
        val numState = mutableStateOf(book.config.dailyChapters.toString())
        val dateState = mutableStateOf(book.getStartDate()?.format(dateFormatter).orEmpty())
        alert(titleResource = R.string.simulated_reading) {
            customView {
                // 复刻 dialog_simulated_reading：开关 + 起始日期(日期选择) + 起始章节/每日章数
                val colors = AppTheme.colors
                Column(Modifier
                    .fillMaxWidth()
                    .padding(16.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            getString(R.string.switch_on),
                            color = colors.primaryText, fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        AppSwitch(
                            checked = enabledState.value,
                            onCheckedChange = { enabledState.value = it },
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            getString(R.string.start_from),
                            color = colors.primaryText, fontSize = 16.sp,
                            modifier = Modifier.width(100.dp),
                        )
                        Text(
                            text = dateState.value.ifEmpty { "Select date" },
                            color = if (dateState.value.isEmpty()) colors.secondaryText else colors.primaryText,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val localStartDate = runCatching {
                                        LocalDate.parse(dateState.value)
                                    }.getOrDefault(LocalDate.now())
                                    DatePickerDialog(
                                        this@BaseReadBookActivity,
                                        { _, yy, mm, dayOfMonth ->
                                            dateState.value = LocalDate.of(yy, mm + 1, dayOfMonth)
                                                .format(dateFormatter)
                                        },
                                        localStartDate.year,
                                        localStartDate.monthValue - 1,
                                        localStartDate.dayOfMonth,
                                    ).show()
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            getString(R.string.start_chapter),
                            color = colors.primaryText, fontSize = 16.sp,
                        )
                        AppNumberField(
                            value = startState.value,
                            onValueChange = { startState.value = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                        Text(
                            getString(R.string.daily_chapters),
                            color = colors.primaryText, fontSize = 16.sp,
                        )
                        AppNumberField(
                            value = numState.value,
                            onValueChange = { numState.value = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                        )
                    }
                }
            }
            okButton {
                val date = dateState.value.let {
                    if (it.isEmpty()) LocalDate.now()
                    else LocalDate.parse(it, dateFormatter)
                }
                book.config.startDate = date
                book.config.dailyChapters = numState.value.intOr(book.totalChapterNum)
                book.config.startChapter = startState.value.intOr(0)
                book.config.readSimulating = enabledState.value
                book.save()
                ReadBook.clearTextChapter()
                viewModel.initData(intent)
            }
            cancelButton()
        }
    }

    fun showCharsetConfig() {
        alert(R.string.set_charset) {
            val getCharset = editTextView(
                hint = "charset",
                text = ReadBook.book?.charset.orEmpty(),
                filterValues = charsets,
            )
            okButton {
                ReadBook.setCharset(getCharset())
            }
            cancelButton()
        }
    }

    fun showPageAnimConfig(success: () -> Unit) {
        val items = arrayListOf<String>()
        items.add(getString(R.string.btn_default_s))
        items.add(getString(R.string.page_anim_cover))
        items.add(getString(R.string.page_anim_slide))
        items.add(getString(R.string.page_anim_simulation))
        items.add(getString(R.string.page_anim_scroll))
        items.add(getString(R.string.page_anim_none))
        selector(R.string.page_anim, items) { _, _ ->
            success()
        }
    }
}
