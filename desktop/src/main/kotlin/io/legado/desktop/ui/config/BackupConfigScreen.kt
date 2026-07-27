package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.desktop.ui.webdav.WebDavConfigScreen

/**
 * 桌面端"备份设置" Screen 入口 (包装已有 [WebDavConfigScreen])。
 *
 * # 职责
 *
 * - 在 [WebDavConfigScreen] 之上加 [AppTitleBar] (标题"备份设置" + 返回按钮)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] / 内部 shared
 *   BackupConfigScreen 通过 LocalXxx 取依赖
 * - 复用已有 [WebDavConfigScreen] (其内部已包装 shared BackupConfigScreen,
 *   并附加 6 个 summary + 6 个 callbacks 装配)
 *
 * # 路由
 *
 * 由 [io.legado.desktop.ui.DesktopRoute.BACKUP_CONFIG] 路由分支调用,
 * 入口由 SettingsScreen 的"备份与恢复"项触发 (替代原 onBackupConfig → WEBDAV)。
 *
 * # 设计说明
 *
 * shared/sharedUiMain 的 [io.legado.app.ui.config.BackupConfigScreen] 已被
 * [WebDavConfigScreen] 内部包装 (含 WebDav 备份/恢复真实下沉逻辑),
 * 此处不重复装配 summary/callbacks, 仅作"带返回按钮的入口页"包装, 避免重复。
 *
 * @param onBack 返回回调 (切回 MY 路由, 由 DesktopApp 注入)
 */
@Composable
fun BackupConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / WebDavConfigScreen 内部 shared BackupConfigScreen 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("backup_config"),
                        onBack = onBack,
                    )
                    // 主体内容复用已有 WebDavConfigScreen (其内部已包装 shared BackupConfigScreen)
                    // weight(1f) 让其占满 AppTitleBar 下方剩余高度
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        WebDavConfigScreen()
                    }
                }
            }
        }
    }
}
