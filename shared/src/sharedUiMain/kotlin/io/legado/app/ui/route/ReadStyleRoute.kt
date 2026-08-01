package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.ChineseConverterSelectorDialog
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.read.config.FontSelectDialog
import io.legado.app.ui.book.read.config.ReadStyleActions
import io.legado.app.ui.book.read.config.ReadStyleController
import io.legado.app.ui.book.read.config.ReadStyleScreen
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.text_font
import org.jetbrains.compose.resources.stringResource

/**
 * 阅读样式配置 shared 路由入口。
 *
 * [ReadStyleController] 字段读写委托 [ReadBookConfigProviders] / [AppConfigProviders],
 * [ReadStyleActions] 接入 [AppNavigator] / 内嵌对话框, 行为对齐 app 端 ReadStyleDialog。
 * Route 退出时持久化, 对齐 app 端 onDismiss -> ReadBookConfig.save()。
 */
@Composable
fun ReadStyleRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    // 顶栏标题 (对照 app 端 R.string.text_font, 与 ReadConfigScreen 入口项一致)
    val titleStr = stringResource(Res.string.text_font)

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        ReadStyleContent(
            onShowPaddingConfig = { navigator.push(AppRoute.PaddingConfig) },
            onShowTipConfig = { navigator.push(AppRoute.TipConfig) },
            onShowBgTextConfig = { navigator.push(AppRoute.BgTextConfig) },
        )
    }
}

/**
 * 阅读样式设置底部弹窗形态 (对照原版 ReadStyleDialog: BaseBottomDialogFragment, 无标题栏)。
 * 由阅读菜单"界面"按钮弹起, 行为与 [ReadStyleRoute] 一致。
 */
@Composable
fun ReadStyleDialogHost(
    onDismiss: () -> Unit,
    onShowPaddingConfig: () -> Unit,
    onShowTipConfig: () -> Unit,
    onShowBgTextConfig: (Int) -> Unit,
) {
    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.bottomBackground,
                modifier = Modifier.appDialogSize().padding(16.dp),
            ) {
                ReadStyleContent(
                    onShowPaddingConfig = onShowPaddingConfig,
                    onShowTipConfig = onShowTipConfig,
                    onShowBgTextConfig = onShowBgTextConfig,
                )
            }
        }
    }
}

/** 阅读样式正文 (Screen + 内嵌对话框), 路由/弹窗两形态共用 */
@Composable
private fun ReadStyleContent(
    onShowPaddingConfig: () -> Unit,
    onShowTipConfig: () -> Unit,
    onShowBgTextConfig: (Int) -> Unit,
) {
    val readBookConfig = ReadBookConfigProviders.get()
    var showFontSelect by remember { mutableStateOf(false) }
    var showChineseConverter by remember { mutableStateOf(false) }

    // 字体列表: 平台扫描注入 (对照 app 端 FontSelectDialog.loadFontFiles; 未实现端空列表)
    var fontItems by remember { mutableStateOf(emptyList<FontItem>()) }
    LaunchedEffect(Unit) {
        fontItems = withContext(IoDispatcher) {
            PlatformCapabilityProviders.get().scanFontItems()
        }
    }

    // 退出时持久化 (对齐 app 端 ReadStyleDialog.onDismiss -> ReadBookConfig.save())
    DisposableEffect(Unit) {
        onDispose { readBookConfig.save() }
    }

    val controller = remember {
        object : ReadStyleController {
            override var textBold: Int
                get() = readBookConfig.textBold
                set(value) {
                    readBookConfig.textBold = value
                }
            override var chineseType: Int
                get() = AppConfigProviders.get().chineseConverterType
                set(value) {
                    AppConfigProviders.get().chineseConverterType = value
                }
            override var pageAnim: Int
                get() = readBookConfig.pageAnim
                set(value) {
                    readBookConfig.pageAnim = value
                }
            override var shareLayout: Boolean
                get() = readBookConfig.shareLayout
                set(value) {
                    readBookConfig.shareLayout = value
                }
            override var textSize: Int
                get() = readBookConfig.textSize
                set(value) {
                    readBookConfig.textSize = value
                }
            override var letterSpacing: Float
                get() = readBookConfig.letterSpacing
                set(value) {
                    readBookConfig.letterSpacing = value
                }
            override var lineSpacingExtra: Int
                get() = readBookConfig.lineSpacingExtra
                set(value) {
                    readBookConfig.lineSpacingExtra = value
                }
            override var paragraphSpacing: Int
                get() = readBookConfig.paragraphSpacing
                set(value) {
                    readBookConfig.paragraphSpacing = value
                }
            override var styleSelect: Int
                get() = readBookConfig.styleSelect
                set(value) {
                    readBookConfig.styleSelect = value
                }
            override var paragraphIndent: String
                get() = readBookConfig.paragraphIndent
                set(value) {
                    readBookConfig.paragraphIndent = value
                }
            override val configList: List<ReadStyleConfig> = readBookConfig.configList
            override fun curTextColor(): Int = readBookConfig.durConfig.curTextColor()
            override fun addStyle() {
                readBookConfig.configList.add(ReadStyleConfig())
            }

            override fun save() = readBookConfig.save()
        }
    }

    val actions = object : ReadStyleActions {
        override fun showFontSelect() {
            showFontSelect = true
        }

        override fun showChineseConverter() {
            showChineseConverter = true
        }

        override fun showPaddingConfig() {
            onShowPaddingConfig()
        }

        override fun showTipConfig() {
            onShowTipConfig()
        }

        override fun showBgTextConfig(index: Int) {
            onShowBgTextConfig(index)
        }

        override fun onUpPageAnim() {
            // 对照 app 端 callBack.upPageAnim() + ReadBook.loadContent(false)
            ReadBookEvents.postConfig(
                ReadConfigChange.PAGE_ANIM, ReadConfigChange.LOAD_CONTENT
            )
        }

        override fun onPostConfig(changes: List<ReadConfigChange>) {
            ReadBookEvents.postConfig(changes)
        }
    }

    ReadStyleScreen(
        controller = controller,
        actions = actions,
        bgPreviewSlot = { _, _, _, _ -> },
    )

    // 字体选择对话框 (fontItems 由平台 [PlatformCapabilityProviders] 扫描注入)
    if (showFontSelect) {
        FontSelectDialog(
            fontItems = fontItems,
            curFontPath = readBookConfig.textFont,
            curFontName = readBookConfig.textFont.substringAfterLast('/'),
            onSelectFont = { path ->
                readBookConfig.textFont = path
                actions.onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT))
            },
            onSelectDefault = {
                readBookConfig.textFont = ""
                actions.onPostConfig(listOf(ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT))
            },
            onDismiss = { showFontSelect = false },
        )
    }

    // 简繁转换选择器 (自包含: 内部写 AppConfigProviders)
    if (showChineseConverter) {
        ChineseConverterSelectorDialog(
            currentType = AppConfigProviders.get().chineseConverterType,
            onChanged = {
                actions.onPostConfig(listOf(ReadConfigChange.LOAD_CONTENT))
            },
            onDismiss = { showChineseConverter = false },
        )
    }
}
