package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowOption
import io.legado.app.ui.compose.component.AppChipRowTitle
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.ic_cfg_other
import legado.ui.generated.resources.ic_refresh_black_24dp
import legado.ui.generated.resources.manga_extension
import legado.ui.generated.resources.manga_extension_available
import legado.ui.generated.resources.manga_extension_empty
import legado.ui.generated.resources.manga_extension_install
import legado.ui.generated.resources.manga_extension_kind_video
import legado.ui.generated.resources.manga_extension_languages
import legado.ui.generated.resources.manga_extension_pref_empty
import legado.ui.generated.resources.manga_extension_pref_error
import legado.ui.generated.resources.manga_extension_repos
import legado.ui.generated.resources.manga_extension_setting
import legado.ui.generated.resources.manga_extension_trust
import legado.ui.generated.resources.manga_extension_uninstall
import legado.ui.generated.resources.manga_extension_uninstall_confirm
import legado.ui.generated.resources.manga_extension_untrusted
import legado.ui.generated.resources.manga_extension_untrusted_desc
import legado.ui.generated.resources.manga_extension_update
import legado.ui.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 漫画插件内容分级角标红 (Arco danger, 同 AppTextField 错误色约定)。 */
private val NsfwBadgeColor = Color(0xFFF53F3F)

/**
 * 漫画插件管理页 (shared, app + 桌面共用; 服务未注册端入口隐藏不会进入)。
 *
 * 语言筛选条常驻标题栏下方 (仅多语言时展示)。列表分区自上而下: 未信任 (确认后
 * 信任) → 未装载原因 (直接展示) → 可更新 → 已装 (按语言分组) → 可用。安装/更新
 * 进度经 state.installSteps 呈现, 进行中点动作钮为取消。仓库入口 (六边形设置图标)
 * 收进标题栏。
 */
