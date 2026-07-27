package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端封面设置页入口 (包装 shared/sharedUiMain 的 [CoverConfigScreen])。
 *
 * @param onBack 返回回调
 */
@Composable
fun IosCoverConfigScreen(
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("cover_config"),
            onBack = onBack,
        )
        // KP-iOS: 默认封面/封面高度 stub; onRefreshCover no-op (BookCover 是 Android 专属)
        // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
        val defaultCoverText = rememberString("ios_default_cover_not_implemented")
        val coverHeightText = rememberString("ios_cover_height_not_implemented")
        CoverConfigScreen(
            onDefaultCover = { _ ->
                // TODO: iOS 端默认封面选择 (pickDocuments), KP6+ 接入
                Toasters.get().toast(defaultCoverText)
            },
            onCoverHeight = {
                // TODO: iOS 端 NumberPicker 封面高度, KP6+ 接入
                Toasters.get().toast(coverHeightText)
            },
            coverHeightSummary = "",
            dayCoverSummary = "",
            nightCoverSummary = "",
            onRefreshCover = {
                // iOS 端无 BookCover 缓存, no-op
            },
        )
    }
}
