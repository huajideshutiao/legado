package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadConfigDefaults
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.BgTextConfigActions
import io.legado.app.ui.book.read.config.BgTextConfigController
import io.legado.app.ui.book.read.config.BgTextConfigScreen
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.import_on_line
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.text_bg_style
import org.jetbrains.compose.resources.stringResource

/**
 * 背景文字配置 shared 路由入口。
 * 通过 [BgTextConfigContent] 复用配置屏幕, 本路由负责标题栏 + 导航 (pop)。
 */
@Composable
fun BgTextConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val titleStr = stringResource(Res.string.text_bg_style)
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        BgTextConfigContent()
    }
}

/**
 * 背景文字配置弹窗形态 (对照原版 BgTextConfigDialog)。
 * 由界面设置弹窗"背景文字"入口弹起。
 */
@Composable
fun BgTextConfigDialogHost(
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                modifier = Modifier.appDialogSize().padding(16.dp),
            ) {
                Column {
                    DialogTitleBar(
                        title = stringResource(Res.string.text_bg_style),
                        onBack = onDismiss,
                    )
                    BgTextConfigContent()
                }
            }
        }
    }
}

/**
 * 背景文字配置正文 (路由/弹窗两形态共用)。
 *
 * 暂无对应 ScreenModel, [BgTextConfigController] / [BgTextConfigActions] 直接接入
 * [ReadBookConfigProviders] / [PlatformServiceProviders] / [ReadBookEvents],
 * 行为对齐 app 端 BgTextConfigDialog + BgTextConfigViewModel。
 *
 * - controller 字段读写委托 [ReadBookConfigProviders.get].durConfig
 * - 文件选择 (导入 zip / 导出 zip / 选背景图) 走 [PlatformServiceProviders] 文件选择器
 * - 网络导入用 [OkHttpClientProviders] 下载 + 内嵌 URL 输入对话框
 * - 配置变更通知走 [ReadBookEvents.postConfig]
 */
