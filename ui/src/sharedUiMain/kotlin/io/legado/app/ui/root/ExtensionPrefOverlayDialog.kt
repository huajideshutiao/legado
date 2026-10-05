package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.ui.book.manga.extension.ExtensionPrefDialog
import io.legado.app.ui.book.manga.extension.MangaExtensionServiceProviders
import io.legado.app.ui.book.manga.extension.MangaPrefDialogState
import io.legado.app.ui.book.manga.extension.MangaPrefValue
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.manga_extension_setting
import org.jetbrains.compose.resources.stringResource

/**
 * 插件虚拟源登录直达 Overlay (key="extensionPref"): 只承载单个扩展自带设置弹窗。
 *
 * 书源列表/发现页/详情页/阅读器的插件虚拟源登录入口 (showSourceLogin 虚拟分支) 直接弹
 * 本对话框, 不进插件管理页 (2026-10-05 用户拍板: 登录=直接只弹该扩展的设置页)。
 * 弹窗渲染与插件管理页行内「设置」共用 [ExtensionPrefDialog]; payload 即归属扩展包名
 * (虚拟行 loginUrl 由同步逻辑写入, 仅可配置扩展有登录入口)。
 *
 * 状态 (loading/失败/空表) 在本组合自管: 打开即加载, 平台读完 shim PreferenceScreen
 * 回填 items; 写入后重读刷新当前值 (对齐 MangaExtensionScreenModel.setPreference)。
 */
@Composable
internal fun ExtensionPrefOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val pkgName = overlay.payload
    if (pkgName.isNullOrBlank()) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    val service = MangaExtensionServiceProviders.getOrNull()
    val scope = rememberCoroutineScope()
    var state by remember(pkgName) { mutableStateOf(MangaPrefDialogState(pkgName)) }
    LaunchedEffect(pkgName) {
        val svc = service
        if (svc == null) {
            state = MangaPrefDialogState(pkgName, loading = false, failed = true)
            return@LaunchedEffect
        }
        runCatching { svc.buildPreferenceItems(pkgName) }
            .onSuccess { items ->
                state = MangaPrefDialogState(pkgName, loading = false, items = items)
            }
            .onFailure {
                state = MangaPrefDialogState(pkgName, loading = false, failed = true)
            }
    }
    ExtensionPrefDialog(
        dialog = state,
        settingText = stringResource(Res.string.manga_extension_setting),
        onSetPreference = { key, value ->
            scope.launch {
                val svc = service ?: return@launch
                runCatching { svc.setPreferenceValue(pkgName, key, value) }
                    .onFailure {
                        state = MangaPrefDialogState(pkgName, loading = false, failed = true)
                        return@launch
                    }
                // 写后重读刷新弹窗当前值 (与插件管理页行为一致)
                runCatching { svc.buildPreferenceItems(pkgName) }
                    .onSuccess { items ->
                        state = MangaPrefDialogState(pkgName, loading = false, items = items)
                    }
                    .onFailure { state = MangaPrefDialogState(pkgName, loading = false, failed = true) }
            }
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}
