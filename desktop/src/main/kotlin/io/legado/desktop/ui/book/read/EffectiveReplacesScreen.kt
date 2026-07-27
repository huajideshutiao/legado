package io.legado.desktop.ui.book.read

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.book.read.EffectiveReplacesScreen as SharedEffectiveReplacesScreen
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 桌面端"有效替换" Screen 入口 (包装 shared/sharedUiMain 的 [SharedEffectiveReplacesScreen])。
 *
 * # 职责
 *
 * - 用 [Surface] 包裹 [SharedEffectiveReplacesScreen], 让其 DialogTitleBar 作为页面标题栏
 *   (原为对话框正文, 桌面端无 Dialog 宿主, 升格为全屏页面, DialogTitleBar 的 onBack
 *   即页面 onBack)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] /
 *   [SharedEffectiveReplacesScreen] 通过 LocalXxx 取依赖
 *
 * # 简化项 (依赖未下沉的功能用 TODO 注释 + no-op)
 *
 * - items: shared 端设计为对话框, 由 ReadBookViewModel 持有当前章节起效的替换规则
 *   (BookContent.effectiveReplaceRules); 桌面端 ReadBookViewModelShared 未暴露该字段,
 *   暂传空列表, 后续接入当前阅读上下文后补全
 * - onAddRule: app 端启动 ReplaceEditActivity (新增针对当前书籍的替换规则),
 *   桌面端虽有 ReplaceEditScreen 路由, 但缺少"当前书籍上下文"参数桥接, 暂 no-op
 * - onItemClick: app 端启动 ReplaceEditActivity (编辑) 或 ChineseConvert alert,
 *   桌面端 ChineseConvert 未下沉, 暂 no-op
 * - onManageAll: app 端启动 ReplaceRuleActivity (全局替换规则管理),
 *   桌面端 ReplaceRuleScreen 在侧栏 REPLACE 路由, 不便从此处跳转, 暂 no-op
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun EffectiveReplacesScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / SharedEffectiveReplacesScreen 取依赖
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
                EffectiveReplacesContent(onBack = onBack)
            }
        }
    }
}

/**
 * 装配 items (空列表) + 4 个回调, 位置传参调用 [SharedEffectiveReplacesScreen]。
 *
 * 与 app 端 EffectiveReplacesDialog 内 EffectiveReplacesScreen(...) 调用对齐,
 * 差异见顶层 KDoc。
 */
@Composable
private fun EffectiveReplacesContent(onBack: () -> Unit) {
    // TODO: 接入当前阅读书籍上下文后, 从 ReadBookViewModelShared 取 BookContent.effectiveReplaceRules
    val items: List<ReplaceRule> = remember { emptyList() }

    SharedEffectiveReplacesScreen(
        items = items,
        onAddRule = {
            // TODO: 桌面端 ReplaceEditScreen 缺少"当前书籍上下文"参数桥接, 暂不接入
        },
        onItemClick = { _ ->
            // TODO: 依赖 ReplaceEditActivity / ChineseConvert alert, 桌面端未下沉
        },
        onManageAll = {
            // TODO: 全局替换规则管理在侧栏 REPLACE 路由, 此处暂不跳转
        },
        onDismiss = onBack,
    )
}
