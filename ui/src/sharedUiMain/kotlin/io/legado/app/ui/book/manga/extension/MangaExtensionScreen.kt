package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowOption
import io.legado.app.ui.compose.component.AppChipRowTitle
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.all
import legado.ui.generated.resources.browser
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.content
import legado.ui.generated.resources.ic_baseline_close
import legado.ui.generated.resources.ic_clear_all
import legado.ui.generated.resources.ic_download
import legado.ui.generated.resources.ic_other_setting
import legado.ui.generated.resources.ic_refresh_black_24dp
import legado.ui.generated.resources.ic_search
import legado.ui.generated.resources.ic_verified_user
import legado.ui.generated.resources.ic_web_outline
import legado.ui.generated.resources.manga_extension
import legado.ui.generated.resources.manga_extension_available
import legado.ui.generated.resources.manga_extension_content_safe
import legado.ui.generated.resources.manga_extension_empty
import legado.ui.generated.resources.manga_extension_filter_kind
import legado.ui.generated.resources.manga_extension_install
import legado.ui.generated.resources.manga_extension_kind_manga
import legado.ui.generated.resources.manga_extension_kind_video
import legado.ui.generated.resources.manga_extension_languages
import legado.ui.generated.resources.manga_extension_no_result
import legado.ui.generated.resources.manga_extension_not_loaded_failed
import legado.ui.generated.resources.manga_extension_not_loaded_filtered
import legado.ui.generated.resources.manga_extension_not_loaded_malformed
import legado.ui.generated.resources.manga_extension_not_loaded_unsigned
import legado.ui.generated.resources.manga_extension_not_loaded_unsupported_lib
import legado.ui.generated.resources.manga_extension_repos
import legado.ui.generated.resources.manga_extension_setting
import legado.ui.generated.resources.manga_extension_trust
import legado.ui.generated.resources.manga_extension_uninstall
import legado.ui.generated.resources.manga_extension_uninstall_confirm
import legado.ui.generated.resources.manga_extension_untrusted
import legado.ui.generated.resources.manga_extension_untrusted_desc
import legado.ui.generated.resources.manga_extension_update
import legado.ui.generated.resources.search
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 漫画插件内容分级角标红 (Arco danger, 同 AppTextField 错误色约定)。 */
private val NsfwBadgeColor = Color(0xFFF53F3F)

/** 内容分级文案 (与角标同一份字面量; 分级名无翻译语义)。 */
private const val NsfwLabel = "NSFW"

