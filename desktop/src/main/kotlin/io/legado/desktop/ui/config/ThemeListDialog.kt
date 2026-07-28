package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.widthIn
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.desktop.ui.component.DialogSizes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportThemeViewModelShared
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * 桌面端"主题列表" Compose Dialog (对照 app 端 [io.legado.app.ui.config.ThemeListDialog])。
 *
 * # 背景
 *
 * 对照 app 端 `ThemeListDialog : BaseComposeDialogFragment`, 桌面端无
 * Fragment/Activity, 改为纯 Compose [Dialog] (参考 [DirectLinkUploadConfigDialog] 模式)。
 * 列表结构 + 增删改查 + 剪贴板导入行为与 app 端逐项对齐, 仅做平台适配。
 *
 * # 内置主题 (桌面端独立定义, 与 [DesktopThemeStoreProvider] 默认色对齐)
 *
 * 内置 5 套 Arco 主题 (各含浅/深两版), 主色取自 Arco Design 调色板:
 * - Arcoblue (#165DFF, 默认主色) / Arcogreen (#00B42A) /
 *   Arcoorange (#FF7D00) / Arcored (#F53F3F) / Arcopurple (#722ED1)
 *
 * 浅色: bg=#FFFFFF / bbg=#F7F8FA; 深色: bg=#121212 / bbg=#1F1F1F
 * (与 [DesktopThemeStoreProvider] toggleDark 浅深色派生色一致)
 *
 * # 自定义主题 (经 [ThemeConfigProviders] 持久化到 themeConfig.json)
 *
 * - 增删/应用/导入统一走 [ThemeConfigProviders] (对照 app 端 ThemeConfig.configList);
 * - [ThemeCustomizeDialog] 新建/编辑仍读写 prefs `desktop.theme.customList`,
 *   本 Dialog 在打开前播种 (provider 列表 → prefs)、关闭后回收 (prefs → provider) 桥接;
 * - 点击应用 / 长按编辑 / 分享 / 删除 与 app 端行为对齐。
 *
 * # 平台适配 (与 app 端差异)
 *
 * - **持久化**: app 端 `ThemeConfig.configList` (走 themeConfig.json) → 桌面端同格式,
 *   经 [ThemeConfigProviders] (FileThemeConfigProvider) 读写
 * - **应用主题**: app 端 `ThemeConfig.applyBuiltin/applyConfig + postEvent(RECREATE)` →
 *   桌面端 [ThemeConfigProviders.get().applyConfig] 落盘 + [applyThemeToStore] 即时刷新
 *   (桌面内置项自带完整三色, applyConfig 即覆盖 applyBuiltin 的净效果)
 * - **剪贴板**: app 端 `getClipText/share` (Android ClipboardManager) → 桌面端
 *   [Toolkit.getDefaultToolkit].systemClipboard (AWT Clipboard)
 * - **结果弹窗**: app 端 `alert { ... }` DSL → 桌面端 [AppAlertDialog]
 * - **Toast**: app 端 `toastOnUi` → 桌面端 [Toasters.get].toast
 * - **文案**: app 端 `R.string.xxx` → 桌面端 [rememberString]`("xxx")`
 * - **内置主题**: app 端 `ThemeConfig.getBuiltinConfigs` (读 assets JSON) → 桌面端
 *   [BuiltinThemes] 常量 (代码内定义, 不读 assets)
 * - **URL 导入** (P2-3 任务4, 桌面端补充): app 端无主题 URL 导入, 桌面端新增第三个 IconButton
 *   (ic_download_line) → AlertDialog 输入 URL → [ImportThemeViewModelShared.importSource]
 *   下载+解析+比对 → 读 vm.allSources 逐条 [ThemeConfigProviders] addConfig (同名覆盖, 不弹勾选)
 *
 * # UI 结构 (对照 app 端, padding 值原样保留)
 *
 * - [DialogTitleBar] (标题"主题列表" + 返回 + 3 个 IconButton: 新建 ic_add /
 *   剪贴板导入 ic_import / URL 导入 ic_download_line)
 * - [LazyColumn] (padding horizontal=16.dp): 主题项 (主题名 + 可选 分享/删除 IconButton)
 *   - 点击应用 / 长按编辑 (仅自定义)
 *
 * @param onDismiss 关闭回调
 */
@Composable
fun ThemeListDialog(onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val pref = LocalPreferenceStoreProvider.current
    // themeStore / eventBus: 在 @Composable 上下文取一次, 供 onClick lambda 内调 applyThemeToStore
    val (themeStore, eventBus) = rememberThemeStoreAndEventBus()

    // 文案
    val titleText = rememberString("theme_list")
    val addLabel = rememberString("add")
    val shareLabel = rememberString("share")
    val deleteLabel = rememberString("delete")
    val sureDelLabel = rememberString("sure_del")
    // URL 导入对话框文案 (app 端无主题 URL 导入, 桌面端补充;
    //   rememberString 对未注册 key 返回 key 本身, 故用中文字面量保持可读, 与 importFromClip 一致)
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val wrongFormatLabel = rememberString("wrong_format")

    // 数据版本号: 增删改后自增触发重组重取列表 (对照 app 端 dataVersion)
    var dataVersion by remember { mutableIntStateOf(0) }

    // 嵌套对话框状态: 编辑自定义主题 (configIndex) / 删除确认 (deleteIndex)
    var editConfigIndex by remember { mutableStateOf(-1) }
    var deleteIndex by remember { mutableStateOf(-1) }

    // ---- URL 导入状态 (P2-3 任务4: 主题补 URL 导入) ----
    // app 端 ThemeListDialog 仅支持剪贴板导入, 桌面端补充 URL 导入:
    //   1. URL 输入 AlertDialog (showUrlImportDialog=true 显示)
    //   2. 用户确认后新建 ImportThemeViewModelShared.importSource(url) 触发下载+解析+比对
    //   3. 收集 successState: 成功后读 vm.allSources 逐条 ThemeConfigProviders.addConfig
    //      (同名覆盖, 与 app 端 addConfig 去重逻辑一致; 主题通常单条, 不弹勾选简化处理)
    val scope = rememberCoroutineScope()
    var showUrlImportDialog by remember { mutableStateOf(false) }
    var urlImportText by remember { mutableStateOf("") }
    // URL 导入 VM (null=无导入任务; 非 null=正在下载/解析, LaunchedEffect 收集其状态流)
    var urlImportVm by remember { mutableStateOf<ImportThemeViewModelShared?>(null) }

    // 读 dataVersion 让增删改能触发重组重取 (对照 app 端 Content() 中的 dataVersion 引用)
    @Suppress("UNUSED_EXPRESSION")
    dataVersion

    // 列表 = 内置主题 + 自定义主题 (对照 app 端 builtins + ThemeConfig.configList)
    val items = remember(dataVersion) {
        BuiltinThemes + ThemeConfigProviders.get().getConfigList()
    }
    val builtinCount = BuiltinThemes.size

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.widthIn(max = DialogSizes.dialogMaxWidth()).heightIn(max = DialogSizes.dialogFullHeight()),
        ) {
            Column(Modifier.fillMaxSize()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                    actions = {
                        // 新建主题 (对照 app 端 alertNewTheme); 先播种 prefs 供 ThemeCustomizeDialog 读写
                        IconButton(onClick = {
                            seedCustomListForEdit(pref)
                            editConfigIndex = -2 // -2 表示 newConfig, -1 表示无, >=0 表示 editConfig
                        }) {
                            Icon(
                                painter = rememberPainter("ic_add"),
                                contentDescription = addLabel,
                                tint = colors.primaryText,
                            )
                        }
                        // 剪贴板导入 (对照 app 端 importFromClip)
                        IconButton(onClick = { importFromClip { dataVersion++ } }) {
                            Icon(
                                painter = rememberPainter("ic_import"),
                                contentDescription = addLabel,
                                tint = colors.primaryText,
                            )
                        }
                        // URL 导入 (app 端无此功能, 桌面端补充; 用 ic_download_line 图标表示网络下载)
                        IconButton(onClick = {
                            urlImportText = ""
                            showUrlImportDialog = true
                        }) {
                            Icon(
                                painter = rememberPainter("ic_download_line"),
                                contentDescription = rememberString("url_import"),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    itemsIndexed(items) { position, item ->
                        // configIndex: 自定义主题在 customList 中的索引 (position - builtinCount)
                        // 在 lambda 顶部声明局部 val, 供 ThemeItem 参数 + onLongClick/onShare/onDelete 复用
                        val configIndex = position - builtinCount
                        ThemeItem(
                            item = item,
                            configIndex = configIndex,
                            onClick = {
                                // 应用主题 (对照 app 端 applyBuiltin/applyConfig):
                                // provider 落盘 (PreferKey 色值 + themeMode + ThemeStore 键),
                                // applyThemeToStore 即时刷新 mutableStateOf 主题态
                                ThemeConfigProviders.get().applyConfig(item)
                                applyThemeToStore(themeStore, eventBus, item)
                                onDismiss()
                            },
                            onLongClick = {
                                // 长按编辑 (仅自定义, 对照 app 端 onLongClick);
                                // 先播种 prefs 供 ThemeCustomizeDialog 按索引读写
                                if (!item.isBuiltin &&
                                    configIndex in ThemeConfigProviders.get().getConfigList().indices
                                ) {
                                    seedCustomListForEdit(pref)
                                    editConfigIndex = configIndex
                                }
                            },
                            onShare = { share(configIndex) },
                            onDelete = { deleteIndex = configIndex },
                        )
                    }
                }
            }
        }
    }

    // 新建/编辑自定义主题对话框 (对照 app 端 alertNewTheme / ThemeCustomizeDialog.editConfig.show)
    // 关闭后把播种的 prefs 列表 (含新建/改名/替换结果) 全量回收进 provider 并清掉 prefs 键
    if (editConfigIndex == -2) {
        ThemeCustomizeDialog(
            mode = ModeNewConfig,
            isNight = false,
            onDismiss = {
                syncCustomListFromPrefs(pref)
                editConfigIndex = -1
                dataVersion++ // 新建可能产生变更, 触发列表刷新
            },
        )
    } else if (editConfigIndex >= 0) {
        ThemeCustomizeDialog(
            mode = ModeEditConfig,
            configIndex = editConfigIndex,
            onDismiss = {
                syncCustomListFromPrefs(pref)
                editConfigIndex = -1
                dataVersion++ // 编辑可能产生变更, 触发列表刷新
            },
        )
    }

    // 删除确认对话框 (对照 app 端 delete(index) 的 alert)
    if (deleteIndex >= 0) {
        AppAlertDialog(
            onDismissRequest = { deleteIndex = -1 },
            title = deleteLabel,
            message = sureDelLabel,
            okButton = AlertButton(text = rememberString("ok")) {
                deleteCustom(deleteIndex)
                deleteIndex = -1
                dataVersion++
            },
            cancelButton = AlertButton(text = rememberString("cancel")),
        )
    }

    // ---- URL 导入对话框 (输入订阅 URL, 确认后触发 ImportThemeViewModelShared.importSource) ----
    // app 端无主题 URL 导入, 桌面端补充; 对话框模式与 BookSourceScreen/ReplaceRuleScreen
    //   网络导入 URL 输入一致 (替换原 javax.swing.JOptionPane.showInputDialog 同步阻塞)
    if (showUrlImportDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { showUrlImportDialog = false },
            title = rememberString("net_import_theme"),
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                val url = urlImportText
                showUrlImportDialog = false
                if (url.isNotBlank()) {
                    // 新建 ImportThemeViewModelShared, 调 importSource(url) 触发下载+解析+比对,
                    // LaunchedEffect(urlImportVm) 收集 successState/errorState 后写入 ThemeConfigProviders
                    val vm = ImportThemeViewModelShared(scope)
                    urlImportVm = vm
                    vm.importSource(url)
                }
            },
            cancelButton = AlertButton(cancelLabel),
        ) {
            AppTextField(
                value = urlImportText,
                onValueChange = { urlImportText = it },
                label = rememberString("input_theme_url"),
                singleLine = true,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }

    // ---- URL 导入 VM 状态收集 (successState/errorState) ----
    // 成功后读 vm.allSources 逐条 ThemeConfigProviders.addConfig (同名覆盖 + 落盘,
    // 与 app 端 ThemeConfig.addConfig 一致; 主题通常单条, 不弹 DesktopImportDialog 勾选)
    urlImportVm?.let { vm ->
        LaunchedEffect(vm) {
            launch {
                vm.errorState.collect { err ->
                    if (err != null) {
                        // 清理 "ImportError:" 前缀 (与 DesktopImportDialog 一致), toast 提示
                        Toasters.get().toast(err.removePrefix("ImportError:"))
                        urlImportVm = null
                    }
                }
            }
            launch {
                vm.successState.collect { size ->
                    if (size != null) {
                        if (size > 0) {
                            // 解析成功, 逐条写入 provider (addConfig 内部按 themeName 同名覆盖 + 落盘)
                            val provider = ThemeConfigProviders.get()
                            vm.allSources.forEach { newConfig ->
                                provider.addConfig(newConfig)
                            }
                            Toasters.get().toast(jvmGetString("import_theme_success", vm.allSources.size))
                            dataVersion++ // 触发列表刷新
                        } else {
                            // size==0 表示解析无结果, 提示格式不对
                            Toasters.get().toast(wrongFormatLabel)
                        }
                        urlImportVm = null
                    }
                }
            }
        }
    }
}

/** 单个主题项 (对照 app 端 ThemeItem) */
@Composable
private fun ThemeItem(
    item: ThemeConfigData,
    configIndex: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    val shareLabel = rememberString("share")
    val deleteLabel = rememberString("delete")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp) // arco_view_height_large, 对照 app 端
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.themeName,
            color = colors.primaryText,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (!item.isBuiltin) {
            IconButton(onClick = onShare) {
                Icon(
                    painter = rememberPainter("ic_share"),
                    contentDescription = shareLabel,
                    tint = colors.primaryText,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = rememberPainter("ic_clear_all"),
                    contentDescription = deleteLabel,
                    tint = colors.primaryText,
                )
            }
        }
    }
}

