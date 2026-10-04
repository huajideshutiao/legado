package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.ic_refresh_black_24dp
import legado.ui.generated.resources.manga_extension
import legado.ui.generated.resources.manga_extension_empty
import legado.ui.generated.resources.manga_extension_install
import legado.ui.generated.resources.manga_extension_languages
import legado.ui.generated.resources.manga_extension_repos
import legado.ui.generated.resources.manga_extension_trust
import legado.ui.generated.resources.manga_extension_uninstall
import legado.ui.generated.resources.manga_extension_uninstall_confirm
import legado.ui.generated.resources.manga_extension_untrusted
import legado.ui.generated.resources.manga_extension_untrusted_desc
import legado.ui.generated.resources.manga_extension_update
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 漫画插件内容分级角标红 (Arco danger, 同 AppTextField 错误色约定)。 */
private val NsfwBadgeColor = Color(0xFFF53F3F)

/**
 * 漫画插件管理页 (shared, app + 桌面共用; 服务未注册端入口隐藏不会进入)。
 *
 * 分区自上而下: 仓库管理入口 → 未信任 (确认后信任) → 未装载原因 (直接展示) →
 * 可更新 → 已装 (按语言分组) → 可用。安装/更新进度经 state.installSteps 呈现,
 * 进行中点动作钮为取消。
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
    val languagesTitle = stringResource(Res.string.manga_extension_languages)

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
                        painterResource(Res.drawable.ic_refresh_black_24dp),
                        contentDescription = null,
                    )
                }
            },
        )
        val availableLangs = remember(state.available) {
            state.available.mapTo(sortedSetOf("all")) { it.lang ?: "all" }
        }
        if (availableLangs.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppFilletTextButton(
                    text = languagesTitle,
                    bold = true,
                )
                Spacer(Modifier.width(8.dp))
                availableLangs.forEach { lang ->
                    val selected = (lang == "all" && selectedLanguages.isEmpty()) || lang in selectedLanguages
                    AppFilletTextButton(
                        text = if (selected) "✓ $lang" else lang,
                        bold = selected,
                        onClick = {
                            if (lang == "all") onClearLanguages() else onToggleLanguage(lang)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
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

        if (state.loading) {
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
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "repos_entry") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageRepos)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = titleRepos,
                            fontSize = 16.sp,
                            color = colors.primaryText,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = state.repos.size.toString(),
                            fontSize = 13.sp,
                            color = colors.secondaryText,
                        )
                    }
                }
                if (untrusted.isNotEmpty()) {
                    item(key = "sec_untrusted") { SectionHeader(untrustedTitle) }
                    items(untrusted, key = { "u_${it.pkgName}" }) { item ->
                        ExtensionRow(
                            item = item,
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
                                action = {
                                    TextButton(onClick = { pendingUninstallPkg = item.pkgName }) {
                                        Text(uninstallText, color = NsfwBadgeColor)
                                    }
                                },
                            )
                        }
                    }
                if (available.isNotEmpty()) {
                    item(key = "sec_available") { SectionHeader(installText) }
                    items(available, key = { "a_${it.pkgName}" }) { item ->
                        ExtensionRow(
                            item = item,
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
            message = uninstallConfirm,
            okButton = AlertButton(text = uninstallText) {
                onUninstall(pkgName)
            },
            cancelButton = AlertButton(text = cancelText) {
                pendingUninstallPkg = null
            },
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

/** 单插件条目: 名称 + NSFW 角标 + 版本/源数/原因, 尾部动作槽。 */
@Composable
private fun ExtensionRow(
    item: MangaExtensionItem,
    action: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Spacer(Modifier.width(8.dp))
        action()
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
