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
import io.legado.app.ui.book.manga.extension.MangaExtensionService
import io.legado.app.ui.book.manga.extension.MangaExtensionServiceProviders
import io.legado.app.ui.book.manga.extension.MangaPrefDialogState
import io.legado.app.ui.book.manga.extension.MangaPrefItem
import io.legado.app.ui.book.manga.extension.MangaPrefTextSession
import io.legado.app.ui.book.manga.extension.MangaPrefValue
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
 * (对齐 MangaExtensionScreenModel.setPreference)。刷新结果只发布给本次打开的状态;
 * 取消照常向上传播, 不记业务失败。
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
    val prefs = remember(pkgName) { PrefOverlaySession(pkgName, service) }
    DisposableEffect(prefs) {
        onDispose { prefs.cancel() }
    }
    LaunchedEffect(prefs) {
        prefs.load()
    }
    ExtensionPrefDialog(
        dialog = prefs.dialog,
        settingText = stringResource(Res.string.manga_extension_setting),
        onSetPreference = { item, value -> scope.launch { prefs.setPreference(item, value) } },
        onPreferenceClick = { item -> scope.launch { prefs.performClick(item) } },
        onPreferenceChange = { item, newValue ->
            scope.launch { prefs.applyChange(item, newValue) }
        },
        onTextItemClick = { item -> prefs.textItemClick(item) },
        // 活编辑会话在槽实现内落值, 这里只负责重读刷新 (与写后同口径)
        onReloadPrefItems = { scope.launch { prefs.reload() } },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

/**
 * Overlay 一次打开的状态与动作: 入口关闭后作废发布资格; 未完成请求由组合 scope 取消,
 * 取消异常照常传播, 不发布失败态。
 */
private class PrefOverlaySession(
    private val pkgName: String,
    private val service: MangaExtensionService?,
) {

    /** 本次打开身份 (仅 UI 状态/作用域隔离用, 不持久化)。 */
    private val requestId: Long = ++nextOverlayRequestId

    private var active: Boolean = true

    var dialog: MangaPrefDialogState by mutableStateOf(
        MangaPrefDialogState(pkgName = pkgName, requestId = requestId)
    )
        private set

    fun cancel() {
        active = false
    }

    suspend fun load() {
        if (service == null) {
            fail()
            return
        }
        reload()
    }

    suspend fun setPreference(item: MangaPrefItem, value: MangaPrefValue) {
        if (!active) return
        val svc = service ?: return
        try {
            svc.setPreferenceValue(item, value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("插件配置写入失败 $pkgName", e)
            fail()
            return
        }
        // 写后重读刷新弹窗当前值 (与插件管理页行为一致)
        reload()
    }

    suspend fun performClick(item: MangaPrefItem) {
        if (!active) return
        val svc = service ?: return
        try {
            svc.performPreferenceClick(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("插件配置点击失败 $pkgName", e)
            fail()
            return
        }
        // 点击可能联动改值 (回调返回 true), 重读刷新弹窗当前值
        reload()
    }

    suspend fun applyChange(item: MangaPrefItem, newValue: Boolean) {
        if (!active) return
        val svc = service ?: return
        try {
            svc.applyPreferenceChange(item, newValue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("插件配置写入失败 $pkgName", e)
            fail()
            return
        }
        reload()
    }

    /** 真实文本项点击: 插件监听故障 (含 ABI 缺类) 记录日志并保持无会话, 取消照常传播。 */
    suspend fun textItemClick(item: MangaPrefItem): MangaPrefTextSession? {
        if (!active) return null
        val session = try {
            service?.clickTextEditor(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("插件配置点击失败 $pkgName", e)
            null
        }
        if (!active) return null
        // 点击可能联动改值, 会话取得后重读刷新
        reload()
        return session.takeIf { active }
    }

    suspend fun reload() {
        if (!active) return
        val svc = service ?: return
        val items = try {
            svc.buildPreferenceItems(pkgName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail()
            return
        }
        if (!active) return
        dialog = dialog.copy(loading = false, failed = false, items = items)
    }

    private fun fail() {
        if (active) dialog = dialog.copy(loading = false, failed = true)
    }
}

/** Overlay 各次打开的 UI 身份来源 (由 Compose 主线程串行创建, 不需要并发原语)。 */
private var nextOverlayRequestId: Long = 0