@Composable
fun BgTextConfigContent() {
    val readBookConfig = ReadBookConfigProviders.get()
    val scope = rememberCoroutineScope()
    var showUrlInput by remember { mutableStateOf(false) }

    val controller = remember {
        object : BgTextConfigController {
            override var name: String
                get() = readBookConfig.durConfig.name
                set(value) {
                    readBookConfig.durConfig.name = value
                    // 改名即落盘 (对照原版 BgTextConfigDialog.onDismiss -> ReadBookConfig.save,
                    // 这里提前到确认时, 防进程被杀丢配置)
                    readBookConfig.save()
                }

            override fun darkStatusIcon(): Boolean =
                readBookConfig.durConfig.curStatusIconDark()

            override fun setCurStatusIconDark(value: Boolean) {
                readBookConfig.durConfig.setCurStatusIconDark(value)
                readBookConfig.save()
            }

            override fun underline(): Boolean = readBookConfig.durConfig.underline

            override fun setUnderline(value: Boolean) {
                readBookConfig.durConfig.underline = value
                readBookConfig.save()
            }

            override fun bgAlpha(): Int = readBookConfig.bgAlpha

            override fun setBgAlpha(value: Int) {
                // 滑条连续回调, 不逐帧落盘; 关闭时经 onDispose save 统一持久化
                readBookConfig.bgAlpha = value
            }

            override fun curTextColor(): Int = readBookConfig.durConfig.curTextColor()

            override fun curBgType(): Int = readBookConfig.durConfig.curBgType()

            override fun curBgStr(): String = readBookConfig.durConfig.curBgStr()

            override fun setCurTextColor(color: Int) {
                readBookConfig.durConfig.setCurTextColor(color)
                // 取色确认即落盘 (对照原版 BgTextConfigDialog.onDismiss -> save,
                // 这里提前到确认时, 防进程被杀丢配置)
                readBookConfig.save()
            }

            override fun setCurBg(type: Int, value: String) {
                readBookConfig.durConfig.setCurBg(type, value)
                // 取色/选背景图确认即落盘 (同上)
                readBookConfig.save()
            }

            // 删除当前主题 (对照 app 端 ReadBookConfig.deleteDur)
            override fun deleteDur(): Boolean {
                val deleted = readBookConfig.deleteDur()
                if (deleted) readBookConfig.save()
                return deleted
            }

            // 持久化配置 (对照 app 端 ReadBookConfig.save)
            override fun save() = readBookConfig.save()

            // 预设布局名列表 (对照 app 端 DefaultData.readConfigs.map { it.name })
            override fun restorePresetNames(): List<String> =
                ReadConfigDefaults.readConfigs.map { it.name }

            // 恢复预设布局 (对照 app 端 ReadBookConfig.durConfig = defaultConfigs[i].copy())
            override fun restorePreset(index: Int) {
                readBookConfig.durConfig = ReadConfigDefaults.readConfigs[index].copy()
                readBookConfig.save()
            }
        }
    }

    val actions = object : BgTextConfigActions {
        // 导入配置 zip: 平台文件选择器选 zip → importFromPath → 更新 durConfig → postConfig
        override fun onImportConfig() {
            val services = PlatformServiceProviders.getOrNull() ?: return
            scope.launch {
                runCatching {
                    val path = withContext(IoDispatcher) {
                        services.files.pickFile(FileFilter(extensions = listOf("zip")))
                    } ?: return@launch
                    val config = withContext(IoDispatcher) {
                        readBookConfig.importFromPath(path)
                    }
                    readBookConfig.durConfig = config
                    // 导入后立即落盘 (对照原版 onDismiss -> save, 提前到导入完成时)
                    readBookConfig.save()
                }.onSuccess {
                    ReadBookEvents.postConfig(
                        ReadConfigChange.BG, ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT
                    )
                    Toasters.get().toast("导入成功")
                }.onFailure {
                    Toasters.get().toast("导入失败:${it.message}")
                }
            }
        }

        // 导出配置 zip: exportConfigZip 生成临时 zip → 平台文件选择器选保存路径 → 复制 → 提示
        override fun onExportConfig() {
            val services = PlatformServiceProviders.getOrNull() ?: return
            scope.launch {
                runCatching {
                    val exportFileName = if (readBookConfig.config.name.isBlank()) {
                        "readConfig.zip"
                    } else {
                        "${readBookConfig.config.name}.zip"
                    }
                    val tempZipPath = withContext(IoDispatcher) {
                        readBookConfig.exportConfigZip()
                    }
                    val savePath = withContext(IoDispatcher) {
                        services.files.saveFile(exportFileName)
                    } ?: return@launch
                    withContext(IoDispatcher) {
                        BackupFileOps.copyFile(tempZipPath, savePath)
                    }
                }.onSuccess {
                    Toasters.get().toast("导出成功")
                }.onFailure {
                    Toasters.get().toast("导出失败:${it.message}")
                }
            }
        }

        // 网络导入: 弹 URL 输入框 (对照 app 端 importNetConfigAlert)
        override fun onImportNetConfig() {
            showUrlInput = true
        }

        // 选择背景图: 平台文件选择器选图 → setBgFromPath 复制到 bg 目录 → setCurBg(2, fileName) → postConfig
        override fun onSelectBgImage() {
            val services = PlatformServiceProviders.getOrNull() ?: return
            scope.launch {
                runCatching {
                    val path = withContext(IoDispatcher) {
                        services.files.pickFile(FileFilter.Images)
                    } ?: return@launch
                    val fileName = withContext(IoDispatcher) {
                        readBookConfig.setBgFromPath(path)
                    }
                    readBookConfig.durConfig.setCurBg(2, fileName)
                }.onSuccess {
                    ReadBookEvents.postConfig(ReadConfigChange.BG)
                }.onFailure {
                    Toasters.get().toast(it.message ?: "设置背景图失败")
                }
            }
        }

        // 选择 assets 背景图预设 (对照 app 端 setCurBg(1, fileName) + postConfig(BG))
        override fun onSelectBgPreset(fileName: String) {
            controller.setCurBg(1, fileName)
            ReadBookEvents.postConfig(ReadConfigChange.BG)
        }

        // 配置变更通知 (对照 app 端 ReadBookEvents.postConfig)
        override fun onPostConfig(changes: List<ReadConfigChange>) {
            ReadBookEvents.postConfig(changes)
        }
    }

    BgTextConfigScreen(
        controller = controller,
        actions = actions,
        isImageBook = false,
        bgImageList = emptyList(),
        bgImagePreviewSlot = { _, _ -> },
    )

    // 离开时持久化 (对照 app 端 BgTextConfigViewModel.onCleared → readBookConfig.save,
    // 与 TipConfigRoute / ReadStyleRoute / PaddingConfigRoute 保持一致)
    DisposableEffect(Unit) {
        onDispose { readBookConfig.save() }
    }

    if (showUrlInput) {
        UrlInputDialog(
            onConfirm = { url ->
                showUrlInput = false
                scope.launch {
                    runCatching {
                        val bytes = withContext(IoDispatcher) {
                            OkHttpClientProviders.get().okHttpClient
                                .newCallResponseBody { url(url) }
                                .bytes()
                        }
                        val config = withContext(IoDispatcher) {
                            readBookConfig.import(bytes)
                        }
                        readBookConfig.durConfig = config
                        // 导入后立即落盘 (对照原版 onDismiss -> save, 提前到导入完成时)
                        readBookConfig.save()
                    }.onSuccess {
                        ReadBookEvents.postConfig(
                            ReadConfigChange.BG,
                            ReadConfigChange.STYLE,
                            ReadConfigChange.LOAD_CONTENT
                        )
                        Toasters.get().toast("导入成功")
                    }.onFailure {
                        Toasters.get().toast(it.stackTraceStr)
                    }
                }
            },
            onDismiss = { showUrlInput = false },
        )
    }
}

/**
 * 网络导入 URL 输入对话框 (对照 app 端 importNetConfigAlert: alert + editTextView)。
 */
@Composable
private fun UrlInputDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppAlertDialogContent(
            onDismissRequest = onDismiss,
            title = stringResource(Res.string.import_on_line),
            okButton = AlertButton(
                text = stringResource(Res.string.ok),
                enabled = url.isNotBlank(),
                onClick = { onConfirm(url.trim()) },
            ),
            cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
            modifier = Modifier.appDialogSize(),
        ) {
            AppOutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = "url",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