/**
 * 漫画插件管理页 (shared, app + 桌面共用; 服务未注册端入口隐藏不会进入)。
 *
 * 标题栏搜索图标点开后标题位换成搜索框 (再点图标/系统返回关闭), 匹配扩展名、源名、
 * 源 id 与源站点 (忽略大小写, 逗号分隔多个子串按"或"匹配; 对齐 Mihon searchQueryPredicate)。
 * 筛选条紧贴标题栏下方: 语言 (仅多语言时展示) + 类型 + 内容分级, 均为单选语义 (点选只留该项,
 * 再点同项无动作, 点「全部」回到不过滤); 三路只过滤「可用」列表并持久化
 * (对齐 Mihon enabledLanguages 只作用于可用扩展)。列表分区自上而下: 未信任 (确认后
 * 信任) → 未装载原因 (直接展示) → 可更新 → 已装 (按语言分组) → 可用。条目尾部动作全部为
 * 图标 (设置/打开网站/安装/更新/卸载/信任; 打开网站仅未安装的可用条目), 安装/更新
 * 进度经 state.installSteps 呈现, 进行中点取消图标。条目标题显示**源名** (中文站点扩展名多为
 * 拉丁文, 如 Dm5 / Jinman Tiantang; 源名才是用户认得的名字, 无源信息回退扩展名),
 * 第二行只留版本。仓库入口 (六边形设置图标) 收进标题栏。
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
    /** 单选语言筛选, null=全部 (清空筛选)。 */
    onSelectLanguage: (String?) -> Unit,
    /** 类型筛选档位 (只作用「可用」列表)。 */
    onSelectKind: (MangaExtensionKindFilter) -> Unit,
    /** 内容分级筛选档位 (同 [onSelectKind])。 */
    onSelectContent: (MangaContentFilter) -> Unit,
    /** 搜索框文本 (null=搜索模式关闭); 只影响本页列表显示。 */
    searchQuery: String? = null,
    /** 防抖后实际用于过滤的查询 (上游 ViewModel 同款 250ms); 省略时按 [searchQuery] 即时过滤。 */
    searchFilterQuery: String? = searchQuery,
    onSearchQueryChange: (String?) -> Unit = {},
    /** 插件自带配置对话框状态 (null=未打开); 平台未实现配置契约时恒 null。 */
    prefDialog: MangaPrefDialogState? = null,
    onOpenPrefDialog: (String) -> Unit = {},
    onSetPreference: (String, MangaPrefValue) -> Unit = { _, _ -> },
    onPreferenceClick: (MangaPrefItem) -> Unit = {},
    onPreferenceChange: (MangaPrefItem, Boolean) -> Unit = { _, _ -> },
    onBindEditText: (MangaPrefItem) -> Unit = {},
    onDismissPrefDialog: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val titleMangaExtension = stringResource(Res.string.manga_extension)
    val titleRepos = stringResource(Res.string.manga_extension_repos)
    val emptyText = stringResource(Res.string.manga_extension_empty)
    val noResultText = stringResource(Res.string.manga_extension_no_result)
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
    val searchHint = stringResource(Res.string.search)
    val allText = stringResource(Res.string.all)
    val kindTitle = stringResource(Res.string.manga_extension_filter_kind)
    val kindMangaText = stringResource(Res.string.manga_extension_kind_manga)
    val kindVideoText = stringResource(Res.string.manga_extension_kind_video)
    val contentTitle = stringResource(Res.string.content)
    val contentSafeText = stringResource(Res.string.manga_extension_content_safe)

    // 确认弹窗待操作包名 (信任/卸载共用各自弹窗)
    var pendingTrustPkg by remember { mutableStateOf<String?>(null) }
    var pendingUninstallPkg by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    // 搜索模式的系统返回 = 退出搜索 (页面内子状态自带可见退出口)
    AppBackHandler(enabled = searchQuery != null) { onSearchQueryChange(null) }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleMangaExtension,
            onBack = onBack,
            titleContent = if (searchQuery != null) {
                val query = searchQuery
                {
                    AppSearchField(
                        value = query,
                        onValueChange = { onSearchQueryChange(it) },
                        hint = searchHint,
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                    LaunchedEffect(Unit) {
                        runCatching { focusRequester.requestFocus() }
                    }
                }
            } else {
                null
            },
            actions = {
                IconButton(
                    onClick = {
                        onSearchQueryChange(if (searchQuery != null) null else "")
                    },
                ) {
                    Icon(
                        painter = painterResource(
                            if (searchQuery != null) {
                                Res.drawable.ic_baseline_close
                            } else {
                                Res.drawable.ic_search
                            }
                        ),
                        contentDescription = searchHint,
                        tint = colors.primaryText,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                        contentDescription = null,
                        tint = colors.primaryText,
                    )
                }
                IconButton(onClick = onManageRepos) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_other_setting),
                        contentDescription = titleRepos,
                        tint = colors.primaryText,
                    )
                }
            },
        )
        // 筛选条: 语言独占一行 (仅多语言时展示); 类型与内容分级另起一行, 一律单选语义
        if (state.availableLanguages.size > 1) {
            AppChipRow {
                AppChipRowTitle(text = languagesTitle)
                state.availableLanguages.forEach { lang ->
                    val selected =
                        (lang == "all" && state.selectedLanguages.isEmpty()) || lang in state.selectedLanguages
                    AppChipRowOption(
                        text = if (lang == "all") allText else lang,
                        selected = selected,
                        onClick = {
                            when {
                                lang in state.selectedLanguages -> Unit
                                lang == "all" -> onSelectLanguage(null)
                                else -> onSelectLanguage(lang)
                            }
                        },
                    )
                }
            }
        }
        AppChipRow {
            AppChipRowTitle(text = kindTitle)
            MangaExtensionKindFilter.entries.forEach { filter ->
                AppChipRowOption(
                    text = when (filter) {
                        MangaExtensionKindFilter.ALL -> allText
                        MangaExtensionKindFilter.MANGA -> kindMangaText
                        MangaExtensionKindFilter.VIDEO -> kindVideoText
                    },
                    selected = state.kindFilter == filter,
                    onClick = { if (state.kindFilter != filter) onSelectKind(filter) },
                )
            }
            AppChipRowTitle(text = contentTitle)
            MangaContentFilter.entries.forEach { filter ->
                AppChipRowOption(
                    text = when (filter) {
                        MangaContentFilter.ALL -> allText
                        MangaContentFilter.SAFE -> contentSafeText
                        MangaContentFilter.NSFW -> NsfwLabel
                    },
                    selected = state.contentFilter == filter,
                    onClick = { if (state.contentFilter != filter) onSelectContent(filter) },
                )
            }
        }
        // 搜索匹配 (扩展名/源名/源站点/源 id); 搜索框为空时恒真 (过滤取防抖后的查询)
        val matchesQuery = remember(searchFilterQuery) { extensionSearchMatcher(searchFilterQuery.orEmpty()) }
        val untrusted = state.notLoaded.filter { it.isUntrusted && matchesQuery(it) }
        val notLoadedOthers = state.notLoaded.filter { !it.isUntrusted && matchesQuery(it) }
        val updatable = state.installed.filter { it.hasUpdate && matchesQuery(it) }
        val installed = state.installed.filter(matchesQuery)
        val installedPkgNames = buildSet {
            state.installed.forEach { add(it.pkgName) }
            state.notLoaded.forEach { add(it.pkgName) }
        }
        val available = state.available.filter { it.pkgName !in installedPkgNames && matchesQuery(it) }
        val filterActive = !searchFilterQuery.isNullOrEmpty() ||
            state.selectedLanguages.isNotEmpty() ||
            state.kindFilter != MangaExtensionKindFilter.ALL ||
            state.contentFilter != MangaContentFilter.ALL

        if (state.loading || state.refreshing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (installed.isEmpty() && notLoadedOthers.isEmpty() && untrusted.isEmpty() &&
            available.isEmpty()
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (filterActive) noResultText else emptyText,
                    color = colors.secondaryText,
                )
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
                                IconActionButton(
                                    icon = Res.drawable.ic_verified_user,
                                    contentDescription = trustText,
                                    tint = colors.accent,
                                    onClick = { pendingTrustPkg = item.pkgName },
                                )
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
                                IconActionButton(
                                    icon = Res.drawable.ic_clear_all,
                                    contentDescription = uninstallText,
                                    tint = NsfwBadgeColor,
                                    onClick = { pendingUninstallPkg = item.pkgName },
                                )
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
                installed
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
                                    IconActionButton(
                                        icon = Res.drawable.ic_clear_all,
                                        contentDescription = uninstallText,
                                        tint = NsfwBadgeColor,
                                        onClick = { pendingUninstallPkg = item.pkgName },
                                    )
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
                            // 打开网站: 索引源站点 (Mihon 同款入口, 只给未安装的可用条目)
                            websiteUrl = item.websiteUrl,
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
            onPreferenceClick = onPreferenceClick,
            onPreferenceChange = onPreferenceChange,
            onBindEditText = onBindEditText,
            onDismiss = onDismissPrefDialog,
        )
    }
}