/** 从剪贴板导入主题 (对照 app 端 importFromClip: ThemeConfig.addConfig(json), 失败 toast) */
private fun importFromClip(onDataChange: () -> Unit) {
    val text = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
    if (text.isNullOrBlank()) {
        Toasters.get().toast(jvmGetString("clipboard_empty_or_invalid"))
        return
    }
    // 对照 app 端 addConfig(json): trim 控制字符 + 解析 + 4 色值校验, 任一失败 toast
    val newConfig = runCatching {
        GSON.fromJsonObject<ThemeConfigData>(text.trim { it < ' ' }).getOrThrow()
    }.getOrNull()?.takeIf { config ->
        runCatching {
            ColorUtils.parseColor(config.primaryColor)
            ColorUtils.parseColor(config.accentColor)
            ColorUtils.parseColor(config.backgroundColor)
            ColorUtils.parseColor(config.bottomBackground)
        }.isSuccess
    }
    if (newConfig == null) {
        Toasters.get().toast(jvmGetString("format_invalid_add_failed"))
        return
    }
    // addConfig 内部按 themeName 同名覆盖/追加 + 落盘 (对照 app 端 ThemeConfig.addConfig)
    ThemeConfigProviders.get().addConfig(newConfig)
    onDataChange()
}

/** 分享自定义主题到剪贴板 (对照 app 端 share: GSON.toJson(configList[index]) + share) */
private fun share(index: Int) {
    val list = ThemeConfigProviders.get().getConfigList()
    if (index !in list.indices) return
    val json = GSON.toJson(list[index])
    runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(json), null)
        Toasters.get().toast(jvmGetString("copied_to_clipboard"))
    }.onFailure {
        Toasters.get().toast(it.localizedMessage ?: jvmGetString("copy_failed"))
    }
}

