package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.manga.extension.ExtensionPrefDialog
import io.legado.app.ui.book.manga.extension.MangaExtensionServiceProviders
import io.legado.app.ui.book.manga.extension.MangaPrefCacheOwner
import io.legado.app.ui.book.manga.extension.MangaPrefDialogState
import kotlinx.coroutines.CancellationException
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
 * 回填 items; 写入/点击联动改值/活编辑会话落值后重读刷新当前值
 * (对齐 MangaExtensionScreenModel.setPreference)。
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
    val cacheOwner = remember { MangaPrefCacheOwner.create() }
    var state by remember(pkgName) { mutableStateOf(MangaPrefDialogState(pkgName)) }

    // 重读刷新弹窗当前值 (初载/写后/点击后/落值后同一口径; 失败如实呈现 failed 态)
    suspend fun refreshState() {
        val svc = service ?: return
        runCatching { svc.buildPreferenceItems(pkgName, cacheOwner) }
            .onSuccess { items ->
                state = MangaPrefDialogState(pkgName, loading = false, items = items)
            }
            .onFailure {
                state = MangaPrefDialogState(pkgName, loading = false, failed = true)
            }
    }

    LaunchedEffect(pkgName) {
        if (service == null) {
            state = MangaPrefDialogState(pkgName, loading = false, failed = true)
            return@LaunchedEffect
        }
        refreshState()
    }
    DisposableEffect(cacheOwner) {
        onDispose {
            // Overlay 关闭: 定点释放本归属的构建产物 (不影响另一入口已替换的构建)
            service?.releasePreferenceCache(cacheOwner)
        }
    }
    ExtensionPrefDialog(
        dialog = state,
        settingText = stringResource(Res.string.manga_extension_setting),
        onSetPreference = { item, value ->
            scope.launch {
                val svc = service ?: return@launch
                runCatching { svc.setPreferenceValue(item, value) }
                    .onFailure {
                        state = MangaPrefDialogState(pkgName, loading = false, failed = true)
                        return@launch
                    }
                // 写后重读刷新弹窗当前值 (与插件管理页行为一致)
                refreshState()
            }
        },
        onPreferenceClick = { item ->
            scope.launch {
                val svc = service ?: return@launch
                runCatching { svc.performPreferenceClick(item) }
                    .onFailure {
                        state = MangaPrefDialogState(pkgName, loading = false, failed = true)
                        return@launch
                    }
                // 点击可能联动改值 (回调返回 true), 重读刷新弹窗当前值
                refreshState()
            }
        },
        onPreferenceChange = { item, newValue ->
            scope.launch {
                val svc = service ?: return@launch
                runCatching { svc.applyPreferenceChange(item, newValue) }
                    .onFailure {
                        state = MangaPrefDialogState(pkgName, loading = false, failed = true)
                        return@launch
                    }
                refreshState()
            }
        },
        // 真实文本项点击: 平台完成 performClick 合同并返回活编辑会话; 插件监听抛错与
        // 取消区分 (取消重抛, 故障记录日志并保持无会话), 点击可能联动改值, 重读刷新
        onTextItemClick = { item ->
            val session = try {
                service?.clickTextEditor(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("插件配置点击失败 $pkgName", e)
                null
            }
            refreshState()
            session
        },
        // 活编辑会话在槽实现内落值, 这里只负责重读刷新 (与写后同口径)
        onReloadPrefItems = { scope.launch { refreshState() } },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}