@Composable
fun MangaExtensionScreen(
    state: MangaExtensionUiState,
    onBack: () -> Unit,
    onManageRepos: () -> Unit,
    onInstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onCancelInstall: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onTrust: (String) -> Unit,
    onRefresh: () -> Unit,
    selectedLanguages: Set<String>,
    onToggleLanguage: (String) -> Unit,
    onClearLanguages: () -> Unit,
    /** 插件自带配置对话框状态 (null=未打开); 平台未实现配置契约时恒 null。 */
    prefDialog: MangaPrefDialogState? = null,
    onOpenPrefDialog: (String) -> Unit = {},
    onSetPreference: (String, MangaPrefValue) -> Unit = { _, _ -> },
    onDismissPrefDialog: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val titleMangaExtension = stringResource(Res.string.manga_extension)
    val titleRepos = stringResource(Res.string.manga_extension_repos)
    val emptyText = stringResource(Res.string.manga_extension_empty)
    val trustText = stringResource(Res.string.manga_extension_trust)
    val untrustedTitle = stringResource(Res.string.manga_extension_untrusted)
    val untrustedDesc = stringResource(Res.string.manga_extension_untrusted_desc)
    val uninstallText = stringResource(Res.string.manga_extension_uninstall)
    val uninstallConfirm = stringResource(Res.string.manga_extension_uninstall_confirm)
    val cancelText = stringResource(Res.string.cancel)
    val updateText = stringResource(Res.string.manga_extension_update)
    val installText = stringResource(Res.string.manga_extension_install)
    val availableText = stringResource(Res.string.manga_extension_available)
    val languagesTitle = stringResource(Res.string.manga_extension_languages)
    val settingText = stringResource(Res.string.manga_extension_setting)

    // 语言筛选 chips (点选即生效, 「全部」即清空), 仅存在多语言时展示筛选条
    val availableLangs = remember(state.available) {
        state.available.mapTo(sortedSetOf("all")) { it.lang ?: "all" }
    }

    // 确认弹窗待操作包名 (信任/卸载共用各自弹窗)
    var pendingTrustPkg by remember { mutableStateOf<String?>(null) }
    var pendingUninstallPkg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleMangaExtension,
            onBack = onBack,
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                        contentDescription = null,
                        tint = colors.primaryText,
                    )
                }
                IconButton(onClick = onManageRepos) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_cfg_other),
                        contentDescription = titleRepos,
                        tint = colors.primaryText,
                    )
                }
            },
        )
        if (availableLangs.size > 1) {
            AppChipRow {
                AppChipRowTitle(text = languagesTitle)
                availableLangs.forEach { lang ->
                    val selected = (lang == "all" && selectedLanguages.isEmpty()) || lang in selectedLanguages
                    AppChipRowOption(
                        text = lang,
                        selected = selected,
                        onClick = {
                            if (lang == "all") onClearLanguages() else onToggleLanguage(lang)
                        },
                    )
                }
            }
        }
        val untrusted = state.notLoaded.filter { it.isUntrusted }
        val notLoadedOthers = state.notLoaded.filter { !it.isUntrusted }
        val updatable = state.installed.filter { it.hasUpdate }
        val installedPkgNames = buildSet {
            state.installed.forEach { add(it.pkgName) }
            state.notLoaded.forEach { add(it.pkgName) }
        }
        val available = state.available.filter { it.pkgName !in installedPkgNames }

        if (state.loading || state.refreshing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.installed.isEmpty() && state.notLoaded.isEmpty() &&
            state.available.isEmpty()
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, color = colors.secondaryText)
            }
        } else {
            FastScrollLazyColumn(Modifier.fillMaxSize()) {
                if (untrusted.isNotEmpty()) {
                    item(key = "sec_untrusted") { SectionHeader(untrustedTitle) }
                    items(untrusted, key = { "u_${it.pkgName}" }) { item ->
                        ExtensionRow(
                            item = item,
                            onConfigure = if (item.isConfigurable) {
                                { onOpenPrefDialog(item.pkgName) }
                            } else {
                                null
                            },
                            action = {
                                TextButton(onClick = { pendingTrustPkg = item.pkgName }) {
                                    Text(trustText, color = colors.accent)
                                }
                            },
                        )
                    }
                }
                if (notLoadedOthers.isNotEmpty()) {
                    // 无独立分区标题: 未装载原因在条目内红字展示 (不再额外增 string key)
                    items(notLoadedOthers, key = { "n_${it.pkgName}" }) { item ->
                        ExtensionRow(
                            item = item,
                            onConfigure = if (item.isConfigurable) {
                                { onOpenPrefDialog(item.pkgName) }
                            } else {
                                null
                            },
                            action = {
                                TextButton(onClick = { pendingUninstallPkg = item.pkgName }) {
                                    Text(uninstallText, color = NsfwBadgeColor)
                                }
                            },
                        )
                    }
                }
                if (updatable.isNotEmpty()) {
                    item(key = "sec_updatable") { SectionHeader(updateText) }
                    items(updatable, key = { "up_${it.pkgName}" }) { item ->
                        ExtensionRow(
                            item = item,
                            onConfigure = if (item.isConfigurable) {
                                { onOpenPrefDialog(item.pkgName) }
                            } else {
                                null
                            },
                            action = {
                                ExtensionStepAction(
                                    pkgName = item.pkgName,
                                    installSteps = state.installSteps,
                                    primaryText = updateText,
                                    onPrimary = onUpdate,
                                    onCancel = onCancelInstall,
                                )
                            },
                        )
                    }
                }
                state.installed
                    .groupBy { it.lang?.takeIf { lang -> lang.isNotBlank() } ?: "all" }
                    .forEach { (lang, list) ->
                        item(key = "sec_installed_$lang") { SectionHeader(lang) }
                        items(list, key = { "i_${it.pkgName}" }) { item ->
                            ExtensionRow(
                                item = item,
                                onConfigure = if (item.isConfigurable) {
                                    { onOpenPrefDialog(item.pkgName) }
                                } else {
                                    null
                                },
                                action = {
                                    TextButton(onClick = { pendingUninstallPkg = item.pkgName }) {
                                        Text(uninstallText, color = NsfwBadgeColor)
                                    }
                                },
                            )
                        }
                    }
                if (available.isNotEmpty()) {
                    item(key = "sec_available") { SectionHeader(availableText) }
                    items(available, key = { "a_${it.pkgName}" }) { item ->
                        ExtensionRow(
                            item = item,
                            onConfigure = null,
                            action = {
                                ExtensionStepAction(
                                    pkgName = item.pkgName,
                                    installSteps = state.installSteps,
                                    primaryText = installText,
                                    onPrimary = onInstall,
                                    onCancel = onCancelInstall,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    // 未信任确认弹窗 (确认后 trust, 装载流程由插件宿主重扫完成)
    pendingTrustPkg?.let { pkgName ->
        AppAlertDialog(
            onDismissRequest = { pendingTrustPkg = null },
            title = untrustedTitle,
            message = untrustedDesc,
            okButton = AlertButton(text = trustText) {
                onTrust(pkgName)
            },
            cancelButton = AlertButton(text = cancelText) {
                pendingTrustPkg = null
            },
        )
    }
    // 卸载确认弹窗 (已装/未装载/可更新共用)
    pendingUninstallPkg?.let { pkgName ->
        AppAlertDialog(
            onDismissRequest = { pendingUninstallPkg = null },
            title = uninstallText,
            message = uninstallConfirm,
            okButton = AlertButton(text = uninstallText) {
                onUninstall(pkgName)
            },
            cancelButton = AlertButton(text = cancelText) {
                pendingUninstallPkg = null
            },
        )
    }
    // 插件自带配置弹窗 (仅 ConfigurableSource 条目可达)
    prefDialog?.let { dialog ->
        ExtensionPrefDialog(
            dialog = dialog,
            settingText = settingText,
            onSetPreference = onSetPreference,
            onDismiss = onDismissPrefDialog,
        )
    }
}

/** 分区标题 (对照书源管理分组小标题样式)。 */
@Composable
private fun SectionHeader(title: String) {
    val colors = AppTheme.colors
    Text(
        text = title,
        fontSize = 13.sp,
        color = colors.secondaryText,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.fillet)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** 单插件条目: 图标 + 名称 + 视频/NSFW 角标 + 版本/源数/原因, 尾部设置与动作槽。 */
@Composable
private fun ExtensionRow(
    item: MangaExtensionItem,
    action: @Composable () -> Unit,
    /** 非空时展示「设置」入口 (仅 isConfigurable 条目传入)。 */
    onConfigure: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionIcon(item.iconUrl)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    fontSize = 15.sp,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.kind == MangaExtensionKind.VIDEO) {
                    Spacer(Modifier.width(6.dp))
                    VideoBadge()
                }
                if (item.contentWarning != MangaContentWarning.SAFE) {
                    Spacer(Modifier.width(6.dp))
                    NsfwBadge()
                }
            }
            Text(
                text = "v${item.versionName}",
                fontSize = 12.sp,
                color = colors.secondaryText,
            )
            item.notLoadedReason?.let { reason ->
                Text(
                    text = reason,
                    fontSize = 12.sp,
                    color = NsfwBadgeColor,
                )
            }
        }
        if (onConfigure != null) {
            TextButton(onClick = onConfigure) {
                Text(text = stringResource(Res.string.manga_extension_setting), color = colors.accent)
            }
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

/**
 * 插件图标 (仓库索引 iconUrl 远程图)。
 * 走 [BookImageLoaders] (与书架封面/简介图同一加载面); 槽位恒占 40dp (fillet 底) 避免
 * 图片到达时行内文字跳位, 加载中/失败/未注册端就留底色空块。
 */
@Composable
private fun ExtensionIcon(url: String?) {
    if (url.isNullOrBlank()) return
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, loader) {
        if (loader == null) return@LaunchedEffect
        bitmap = loader.loadImageOrNull(url, null, 0, 0)
    }
    Box(
        Modifier
            .size(40.dp)
            .background(AppTheme.colors.fillet, RoundedCornerShape(DesignTokens.radiusSm)),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 视频类型角标「视频」(accent 字色; 漫画为默认类型不展示角标)。 */
@Composable
private fun VideoBadge() {
    val colors = AppTheme.colors
    Box(
        Modifier
            .background(colors.fillet, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = stringResource(Res.string.manga_extension_kind_video),
            color = colors.accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** NSFW 角标 (内容分级 MIXED/NSFW 均展示, 与插件宿主 ContentWarning 对齐)。 */
@Composable
private fun NsfwBadge() {
    Box(
        Modifier
            .background(NsfwBadgeColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = "NSFW",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 安装/更新动作钮: 无进行中步骤时展示主动作; 进行中 (下载/安装) 点按为取消。
 * INSTALLED 短暂态不出动作 (管理器流刷新后条目归位到已装分区)。
 */
@Composable
private fun ExtensionStepAction(
    pkgName: String,
    installSteps: Map<String, MangaInstallState>,
    primaryText: String,
    onPrimary: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val colors = AppTheme.colors
    when (installSteps[pkgName]) {
        MangaInstallState.PENDING, MangaInstallState.DOWNLOADING, MangaInstallState.INSTALLING ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onCancel(pkgName) }) {
                    Text(text = "✕", color = colors.secondaryText)
                }
            }

        MangaInstallState.INSTALLED, MangaInstallState.ERROR, null ->
            TextButton(onClick = { onPrimary(pkgName) }) {
                Text(text = primaryText, color = colors.accent)
            }
    }
}

/**
 * 插件自带配置弹窗: 渲染平台侧从 shim `PreferenceScreen` 降级来的 [MangaPrefItem] 列表。
 *
 * 配置行整行可点: 开关行点击即切换, 列表行弹单选/复选, 文本行弹二级输入框 (与仓库
 * 页添加仓库同款)。所有写入经 [onSetPreference] 回平台落插件自身 SharedPreferences。
 */
@Composable
private fun ExtensionPrefDialog(
    dialog: MangaPrefDialogState,
    settingText: String,
    onSetPreference: (String, MangaPrefValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val emptyText = stringResource(Res.string.manga_extension_pref_empty)
    val errorText = stringResource(Res.string.manga_extension_pref_error)
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)

    // EditText 二级输入框待写项 (key + 初始文本)
    var editing by remember(dialog.pkgName) { mutableStateOf<Pair<String, String>?>(null) }
    // MultiSelectListPreference 二级复选待写项
    var multiSelect by remember(dialog.pkgName) { mutableStateOf<MangaPrefItem?>(null) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = settingText,
        okButton = AlertButton(text = okText, onClick = onDismiss),
    ) {
        when {
            dialog.loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(DesignTokens.spacingXl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            dialog.failed -> Text(
                text = errorText,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(DesignTokens.spacingLg),
            )

            dialog.items.isEmpty() -> Text(
                text = emptyText,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(DesignTokens.spacingLg),
            )

            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                dialog.items.forEach { item ->
                    PrefItemRow(
                        item = item,
                        onToggleFlag = { checked ->
                            item.key?.let { onSetPreference(it, MangaPrefValue.Flag(checked)) }
                        },
                        onPickChoice = { value ->
                            item.key?.let { onSetPreference(it, MangaPrefValue.Choice(value)) }
                        },
                        onEditText = { current ->
                            item.key?.let { editing = it to current }
                        },
                        onEditMulti = { multiSelect = item },
                    )
                }
            }
        }
    }

    editing?.let { (key, initial) ->
        var text by remember(key) { mutableStateOf(initial) }
        AppAlertDialog(
            onDismissRequest = { editing = null },
            okButton = AlertButton(text = okText) {
                onSetPreference(key, MangaPrefValue.Text(text))
            },
            cancelButton = AlertButton(text = cancelText) { editing = null },
        ) {
            AppTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    multiSelect?.let { item ->
        val current = (item.value as? MangaPrefValue.MultiChoice)?.values.orEmpty()
        var picked by remember(item.key) { mutableStateOf(current) }
        AppAlertDialog(
            onDismissRequest = { multiSelect = null },
            title = item.title,
            okButton = AlertButton(text = okText) {
                item.key?.let { onSetPreference(it, MangaPrefValue.MultiChoice(picked)) }
                multiSelect = null
            },
            cancelButton = AlertButton(text = cancelText) { multiSelect = null },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                item.entries.forEachIndexed { index, entry ->
                    val value = item.entryValues.getOrNull(index) ?: entry
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                picked = if (value in picked) picked - value else picked + value
                            }
                            .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(
                            checked = value in picked,
                            onCheckedChange = { checked ->
                                picked = if (checked) picked + value else picked - value
                            },
                        )
                        Spacer(Modifier.width(DesignTokens.spacingMd))
                        Text(entry, color = colors.primaryText, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 配置项单行: 开关行标题/摘要居左、开关居右; 其余类型标题/摘要在上、当前值或控件在下
 * (长摘要与长值左右排会互相挤压)。整行可点, 点击按 [MangaPrefItem.value] 类型分派
 * 编辑/选择, 开关行点击即切换、也可直接拨动。
 */
@Composable
private fun PrefItemRow(
    item: MangaPrefItem,
    onToggleFlag: (Boolean) -> Unit,
    onPickChoice: (String) -> Unit,
    onEditText: (String) -> Unit,
    onEditMulti: () -> Unit,
) {
    val colors = AppTheme.colors
    var choiceExpanded by remember(item.key) { mutableStateOf(false) }
    val enabled = item.writable
    val summary = item.displaySummary()

    // 标题 + 摘要 (开关行居左, 其余行居上, 同一段渲染)
    val header: @Composable ColumnScope.() -> Unit = {
        Text(
            text = item.title,
            color = colors.primaryText,
            fontSize = 15.sp,
        )
        summary?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, color = colors.secondaryText, fontSize = 12.sp)
        }
    }

    val flag = item.value as? MangaPrefValue.Flag
    if (flag != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onToggleFlag(!flag.value) }
                .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), content = header)
            Spacer(Modifier.width(DesignTokens.spacingMd))
            AppSwitch(
                checked = flag.value,
                onCheckedChange = onToggleFlag,
                enabled = enabled,
            )
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                when (val v = item.value) {
                    is MangaPrefValue.Choice -> choiceExpanded = true
                    is MangaPrefValue.MultiChoice -> onEditMulti()
                    is MangaPrefValue.Text -> onEditText(v.value)
                    else -> {}
                }
            }
            .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
    ) {
        header()
        when (val value = item.value) {
            is MangaPrefValue.Choice -> {
                Text(
                    text = item.selectedEntryText() ?: value.value,
                    color = if (enabled) colors.accent else colors.textDisabled,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = DesignTokens.spacingXs),
                )
                if (choiceExpanded) {
                    PrefChoiceDialog(
                        item = item,
                        onDismiss = { choiceExpanded = false },
                        onPick = { picked ->
                            onPickChoice(picked)
                            choiceExpanded = false
                        },
                    )
                }
            }

            is MangaPrefValue.MultiChoice -> Text(
                text = (item.selectedEntryText() ?: "").ifBlank { "—" },
                color = if (enabled) colors.accent else colors.textDisabled,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = DesignTokens.spacingXs),
            )

            is MangaPrefValue.Text -> Text(
                text = value.value.ifBlank { "—" },
                color = if (enabled) colors.accent else colors.textDisabled,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = DesignTokens.spacingXs),
            )
        }
    }
}

/** ListPreference 单选弹窗 (radio 列表)。 */
@Composable
private fun PrefChoiceDialog(
    item: MangaPrefItem,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val selected = (item.value as? MangaPrefValue.Choice)?.value
    AppAlertDialog(onDismissRequest = onDismiss, title = item.title) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            item.entries.forEachIndexed { index, entry ->
                val value = item.entryValues.getOrNull(index) ?: entry
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(value) }
                        .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppRadioButton(selected = value == selected, onClick = null)
                    Spacer(Modifier.width(DesignTokens.spacingMd))
                    Text(entry, color = colors.primaryText, fontSize = 15.sp)
                }
            }
        }
    }
}
