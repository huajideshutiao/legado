package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setupAsBottomDialog

/**
 * 阅读界面更多设置（迁 pref_config_read.xml → Compose）。
 * ReadBookActivity 弹出契约不变：bottomDialog 计数、480dp 底部弹窗、底栏色背景。
 * prefs 变更监听承接各 key 的事件广播，与原 ReadPreferenceFragment 逐条一致。
 */
class MoreConfigDialog : BasePrefDialogFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onStart() {
        super.onStart()
        dialog?.window?.setupAsBottomDialog(480.dpToPx())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as ReadBookActivity).bottomDialog++
        val slopSquare = ViewConfiguration.get(requireContext()).scaledTouchSlop
        val pageTouchSlopSummary =
            getString(R.string.page_touch_slop_summary, slopSquare.toString())
        return ComposeView(requireContext()).apply {
            setBackgroundColor(requireContext().bottomBackground)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 注入 Android actual Provider，供 Screen 内部 AppTheme 通过 LocalXxx 取依赖
                val themeStoreProvider = remember { AndroidThemeStoreProvider() }
                val appConfigProvider = remember { AndroidAppConfigProvider() }
                val eventBusProvider = remember { AndroidEventBusProvider() }
                val preferenceStoreProvider = remember { AndroidPreferenceStoreProvider() }
                CompositionLocalProvider(
                    LocalThemeStoreProvider provides themeStoreProvider,
                    LocalAppConfigProvider provides appConfigProvider,
                    LocalEventBusProvider provides eventBusProvider,
                    LocalPreferenceStoreProvider provides preferenceStoreProvider,
                ) {
                    MoreConfigScreen(
                        pageTouchSlopSummary = pageTouchSlopSummary,
                        onPageTouchSlop = ::pickPageTouchSlop,
                        onClickRegionalConfig = {
                            (activity as? ReadBookActivity)?.showClickRegionalConfig()
                        },
                        onCustomPageKey = {
                            PageKeyDialog().show(
                                requireActivity().supportFragmentManager,
                                "pageKey"
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().defaultSharedPreferences
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        requireContext().defaultSharedPreferences
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        when (key) {
            PreferKey.hideStatusBar,
            PreferKey.hideNavigationBar -> {
                ReadBookConfig.reloadHideBarPrefs()
                ReadBookEvents.postConfig(ReadConfigChange.SYSTEM_UI, ReadConfigChange.STYLE)
            }

            PreferKey.keepLight -> ReadBookEvents.postKeepLightChange()
            PreferKey.screenOrientation -> {
                (activity as? ReadBookActivity)?.setOrientation()
            }

            PreferKey.textFullJustify,
            PreferKey.textBottomJustify,
            PreferKey.useZhLayout -> {
                ReadBookEvents.postConfig(ReadConfigChange.LOAD_CONTENT)
            }

            PreferKey.doublePageHorizontal -> {
                ChapterProvider.upLayout()
                ReadBook.loadContent(false)
            }

            PreferKey.showReadTitleAddition -> {
                ReadBookEvents.postActionBarChange()
            }

            PreferKey.progressBarBehavior -> {
                ReadBookEvents.postSeekBarChange()
            }

        }
    }

    private fun pickPageTouchSlop() {
        showNumberPicker(
            requireContext(),
            titleResId = R.string.page_touch_slop_dialog_title,
            max = 9999, min = 0, value = AppConfig.pageTouchSlop
        ) {
            AppConfig.pageTouchSlop = it
            ReadBookEvents.postConfig(ReadConfigChange.PAGE_SLOP)
        }
    }

}
