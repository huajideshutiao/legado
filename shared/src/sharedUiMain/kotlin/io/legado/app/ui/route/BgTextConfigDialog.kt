package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadConfigDefaults
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.BgImageItem
import io.legado.app.ui.book.read.config.BgTextConfigActions
import io.legado.app.ui.book.read.config.BgTextConfigController
import io.legado.app.ui.book.read.config.BgTextConfigScreen
import io.legado.app.ui.book.read.config.DefaultBgImagePreviewSlot
import io.legado.app.ui.book.read.page.ReaderBackgroundImageCache
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.import_on_line
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 背景文字配置弹窗形态 (对照原版 BgTextConfigDialog: BaseBottomDialogFragment
 * 底部全宽弹层, 无标题栏)。由界面设置弹窗"背景文字"入口弹起。
 *
 * @param onConfigChanged 配置变更回调：改名/换背景/换色等改动后触发，供上层界面设置弹窗
 *        实时刷新样式列表（缩略图与名称）。原版 ReadStyleDialog 在打开本弹窗前已 dismiss，
 *        重新进入时自然读到新值；迁移版两窗叠层，需显式通知（2026-08-04 用户反馈）。
 */
@Composable
fun BgTextConfigDialogHost(
    onDismiss: () -> Unit,
    onConfigChanged: () -> Unit = {},
) {
    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            // 原版 BaseBottomDialogFragment: 窗口 MATCH_PARENT 全宽贴底, filletBackground 8dp 圆角;
            // 内容 8dp 间距由 BgTextConfigScreen 内部 padding(8.dp) 提供 (XML root padding=default)。
            Surface(
                shape = DesignTokens.shapeDefault,
                color = AppTheme.colors.bottomBackground,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BgTextConfigContent(onDismiss = onDismiss, onConfigChanged = onConfigChanged)
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
fun BgTextConfigContent(
    /** 删除当前主题成功后关闭对话框（对照原版 deleteDur 成功后 dismissAllowingStateLoss） */
    onDismiss: (() -> Unit)? = null,
    /** 配置变更通知（改名/换背景/换色/恢复预设），供上层界面设置弹窗实时刷新样式列表 */
    onConfigChanged: () -> Unit = {},
) {
    val readBookConfig = ReadBookConfigProviders.get()
    val scope = rememberCoroutineScope()
    var showUrlInput by remember { mutableStateOf(false) }
    // 原版由 RemoteAssetsUtils.getBgList() 提供内置背景列表；迁移后由平台能力注入，
    // 这样 shared UI 不依赖 Android assets，同时 Android 端不会再得到空列表。
    val bgImageList = remember {
        PlatformCapabilityProviders.getOrNull()
            ?.readerBackgroundImageNames()
            .orEmpty()
            .map { fileName ->
                BgImageItem(
                    label = fileName.substringBeforeLast('.', fileName),
                    fileName = fileName,
                )
            }
    }

    val controller = remember {
        object : BgTextConfigController {
            // 读写统一走 shareLayout 感知的 [ReadBookConfigShared.config]:
            // shareLayout=false 时 config == durConfig; shareLayout=true 时渲染读的是
            // shareConfig, 若这里仍写 durConfig, 就地修改不会同步到 shareConfig,
            // 弹窗里改背景/颜色会"不生效" (原版 bg 走 durConfig 是历史遗留不一致)。
            override var name: String
                get() = readBookConfig.config.name
                set(value) {
                    readBookConfig.config.name = value
                    // 改名即落盘 (对照原版 BgTextConfigDialog.onDismiss -> ReadBookConfig.save,
                    // 这里提前到确认时, 防进程被杀丢配置)
                    readBookConfig.save()
                    // 样式列表名称实时刷新 (迁移版叠窗形态, 见 BgTextConfigDialogHost)
                    onConfigChanged()
                }

            override fun darkStatusIcon(): Boolean =
                readBookConfig.config.curStatusIconDark()

            override fun setCurStatusIconDark(value: Boolean) {
                readBookConfig.config.setCurStatusIconDark(value)
                readBookConfig.save()
            }

            override fun underline(): Boolean = readBookConfig.config.underline

            override fun setUnderline(value: Boolean) {
                readBookConfig.config.underline = value
                readBookConfig.save()
            }

            override fun bgAlpha(): Int = readBookConfig.bgAlpha

            override fun setBgAlpha(value: Int) {
                // 滑条连续回调, 不逐帧落盘; 关闭时经 onDispose save 统一持久化
                readBookConfig.bgAlpha = value
            }

            override fun curTextColor(): Int = readBookConfig.config.curTextColor()

            override fun curBgType(): Int = readBookConfig.config.curBgType()

            override fun curBgStr(): String = readBookConfig.config.curBgStr()

            override fun setCurTextColor(color: Int) {
                readBookConfig.config.setCurTextColor(color)
                // 取色确认即落盘 (对照原版 BgTextConfigDialog.onDismiss -> save,
                // 这里提前到确认时, 防进程被杀丢配置)
                readBookConfig.save()
                onConfigChanged()
            }

            override fun setCurBg(type: Int, value: String) {
                readBookConfig.config.setCurBg(type, value)
                // 取色/选背景图确认即落盘 (同上)
                readBookConfig.save()
                onConfigChanged()
            }

            // 删除当前主题 (对照 app 端 ReadBookConfig.deleteDur; 删除成功后关闭次级
            // 对话框 [见 BgTextConfigScreen 删除按钮] 并通知上级界面设置弹窗实时刷新样式列表)
            override fun deleteDur(): Boolean {
                val deleted = readBookConfig.deleteDur()
                if (deleted) {
                    readBookConfig.save()
                    onConfigChanged()
                }
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
                onConfigChanged()
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
                    // 导入整包替换 durConfig → 上级界面设置弹窗样式列表实时刷新
                    onConfigChanged()
                }.onSuccess {
                    ReadBookEvents.postConfig(
                        ReadConfigChange.BG, ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT
                    )
                    Toasters.get().toast("导入成功")
                }.onFailure {
                    // 取消不当作失败上报 (对照原版 execute{}.onError{} 的 isActive 守卫)
                    if (it is CancellationException) throw it
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
                    // 取消不当作失败上报 (对照原版 execute{}.onError{} 的 isActive 守卫)
                    if (it is CancellationException) throw it
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
                    readBookConfig.config.setCurBg(2, fileName)
                }.onSuccess {
                    // 原版 setBgFromUri 完成后立即更新当前组合；这里同时落盘，
                    // 避免用户在图片选择后尚未退出详细设置时进程被回收而丢失配置。
                    readBookConfig.save()
                    ReadBookEvents.postConfig(ReadConfigChange.BG)
                }.onFailure {
                    // 取消不当作失败上报 (对照原版 execute{}.onError{} 的 isActive 守卫)
                    if (it is CancellationException) throw it
                    Toasters.get().toast(it.message ?: "设置背景图失败")
                }
            }
        }

        // 选择 assets 背景图预设 (对照 app 端 setCurBg(1, fileName) + postConfig(BG))
        override fun onSelectBgPreset(fileName: String) {
            controller.setCurBg(1, fileName)
            val source = "bg://$fileName"
            // 清除失败冷却并主动发起加载: 重选同一预设时 LaunchedEffect(source) 不会重启,
            // 若上次加载失败 (60s 冷却) 会一直卡在"不生效"状态, 这里主动重试。
            ReaderBackgroundImageCache.clearFailed(source)
            ReaderBackgroundImageCache.requestAsync(source)
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
        bgImageList = bgImageList,
        bgImagePreviewSlot = { item, onClick ->
            DefaultBgImagePreviewSlot(item, onClick)
        },
        onDismiss = onDismiss,
    )

    // 离开时持久化 (对照 app 端 BgTextConfigViewModel.onCleared → readBookConfig.save,
    // 与 TipConfigDialog / ReadStyleDialog / PaddingConfigDialog 保持一致)
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
                        // 导入整包替换 durConfig → 上级界面设置弹窗样式列表实时刷新
                        onConfigChanged()
                    }.onSuccess {
                        ReadBookEvents.postConfig(
                            ReadConfigChange.BG,
                            ReadConfigChange.STYLE,
                            ReadConfigChange.LOAD_CONTENT
                        )
                        Toasters.get().toast("导入成功")
                    }.onFailure {
                        // 取消不当作失败上报 (对照原版 execute{}.onError{} 的 isActive 守卫)
                        if (it is CancellationException) throw it
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
            AppUnderlineTextField(
                value = url,
                onValueChange = { url = it },
                label = "url",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
