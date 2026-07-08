package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.indices
import androidx.preference.Preference
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.BottomNavTag
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.DialogBottomNavConfigBinding
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.neutralButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.startActivity
import java.util.Collections


@Suppress("SameParameterValue")
class ThemeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_theme)
        upPreferenceSummary(PreferKey.fontScale)
        upPreferenceSummary(PreferKey.sourceEditMaxLine)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }


    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(getPrefString(key))
            PreferKey.sourceEditMaxLine -> upPreferenceSummary(key)

            PreferKey.fontScale -> {
                upPreferenceSummary(key)
                recreateActivities()
            }

            PreferKey.showDiscovery,
            PreferKey.showHome -> postEvent(EventBus.NOTIFY_MAIN, true)
        }
    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.fontScale -> showNumberPicker(
                requireContext(),
                titleResId = R.string.font_scale,
                max = 16, min = 8, value = 10,
                neutralButton = R.string.btn_default_s to {
                    putPrefInt(PreferKey.fontScale, 0)
                }
            ) {
                putPrefInt(PreferKey.fontScale, it)
            }

            "themeList" -> ThemeListDialog().show(childFragmentManager, "themeList")
            "customizeDayTheme" -> ThemeCustomizeDialog.editPrefs(false)
                .show(childFragmentManager, "themeCustomize")

            "customizeNightTheme" -> ThemeCustomizeDialog.editPrefs(true)
                .show(childFragmentManager, "themeCustomize")

            "coverConfig" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_CONFIG)
            }

            "welcomeStyle" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.WELCOME_CONFIG)
            }

            PreferKey.bookshelfLayout -> configBookshelf()

            "bottomNavConfig" -> configBottomNav()

            PreferKey.sourceEditMaxLine -> {
                showNumberPicker(
                    requireContext(),
                    titleResId = R.string.source_edit_text_max_line,
                    max = Int.MAX_VALUE, min = 10, value = AppConfig.sourceEditMaxLine
                ) {
                    AppConfig.sourceEditMaxLine = it
                }
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String? = null) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.fontScale -> {
                val fontScale = AppContextWrapper.getFontScale(requireContext())
                preference.summary = getString(R.string.font_scale_summary, fontScale)
            }

            PreferKey.sourceEditMaxLine -> {
                val maxLine = value ?: AppConfig.sourceEditMaxLine.toString()
                preference.summary = getString(R.string.source_edit_max_line_summary, maxLine)
            }

            else -> preference.summary = value
        }
    }

    @SuppressLint("InflateParams")
    private fun configBottomNav() {
        val binding = DialogBottomNavConfigBinding.inflate(layoutInflater)

        data class NavItem(
            val tag: String,
            val filledIconRes: Int,
            val outlinedIconRes: Int,
            val nameRes: Int,
            var enabled: Boolean,
        ) {
            val locked get() = tag == BottomNavTag.BOOKSHELF || tag == BottomNavTag.MY
        }

        val defaultNavItems = listOf(
            NavItem(
                BottomNavTag.HOME,
                R.drawable.ic_bottom_home_s,
                R.drawable.ic_bottom_home_e,
                R.string.home,
                AppConfig.showHome
            ),
            NavItem(
                BottomNavTag.BOOKSHELF,
                R.drawable.ic_bottom_books_s,
                R.drawable.ic_bottom_books_e,
                R.string.bookshelf,
                true
            ),
            NavItem(
                BottomNavTag.DISCOVERY,
                R.drawable.ic_bottom_explore_s,
                R.drawable.ic_bottom_explore_e,
                R.string.discovery,
                AppConfig.showDiscovery
            ),
            NavItem(
                BottomNavTag.MY,
                R.drawable.ic_bottom_person_s,
                R.drawable.ic_bottom_person_e,
                R.string.my,
                true
            ),
        )
        val savedOrder = AppConfig.bottomNavItemOrder?.split(",").orEmpty()
        val defaultTags = defaultNavItems.map { it.tag }.toSet()
        val navItems = if (savedOrder.size == defaultNavItems.size
            && savedOrder.toSet() == defaultTags
        ) {
            savedOrder.mapNotNull { tag -> defaultNavItems.find { it.tag == tag } }.toMutableList()
        } else {
            defaultNavItems.toMutableList()
        }

        fun iconSize() = AppConfig.bottomBarIconSize.dpToPx()

        fun bindView(tv: TextView, item: NavItem) {
            tv.setText(item.nameRes)
            val ctx = requireContext()
            val iconRes = if (item.enabled) item.filledIconRes else item.outlinedIconRes
            val tint = if (item.enabled) ctx.accentColor else ctx.primaryTextColor
            val icon: Drawable = ContextCompat.getDrawable(ctx, iconRes)!!
                .mutate().also {
                    it.setBounds(0, 0, iconSize(), iconSize())
                    DrawableCompat.setTint(it, tint)
                }
            tv.setCompoundDrawables(null, icon, null, null)
            tv.setTextColor(tint)
        }

        val adapter = object : RecyclerAdapter<NavItem, ViewBinding>(requireContext()) {
            override fun getViewBinding(parent: ViewGroup): ViewBinding {
                val tv = TextView(context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.primaryText))
                    textSize = 12f
                    compoundDrawablePadding = 4.dpToPx()
                    val pad = resources.getDimensionPixelSize(R.dimen.arco_spacing_default)
                    setPadding(pad, pad, pad, pad)
                }
                return ViewBinding { tv }
            }

            override fun convert(
                holder: ItemViewHolder,
                binding: ViewBinding,
                item: NavItem,
                payloads: MutableList<Any>
            ) {
                bindView(binding.root as TextView, item)
            }

            override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
                holder.itemView.setOnClickListener {
                    val item =
                        getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                    if (!item.locked) {
                        item.enabled = !item.enabled
                        notifyItemChanged(holder.layoutPosition)
                    }
                }
            }
        }.also { it.setItems(navItems) }

        binding.rvNavItems.apply {
            layoutManager = GridLayoutManager(requireContext(), navItems.size)
            this.adapter = adapter
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                Collections.swap(navItems, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        }).attachToRecyclerView(binding.rvNavItems)

        @SuppressLint("SetTextI18n")
        fun applyValues(height: Int, icon: Int, label: Int) {
            binding.sbHeight.progress = height - AppConfig.BOTTOM_BAR_HEIGHT_MIN
            binding.sbIcon.progress = icon - AppConfig.BOTTOM_BAR_ICON_MIN
            binding.tvHeightValue.text = "${height}dp"
            binding.tvIconValue.text = "${icon}dp"
            when (label) {
                1 -> binding.rbLabelLabeled.isChecked = true
                2 -> binding.rbLabelSelected.isChecked = true
                3 -> binding.rbLabelAuto.isChecked = true
                else -> binding.rbLabelUnlabeled.isChecked = true
            }
        }
        binding.apply {
            applyValues(
                AppConfig.bottomBarHeight,
                AppConfig.bottomBarIconSize,
                AppConfig.bottomBarLabelMode
            )
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
        alert(titleResource = R.string.bottom_nav_config) {
            customView { binding.root }
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
            neutralButton(R.string.reset)
            cancelButton()
        }.apply {
            getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                applyValues(
                    AppConfig.BOTTOM_BAR_HEIGHT_DEFAULT,
                    AppConfig.BOTTOM_BAR_ICON_DEFAULT,
                    AppConfig.BOTTOM_BAR_LABEL_DEFAULT
                )
                navItems.clear()
                navItems.addAll(defaultNavItems.map { it.copy(enabled = true) })
                adapter.setItems(navItems)
            }
        }
    }

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        alert(titleResource = R.string.bookshelf_layout) {
            var bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            var fixedWidthMode = AppConfig.bookshelfFixedWidthMode
            val gridWidth = AppConfig.bookshelfGridWidth
            var introLines = AppConfig.bookshelfListIntroLines
            val alertBinding =
                DialogBookshelfConfigBinding.inflate(layoutInflater)
                    .apply {
                        if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                            AppConfig.bookGroupStyle = 0
                        }
                        if (bookshelfLayout !in 0..6) {
                            bookshelfLayout = 0
                            AppConfig.bookshelfLayout = 0
                        }
                        if (bookshelfSort !in rgSort.indices) {
                            bookshelfSort = 0
                            AppConfig.bookshelfSort = 0
                        }
                        spGroupStyle.setSelection(AppConfig.bookGroupStyle)
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
                        sbColumnCount.progress = bookshelfLayout
                        tvColumnValue.text =
                            if (sbColumnCount.progress <= 1) getString(R.string.layout_list) else sbColumnCount.progress.toString()
                        etGridWidth.setText(gridWidth.toString())
                        llColumnCount.visibility =
                            if (fixedWidthMode) View.GONE else View.VISIBLE
                        llFixedWidth.visibility =
                            if (fixedWidthMode) View.VISIBLE else View.GONE
                        val updateListOnlyVisibility = {
                            val isList = !swFixedWidthMode.isChecked && sbColumnCount.progress <= 1
                            val v = if (isList) View.VISIBLE else View.GONE
                            swShowKind.visibility = v
                            swShowIntro.visibility = v
                            llIntroLines.visibility = v
                            swShowLastUpdateTime.visibility = v
                        }
                        updateListOnlyVisibility()
                        sbColumnCount.setOnSeekBarChangeListener(object :
                            SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                seekBar: SeekBar?,
                                progress: Int,
                                fromUser: Boolean
                            ) {
                                tvColumnValue.text =
                                    if (progress <= 1) getString(R.string.layout_list) else progress.toString()
                                updateListOnlyVisibility()
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                        })
                        swFixedWidthMode.setOnCheckedChangeListener { _, isChecked ->
                            fixedWidthMode = isChecked
                            llColumnCount.visibility =
                                if (isChecked) View.GONE else View.VISIBLE
                            llFixedWidth.visibility =
                                if (isChecked) View.VISIBLE else View.GONE
                            updateListOnlyVisibility()
                        }
                        rgSort.checkByIndex(bookshelfSort)
                    }
            customView { alertBinding.root }
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
                    val newLayout = sbColumnCount.progress
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

}
