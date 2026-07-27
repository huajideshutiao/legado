package io.legado.app.ui.config

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment

/**
 * 封面设置宿主（原 CoverConfigFragment 壳上浮）。
 * 动态 summary（封面数量、封面高度）用 Compose state 承接——
 * 默认封面由 DefaultCoverGalleryDialog 写 prefs，故仍注册 prefs 变更监听保证实时刷新。
 */
class CoverConfigHost(activity: ConfigActivity) : ConfigHost(activity),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var dayCoverSummary by mutableStateOf("")
    private var nightCoverSummary by mutableStateOf("")
    private var coverHeightSummary by mutableStateOf("")

    init {
        dayCoverSummary = coverCountSummary(activity, PreferKey.defaultCover)
        nightCoverSummary = coverCountSummary(activity, PreferKey.defaultCoverDark)
        coverHeightSummary = coverHeightSummary(activity)
        activity.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(
                title = stringResource(R.string.cover_config),
                onBack = { activity.finish() },
            )
            Box(Modifier.weight(1f)) {
                CoverConfigScreen(
                    onDefaultCover = { isNight ->
                        activity.showDialogFragment(DefaultCoverGalleryDialog(isNight = isNight))
                    },
                    onCoverHeight = ::pickCoverHeight,
                    coverHeightSummary = coverHeightSummary,
                    dayCoverSummary = dayCoverSummary,
                    nightCoverSummary = nightCoverSummary,
                    // 注入 BookCover.upDefaultCover() (BookCover 重 Android 依赖, 留 app 端)
                    onRefreshCover = { BookCover.upDefaultCover() },
                )
            }
        }
    }

    override fun onDestroy() {
        activity.defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.defaultCover -> dayCoverSummary = coverCountSummary(activity, key)
            PreferKey.defaultCoverDark -> nightCoverSummary = coverCountSummary(activity, key)
        }
    }

    private fun pickCoverHeight() {
        showNumberPicker(
            activity,
            titleResId = R.string.bookshelf_cover_height,
            max = 220, min = 90, value = AppConfig.bookshelfCoverHeight,
            neutralButton = R.string.btn_default_s to {
                AppConfig.bookshelfCoverHeight = 120
                coverHeightSummary = coverHeightSummary(activity)
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }
        ) {
            AppConfig.bookshelfCoverHeight = it
            coverHeightSummary = coverHeightSummary(activity)
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

}

/** 封面数量 summary（复刻 upPreferenceSummary：0 张显示"选择图片"，否则显示张数）。
 *  从 app 端原 CoverConfigScreen.kt 迁移至宿主（依赖 Context + BookCover.listDefaultCovers, 无法跨平台）。 */
fun coverCountSummary(context: android.content.Context, prefKey: String): String {
    val count = BookCover.listDefaultCovers(prefKey).size
    return if (count == 0) {
        context.getString(R.string.select_image)
    } else {
        context.getString(R.string.default_cover_count, count)
    }
}

/** 封面高度 summary（复刻 "Current: %sdp"）。
 *  从 app 端原 CoverConfigScreen.kt 迁移至宿主（依赖 Context + AppConfig.bookshelfCoverHeight, 无法跨平台）。 */
fun coverHeightSummary(context: android.content.Context): String {
    return context.getString(R.string.bookshelf_cover_height_summary, "${AppConfig.bookshelfCoverHeight}dp")
}