/** 删除自定义主题 (对照 app 端 ThemeConfig.delConfig: removeAt + save) */
private fun deleteCustom(index: Int) {
    ThemeConfigProviders.get().delConfig(index)
}

/**
 * 打开 ThemeCustomizeDialog (新建/编辑) 前, 把 provider 列表播种到 prefs
 * `desktop.theme.customList` —— 该 Dialog (另行迁移) 仍按索引读写 prefs 列表。
 */
private fun seedCustomListForEdit(pref: PreferenceStoreProvider) {
    pref.putString(
        ThemePrefKeys.CUSTOM_LIST,
        GSON.toJson(ThemeConfigProviders.get().getConfigList()),
    )
}

/**
 * ThemeCustomizeDialog 关闭后, 用播种+编辑后的 prefs 列表全量替换 provider 列表
 * (支持改名/替换/新建), 再清掉 prefs 键, 保持 provider 为唯一持久数据源。
 */
private fun syncCustomListFromPrefs(pref: PreferenceStoreProvider) {
    val json = pref.getString(ThemePrefKeys.CUSTOM_LIST) ?: return
    val list = runCatching {
        GSON.fromJsonArray<ThemeConfigData>(json).getOrThrow()
    }.getOrNull() ?: return
    val provider = ThemeConfigProviders.get()
    repeat(provider.getConfigList().size) { provider.delConfig(0) }
    list.forEach { provider.addConfig(it) }
    pref.putString(ThemePrefKeys.CUSTOM_LIST, null)
}