/**
 * 搜索匹配器, 逐字对标上游 (Mihon `ExtensionsViewModel.searchQueryPredicate` /
 * Aniyomi `AnimeExtensionsScreenModel.queryFilter`): 关键词按逗号拆多个子串 (去空) 按"或"匹配,
 * 忽略大小写; 每个子串匹配面 = 扩展名 + (仅已装载/可用条目的) 源名、源站点、源 id。
 * 未装载条目无源信息 (与上游 NotLoaded 同形), 只匹配扩展名; 空查询返回恒真谓词。
 */
private fun extensionSearchMatcher(query: String): (MangaExtensionItem) -> Boolean {
    val subQueries = query.split(",")
        .map { it.trim() }
        .filterNot { it.isBlank() }
    if (subQueries.isEmpty()) return { true }
    return { item ->
        subQueries.any { subQuery ->
            item.name.contains(subQuery, ignoreCase = true) ||
                item.sources.any { source ->
                    source.name.contains(subQuery, ignoreCase = true) ||
                        source.homeUrl.contains(subQuery, ignoreCase = true) ||
                        source.id == subQuery.toLongOrNull()
                }
        }
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

/** 行尾图标动作钮 (统一点击区与单色 tint)。 */
@Composable
private fun IconActionButton(
    icon: DrawableResource,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

/** 单插件条目: 图标 + 源名 + 视频/NSFW 角标 + 版本, 尾部打开网站/设置与动作槽。 */
@Composable
private fun ExtensionRow(
    item: MangaExtensionItem,
    action: @Composable () -> Unit,
    /** 非空时展示「设置」入口 (仅 isConfigurable 条目传入)。 */
    onConfigure: (() -> Unit)? = null,
    /** 非空时展示「打开网站」入口 (仅未安装的可用条目传入)。 */
    websiteUrl: String? = null,
) {
    val colors = AppTheme.colors
    val sourceName = item.sourceName
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
                    text = sourceName ?: item.name,
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
                    text = reason.toText(item.notLoadedDetail),
                    fontSize = 12.sp,
                    color = NsfwBadgeColor,
                )
            }
        }
        websiteUrl?.let { url ->
            IconActionButton(
                icon = Res.drawable.ic_web_outline,
                contentDescription = stringResource(Res.string.browser),
                tint = colors.accent,
                onClick = {
                    // 站点地址来自仓库索引, 不走书源实例: 不传 sourceKey, 避免网页菜单把
                    // 虚拟插件源当书源禁用/删除
                    PlatformCapabilityProviders.get()
                        .openWebView(url = url, sourceName = item.name)
                },
            )
        }
        if (onConfigure != null) {
            IconActionButton(
                icon = Res.drawable.ic_other_setting,
                contentDescription = stringResource(Res.string.manga_extension_setting),
                tint = colors.accent,
                onClick = onConfigure,
            )
        }
        Spacer(Modifier.width(4.dp))
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

/** 未装载原因文案 (资源在 8 语言 strings.xml; FAILED 拼装载器原始消息)。 */
@Composable
private fun MangaNotLoadedReason.toText(detail: String?): String = when (this) {
    MangaNotLoadedReason.FILTERED -> stringResource(Res.string.manga_extension_not_loaded_filtered)
    MangaNotLoadedReason.UNSIGNED -> stringResource(Res.string.manga_extension_not_loaded_unsigned)
    MangaNotLoadedReason.UNSUPPORTED_LIB_VERSION ->
        stringResource(Res.string.manga_extension_not_loaded_unsupported_lib)
    MangaNotLoadedReason.MALFORMED -> stringResource(Res.string.manga_extension_not_loaded_malformed)
    MangaNotLoadedReason.FAILED ->
        stringResource(Res.string.manga_extension_not_loaded_failed, detail.orEmpty())
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
            text = NsfwLabel,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 安装/更新动作钮: 无进行中步骤时展示主动作 (下载图标); 进行中 (下载/安装) 点按为取消。
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
                IconActionButton(
                    icon = Res.drawable.ic_baseline_close,
                    contentDescription = stringResource(Res.string.cancel),
                    tint = colors.secondaryText,
                    onClick = { onCancel(pkgName) },
                )
            }

        MangaInstallState.INSTALLED, MangaInstallState.ERROR, null ->
            IconActionButton(
                icon = Res.drawable.ic_download,
                contentDescription = primaryText,
                tint = colors.accent,
                onClick = { onPrimary(pkgName) },
            )
    }
}
