package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.View
import android.widget.SeekBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.indices
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.BottomNavTag
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.DialogBottomNavConfigBinding
import io.legado.app.databinding.DialogSearchConfigBinding
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.applyThemeTree
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.startActivity
import sh.calvin.reorderable.ReorderableRow

/**
 * 主题设置宿主（原 ThemeConfigFragment 壳上浮）。
 * launcherIcon 写 prefs 后仍走 OnSharedPreferenceChangeListener 承接原副作用；动态 summary 用 state 承接；
 * 布局/搜索/底栏/主题弹窗等点击型交互逐字保留。
 */
@Suppress("SameParameterValue")
class ThemeConfigHost(activity: ConfigActivity) : ConfigHost(activity),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var fontScaleSummary by mutableStateOf("")
    private var sourceEditMaxLineSummary by mutableStateOf("")

    init {
        fontScaleSummary = fontScaleSummary()
        sourceEditMaxLineSummary = activity.getString(
            R.string.source_edit_max_line_summary,
            AppConfig.sourceEditMaxLine.toString()
        )
        activity.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(
                title = stringResource(R.string.theme_setting),
                onBack = { activity.finish() },
            )
            Box(Modifier.weight(1f)) {
                ThemeConfigScreen(
                    fontScaleSummary = fontScaleSummary,
                    sourceEditMaxLineSummary = sourceEditMaxLineSummary,
                    onBookshelfLayout = ::configBookshelf,
                    onSearchLayout = ::configSearch,
                    onCoverConfig = {
                        activity.startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.COVER_CONFIG)
                        }
                    },
                    onWelcomeStyle = {
                        activity.startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.WELCOME_CONFIG)
                        }
                    },
                    onBottomNavConfig = ::configBottomNav,
                    onThemeList = {
                        ThemeListDialog().show(activity.supportFragmentManager, "themeList")
                    },
                    onCustomizeDayTheme = {
                        ThemeCustomizeDialog.editPrefs(false)
                            .show(activity.supportFragmentManager, "themeCustomize")
                    },
                    onCustomizeNightTheme = {
                        ThemeCustomizeDialog.editPrefs(true)
                            .show(activity.supportFragmentManager, "themeCustomize")
                    },
                    onFontScale = {
                        showNumberPicker(
                            activity,
                            titleResId = R.string.font_scale,
                            max = 16, min = 8, value = 10,
                            neutralButton = R.string.btn_default_s to {
                                activity.putPrefInt(PreferKey.fontScale, 0)
                            }
                        ) {
                            activity.putPrefInt(PreferKey.fontScale, it)
                        }
                    },
                    onSourceEditMaxLine = {
                        showNumberPicker(
                            activity,
                            titleResId = R.string.source_edit_text_max_line,
                            max = Int.MAX_VALUE, min = 10, value = AppConfig.sourceEditMaxLine
                        ) {
                            AppConfig.sourceEditMaxLine = it
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        activity.defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(activity.getPrefString(key))
            PreferKey.sourceEditMaxLine -> {
                sourceEditMaxLineSummary = activity.getString(
                    R.string.source_edit_max_line_summary,
                    AppConfig.sourceEditMaxLine.toString()
                )
            }

            PreferKey.fontScale -> {
                fontScaleSummary = fontScaleSummary()
                recreateActivities()
            }

            PreferKey.showDiscovery,
            PreferKey.showHome -> postEvent(EventBus.NOTIFY_MAIN, true)
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    private fun fontScaleSummary(): String {
        val fontScale = AppContextWrapper.getFontScale(activity)
        return activity.getString(R.string.font_scale_summary, fontScale)
    }

    @SuppressLint("InflateParams")
    private fun configBottomNav() {
        val binding = DialogBottomNavConfigBinding.inflate(activity.layoutInflater)
        binding.root.applyThemeTree()

        data class NavItem(
            val tag: String,
            val filledIconRes: Int,
            val outlinedIconRes: Int,
            val nameRes: Int,
            val enabled: Boolean,
        ) {
            val locked get() = tag == BottomNavTag.BOOKSHELF || tag == BottomNavTag.MY
        }

        val defaultNavItems = listOf(
            NavItem(
                BottomNavTag.HOME,
                io.legado.shared.R.drawable.ic_bottom_home_s,
                io.legado.shared.R.drawable.ic_bottom_home_e,
                R.string.home,
                AppConfig.showHome
            ),
            NavItem(
                BottomNavTag.BOOKSHELF,
                io.legado.shared.R.drawable.ic_bottom_books_s,
                io.legado.shared.R.drawable.ic_bottom_books_e,
                R.string.bookshelf,
                true
            ),
            NavItem(
                BottomNavTag.DISCOVERY,
                io.legado.shared.R.drawable.ic_bottom_explore_s,
                io.legado.shared.R.drawable.ic_bottom_explore_e,
                R.string.discovery,
                AppConfig.showDiscovery
            ),
            NavItem(
                BottomNavTag.MY,
                io.legado.shared.R.drawable.ic_bottom_person_s,
                io.legado.shared.R.drawable.ic_bottom_person_e,
                R.string.my,
                true
            ),
        )
        val savedOrder = AppConfig.bottomNavItemOrder?.split(",").orEmpty()
        val defaultTags = defaultNavItems.map { it.tag }.toSet()
        var navItems by mutableStateOf(
            if (savedOrder.size == defaultNavItems.size && savedOrder.toSet() == defaultTags) {
                savedOrder.mapNotNull { tag -> defaultNavItems.find { it.tag == tag } }
            } else {
                defaultNavItems
            }
        )

        applyValues(
            binding,
            AppConfig.bottomBarHeight,
            AppConfig.bottomBarIconSize,
            AppConfig.bottomBarLabelMode
        )
        binding.apply {
            sbHeight.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                @SuppressLint("SetTextI18n")
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    tvHeightValue.text = "${progress + AppConfig.BOTTOM_BAR_HEIGHT_MIN}dp"
                }
            })
            sbIcon.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                @SuppressLint("SetTextI18n")
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    tvIconValue.text = "${progress + AppConfig.BOTTOM_BAR_ICON_MIN}dp"
                }
            })
        }

        activity.alert(titleResource = R.string.bottom_nav_config) {
            customView {
                Column {
                    // 排序列表段(原 RecyclerView+ItemTouchHelper)：拖拽换位、点击切换启停
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.bottom_nav_items_order),
                            color = colorResource(R.color.primaryText),
                            fontWeight = FontWeight.Bold,
                        )
                        ReorderableRow(
                            list = navItems,
                            onSettle = { from, to ->
                                navItems = navItems.toMutableList()
                                    .apply { add(to, removeAt(from)) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) { _, item, _ ->
                            key(item.tag) {
                                // 形态对比表达启停：实心/描边图标 + accent/primaryText
                                val tint = if (item.enabled) AppTheme.colors.accent
                                else AppTheme.colors.primaryText
                                ReorderableItem(modifier = Modifier.weight(1f)) {
                                    Column(
                                        Modifier
                                            .longPressDraggableHandle()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                enabled = !item.locked,
                                            ) {
                                                navItems = navItems.map {
                                                    if (it.tag == item.tag) it.copy(enabled = !it.enabled)
                                                    else it
                                                }
                                            }
                                            .padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                if (item.enabled) item.filledIconRes
                                                else item.outlinedIconRes
                                            ),
                                            contentDescription = stringResource(item.nameRes),
                                            tint = tint,
                                            modifier = Modifier.size(AppConfig.bottomBarIconSize.dp),
                                        )
                                        Text(
                                            text = stringResource(item.nameRes),
                                            color = tint,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { binding.root },
                        update = { root ->
                            // 主题变更/重组时重新着色, 防 AndroidView attach 后 tintList 被重置回落到 Material 默认色
                            root.applyThemeTree()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            okButton {
                val newShowHome = navItems.find { it.tag == BottomNavTag.HOME }?.enabled ?: true
                val newShowDiscovery =
                    navItems.find { it.tag == BottomNavTag.DISCOVERY }?.enabled ?: true
                val newOrder = navItems.joinToString(",") { it.tag }
                val newHeight = binding.sbHeight.progress + AppConfig.BOTTOM_BAR_HEIGHT_MIN
                val newIcon = binding.sbIcon.progress + AppConfig.BOTTOM_BAR_ICON_MIN
                val newLabel = when (binding.rgLabelMode.checkedRadioButtonId) {
                    R.id.rb_label_labeled -> 1
                    R.id.rb_label_selected -> 2
                    R.id.rb_label_auto -> 3
                    else -> 0
                }
                var changed = AppConfig.showHome != newShowHome
                    || AppConfig.showDiscovery != newShowDiscovery
                    || AppConfig.bottomNavItemOrder != newOrder
                AppConfig.showHome = newShowHome
                AppConfig.showDiscovery = newShowDiscovery
                AppConfig.bottomNavItemOrder = newOrder
                if (AppConfig.bottomBarHeight != newHeight) {
                    AppConfig.bottomBarHeight = newHeight; changed = true
                }
                if (AppConfig.bottomBarIconSize != newIcon) {
                    AppConfig.bottomBarIconSize = newIcon; changed = true
                }
                if (AppConfig.bottomBarLabelMode != newLabel) {
                    AppConfig.bottomBarLabelMode = newLabel; changed = true
                }
                if (changed) recreateActivities()
            }
            // reset：dismissOnClick=false，点击重置默认值后对话框保留
            neutralButtonRetain(R.string.reset) {
                applyValues(
                    binding,
                    AppConfig.BOTTOM_BAR_HEIGHT_DEFAULT,
                    AppConfig.BOTTOM_BAR_ICON_DEFAULT,
                    AppConfig.BOTTOM_BAR_LABEL_DEFAULT
                )
                navItems = defaultNavItems.map { it.copy(enabled = true) }
            }
            cancelButton()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applyValues(
        binding: DialogBottomNavConfigBinding, height: Int, icon: Int, label: Int
    ) = binding.run {
        sbHeight.progress = height - AppConfig.BOTTOM_BAR_HEIGHT_MIN
        sbIcon.progress = icon - AppConfig.BOTTOM_BAR_ICON_MIN
        tvHeightValue.text = "${height}dp"
        tvIconValue.text = "${icon}dp"
        when (label) {
            1 -> rbLabelLabeled.isChecked = true
            2 -> rbLabelSelected.isChecked = true
            3 -> rbLabelAuto.isChecked = true
            else -> rbLabelUnlabeled.isChecked = true
        }
    }

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        activity.alert(titleResource = R.string.bookshelf_layout) {
            val bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            var fixedWidthMode = AppConfig.bookshelfFixedWidthMode
            val gridWidth = AppConfig.bookshelfGridWidth
            var introLines = AppConfig.bookshelfListIntroLines
            var selectedCols = BookSource.exploreStyleCols(bookshelfLayout)

            val alertBinding = DialogBookshelfConfigBinding.inflate(activity.layoutInflater).apply {
                root.applyThemeTree()
                if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                    AppConfig.bookGroupStyle = 0
                }
                if (bookshelfSort !in rgSort.indices) {
                    bookshelfSort = 0
                    AppConfig.bookshelfSort = 0
                }
                spGroupStyle.setSelection(AppConfig.bookGroupStyle)
                spItemStyle.setSelection(
                    if (BookSource.exploreStyleIsVideo(bookshelfLayout)) 1 else 0
                )
                swShowUnread.isChecked = AppConfig.showUnread
                swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
                swShowGroupCount.isChecked = AppConfig.bookshelfShowGroupCount
                swShowKind.isChecked = AppConfig.bookshelfListShowKind
                swShowIntro.isChecked = AppConfig.bookshelfListShowIntro
                tvIntroLinesValue.text = introLines.toString()
                llIntroLines.alpha = if (swShowIntro.isChecked) 1f else 0.4f
                swShowIntro.setOnCheckedChangeListener { _, isChecked ->
                    llIntroLines.alpha = if (isChecked) 1f else 0.4f
                }
                tvIntroLinesMinus.setOnClickListener {
                    if (introLines > 1) {
                        introLines--
                        tvIntroLinesValue.text = introLines.toString()
                    }
                }
                tvIntroLinesPlus.setOnClickListener {
                    if (introLines < 5) {
                        introLines++
                        tvIntroLinesValue.text = introLines.toString()
                    }
                }
                swFixedWidthMode.isChecked = fixedWidthMode
                sbColumnCount.progress = selectedCols
                tvColumnValue.text = selectedCols.toString()
                etGridWidth.setText(gridWidth.toString())
                llColumnCount.visibility = if (fixedWidthMode) View.GONE else View.VISIBLE
                llFixedWidth.visibility = if (fixedWidthMode) View.VISIBLE else View.GONE
                val updateListOnlyVisibility = {
                    val isList = !swFixedWidthMode.isChecked && sbColumnCount.progress <= 1
                    val v = if (isList) View.VISIBLE else View.GONE
                    swShowKind.visibility = v
                    swShowIntro.visibility = v
                    llIntroLines.visibility = v
                    swShowLastUpdateTime.visibility = v
                }
                updateListOnlyVisibility()
                sbColumnCount.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar, progress: Int, fromUser: Boolean
                    ) {
                        selectedCols = progress
                        tvColumnValue.text = progress.toString()
                        updateListOnlyVisibility()
                    }
                })
                swFixedWidthMode.setOnCheckedChangeListener { _, isChecked ->
                    fixedWidthMode = isChecked
                    llColumnCount.visibility = if (isChecked) View.GONE else View.VISIBLE
                    llFixedWidth.visibility = if (isChecked) View.VISIBLE else View.GONE
                    updateListOnlyVisibility()
                }
                rgSort.checkByIndex(bookshelfSort)
            }

            customView {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { alertBinding.root },
                    update = { root ->
                        // 主题变更/重组时重新着色, 防 AndroidView attach 后 tintList 被重置回落到 Material 默认色
                        root.applyThemeTree()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            okButton {
                alertBinding.apply {
                    var notifyMain = false
                    var recreate = false
                    if (AppConfig.bookGroupStyle != spGroupStyle.selectedItemPosition) {
                        AppConfig.bookGroupStyle = spGroupStyle.selectedItemPosition
                        notifyMain = true
                    }
                    if (AppConfig.showUnread != swShowUnread.isChecked) {
                        AppConfig.showUnread = swShowUnread.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showLastUpdateTime != swShowLastUpdateTime.isChecked) {
                        AppConfig.showLastUpdateTime = swShowLastUpdateTime.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.bookshelfShowGroupCount != swShowGroupCount.isChecked) {
                        AppConfig.bookshelfShowGroupCount = swShowGroupCount.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.bookshelfListShowKind != swShowKind.isChecked) {
                        AppConfig.bookshelfListShowKind = swShowKind.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.bookshelfListShowIntro != swShowIntro.isChecked) {
                        AppConfig.bookshelfListShowIntro = swShowIntro.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.bookshelfListIntroLines != introLines) {
                        AppConfig.bookshelfListIntroLines = introLines
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (bookshelfSort != rgSort.getCheckedIndex()) {
                        AppConfig.bookshelfSort = rgSort.getCheckedIndex()
                    }
                    val newLayout =
                        makeLayoutStyle(selectedCols, spItemStyle.selectedItemPosition == 1)
                    val newGridWidth = etGridWidth.text?.toString()?.toIntOrNull() ?: 120
                    if (bookshelfLayout != newLayout ||
                        AppConfig.bookshelfFixedWidthMode != fixedWidthMode ||
                        AppConfig.bookshelfGridWidth != newGridWidth
                    ) {
                        AppConfig.bookshelfLayout = newLayout
                        AppConfig.bookshelfFixedWidthMode = fixedWidthMode
                        AppConfig.bookshelfGridWidth = newGridWidth
                        recreate = true
                    }
                    if (recreate) {
                        recreateActivities()
                    } else if (notifyMain) {
                        postEvent(EventBus.NOTIFY_MAIN, false)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun makeLayoutStyle(cols: Int, isVideo: Boolean): Int {
        val flag = if (isVideo) BookSource.EXPLORE_STYLE_VIDEO_FLAG else 0
        return flag or (cols and BookSource.EXPLORE_STYLE_COLS_MASK)
    }

    @SuppressLint("InflateParams")
    fun configSearch() {
        val currentStyle = AppConfig.searchLayout
        var selectedCols = BookSource.exploreStyleCols(currentStyle).coerceIn(0, 6)

        val alertBinding = DialogSearchConfigBinding.inflate(activity.layoutInflater).apply {
            root.applyThemeTree()
            spItemStyle.setSelection(if (BookSource.exploreStyleIsVideo(currentStyle)) 1 else 0)
            sbColumnCount.progress = selectedCols
            tvColumnValue.text = selectedCols.toString()
            sbColumnCount.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    selectedCols = progress
                    tvColumnValue.text = progress.toString()
                }
            })
        }

        activity.alert(titleResource = R.string.search_layout) {
            customView {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { alertBinding.root },
                    update = { root ->
                        // 主题变更/重组时重新着色, 防 AndroidView attach 后 tintList 被重置回落到 Material 默认色
                        root.applyThemeTree()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            okButton {
                val newLayout =
                    makeLayoutStyle(
                        selectedCols,
                        alertBinding.spItemStyle.selectedItemPosition == 1
                    )
                if (AppConfig.searchLayout != newLayout) {
                    AppConfig.searchLayout = newLayout
                    recreateActivities()
                }
            }
            cancelButton()
        }
    }

}