// ---- 内置主题 (Arco Design 调色板) ----

/**
 * 桌面端内置主题列表 (对照 app 端 `ThemeConfig.getBuiltinConfigs`).
 *
 * 5 套 Arco 主题色 × 浅/深两版 = 10 项。主色取自 Arco Design 调色板:
 * - Arcoblue-6 (#165DFF): Arco 默认主色, 与 [DesktopThemeStoreProvider] 初始 accentColor 一致
 * - Arcogreen-6 (#00B42A) / Arcoorange-6 (#FF7D00) /
 *   Arcored-6 (#F53F3F) / Arcopurple-6 (#722ED1)
 *
 * 浅色: bg=#FFFFFF / bbg=#F7F8FA (与 [DesktopThemeStoreProvider] 浅色派生色一致)
 * 深色: bg=#121212 / bbg=#1F1F1F (与 [DesktopThemeStoreProvider] 深色派生色一致)
 */
private val BuiltinThemes: List<ThemeConfigData> = listOf(
    builtin(jvmGetString("theme_arcoblue_light"), isNight = false, accent = "#FF165DFF"),
    builtin(jvmGetString("theme_arcoblue_dark"), isNight = true, accent = "#FF165DFF"),
    builtin(jvmGetString("theme_arcogreen_light"), isNight = false, accent = "#FF00B42A"),
    builtin(jvmGetString("theme_arcogreen_dark"), isNight = true, accent = "#FF00B42A"),
    builtin(jvmGetString("theme_arcoorange_light"), isNight = false, accent = "#FFFF7D00"),
    builtin(jvmGetString("theme_arcoorange_dark"), isNight = true, accent = "#FFFF7D00"),
    builtin(jvmGetString("theme_arcored_light"), isNight = false, accent = "#FFF53F3F"),
    builtin(jvmGetString("theme_arcored_dark"), isNight = true, accent = "#FFF53F3F"),
    builtin(jvmGetString("theme_arcopurple_light"), isNight = false, accent = "#FF722ED1"),
    builtin(jvmGetString("theme_arcopurple_dark"), isNight = true, accent = "#FF722ED1"),
)

/** 构造内置主题 [ThemeConfigData], isBuiltin=true, primaryColor=backgroundColor */
private fun builtin(name: String, isNight: Boolean, accent: String): ThemeConfigData {
    val bg = if (isNight) "#FF121212" else "#FFFFFFFF"
    val bbg = if (isNight) "#FF1F1F1F" else "#FFF7F8FA"
    return ThemeConfigData(
        themeName = name,
        isNightTheme = isNight,
        primaryColor = bg,
        accentColor = accent,
        backgroundColor = bg,
        bottomBackground = bbg,
    ).also { it.isBuiltin = true }
}
