package io.legado.app.ui.book.tvbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.add
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.confirm
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.ic_refresh_black_24dp
import legado.ui.generated.resources.tvbox
import legado.ui.generated.resources.tvbox_add_source
import legado.ui.generated.resources.tvbox_delete_source
import legado.ui.generated.resources.tvbox_filterable
import legado.ui.generated.resources.tvbox_jar_failed
import legado.ui.generated.resources.tvbox_jar_idle
import legado.ui.generated.resources.tvbox_jar_loading
import legado.ui.generated.resources.tvbox_jar_ready
import legado.ui.generated.resources.tvbox_jar_sites
import legado.ui.generated.resources.tvbox_jars
import legado.ui.generated.resources.tvbox_loading
import legado.ui.generated.resources.tvbox_no_site
import legado.ui.generated.resources.tvbox_no_source
import legado.ui.generated.resources.tvbox_remove_source_confirm
import legado.ui.generated.resources.tvbox_searchable
import legado.ui.generated.resources.tvbox_sites
import legado.ui.generated.resources.tvbox_unsupported
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 错误红 (与 MangaExtensionScreen 危险动作色同源)。 */
private val ErrorColor = Color(0xFFF53F3F)

/** 类型标记底色 (JAR/CMS/不支持三种)。 */
private val TagColor = Color(0x14607D8B)

/**
 * 影视源 (TVBox) 管理页 (shared, 已注册 TvBoxService 的端有入口; 未注册端隐藏不会进入)。
 *
 * 自上而下: 使用说明 → 配置来源 (增删/切换/刷新) → Spider jar 下载状态 →
 * 站点列表 (类型/可搜索/可筛选标记 + 已添加开关; 开关落虚拟书源行的存在与否,
 * 是否参与搜索归书源界面管)。
 */
@Composable
fun TvBoxManageScreen(
    state: TvBoxUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAddSource: (String) -> Unit,
    onRemoveSource: (String) -> Unit,
    onActivateSource: (String) -> Unit,
    onToggleSite: (String, Boolean) -> Unit,
    onProbeJars: () -> Unit,
    onClearError: () -> Unit,
) {
    val colors = AppTheme.colors
    val title = stringResource(Res.string.tvbox)
    val addText = stringResource(Res.string.add)
    val deleteText = stringResource(Res.string.delete)
    val cancelText = stringResource(Res.string.cancel)
    val confirmText = stringResource(Res.string.confirm)
    val addSourceText = stringResource(Res.string.tvbox_add_source)
    val emptySourceText = stringResource(Res.string.tvbox_no_source)
    val sourcesTitle = stringResource(Res.string.tvbox_add_source)
    val sitesTitle = stringResource(Res.string.tvbox_sites)
    val jarsTitle = stringResource(Res.string.tvbox_jars)
    val deleteSourceTitle = stringResource(Res.string.tvbox_delete_source)
    val removeSourceConfirm = stringResource(Res.string.tvbox_remove_source_confirm)
    val emptySiteText = stringResource(Res.string.tvbox_no_site)
    val loadingText = stringResource(Res.string.tvbox_loading)
    val unsupportedText = stringResource(Res.string.tvbox_unsupported)
    val searchableText = stringResource(Res.string.tvbox_searchable)
    val filterableText = stringResource(Res.string.tvbox_filterable)

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRemoveUrl by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = title,
            onBack = onBack,
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                        contentDescription = null,
                        tint = colors.primaryText,
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add),
                        contentDescription = addText,
                        tint = colors.primaryText,
                    )
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (state.loading) {
                item(key = "loading") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(loadingText, fontSize = 13.sp, color = colors.secondaryText)
                    }
                }
            }
            // 最近一次错误 (导入/切换/刷新/jar 装载失败)
            if (state.error != null) {
                item(key = "error") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.error,
                            fontSize = 12.sp,
                            color = ErrorColor,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onClearError) {
                            Text(cancelText, fontSize = 12.sp, color = colors.secondaryText)
                        }
                    }
                }
            }
            item(key = "sec_sources") { SectionHeader(sourcesTitle) }
            if (state.sources.isEmpty()) {
                item(key = "sources_empty") {
                    Text(
                        text = emptySourceText,
                        fontSize = 13.sp,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            } else {
                items(state.sources, key = { "src_$it" }) { url ->
                    SourceRow(
                        url = url,
                        active = url == state.activeSource,
                        removeText = deleteText,
                        onActivate = { onActivateSource(url) },
                        onRemove = { pendingRemoveUrl = url },
                    )
                }
            }
            if (state.jars.isNotEmpty()) {
                item(key = "sec_jars") { SectionHeader(jarsTitle) }
                items(state.jars, key = { "jar_${it.spec}" }) { jar ->
                    JarRow(jar = jar, onRetry = onProbeJars)
                }
            }
            if (state.sites.isNotEmpty()) {
                item(key = "sec_sites") { SectionHeader(sitesTitle) }
                items(state.sites, key = { "site_${it.key}" }) { site ->
                    SiteRow(
                        site = site,
                        onToggle = { onToggleSite(site.key, it) },
                        unsupportedText = unsupportedText,
                        searchableText = searchableText,
                        filterableText = filterableText,
                    )
                }
            } else if (!state.loading) {
                item(key = "sites_empty") {
                    Text(
                        text = emptySiteText,
                        fontSize = 13.sp,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        var urlText by remember { mutableStateOf("") }
        AppAlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = addSourceText,
            content = {
                AppTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = null,
                    placeholder = "https://",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            okButton = AlertButton(
                text = confirmText,
                enabled = urlText.isNotBlank(),
                onClick = {
                    onAddSource(urlText.trim())
                    showAddDialog = false
                },
            ),
            cancelButton = AlertButton(text = cancelText) { showAddDialog = false },
        )
    }

    pendingRemoveUrl?.let { url ->
        AppAlertDialog(
            onDismissRequest = { pendingRemoveUrl = null },
            title = deleteSourceTitle,
            message = "$removeSourceConfirm\n$url",
            okButton = AlertButton(text = deleteText) {
                onRemoveSource(url)
                pendingRemoveUrl = null
            },
            cancelButton = AlertButton(text = cancelText) {
                pendingRemoveUrl = null
            },
        )
    }
}

/** 分区标题 (同 MangaExtensionScreen 的小标题样式)。 */
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

/** 配置来源条目: ✓ 前缀标当前生效源, 点条目切换, 末尾删除。 */
@Composable
private fun SourceRow(
    url: String,
    active: Boolean,
    removeText: String,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onActivate)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (active) "✓ $url" else url,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) colors.primaryText else colors.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRemove) {
            Text(removeText, fontSize = 12.sp, color = ErrorColor)
        }
    }
}

/** Spider jar 条目: 规格串 + 站点数 + 状态 (未下载/失败时点按触发装载重试)。 */
@Composable
private fun JarRow(jar: TvBoxJarItem, onRetry: () -> Unit) {
    val colors = AppTheme.colors
    val idleText = stringResource(Res.string.tvbox_jar_idle)
    val loadingText = stringResource(Res.string.tvbox_jar_loading)
    val readyText = stringResource(Res.string.tvbox_jar_ready)
    val failedText = stringResource(Res.string.tvbox_jar_failed)
    val countText = stringResource(Res.string.tvbox_jar_sites, jar.siteCount.toString())
    val statusText = when (jar.status) {
        TvBoxJarStatus.IDLE -> idleText
        TvBoxJarStatus.LOADING -> loadingText
        TvBoxJarStatus.READY -> readyText
        TvBoxJarStatus.FAILED -> failedText
    }
    val statusColor = when (jar.status) {
        TvBoxJarStatus.READY -> colors.accent
        TvBoxJarStatus.FAILED -> ErrorColor
        else -> colors.secondaryText
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (jar.status == TvBoxJarStatus.FAILED || jar.status == TvBoxJarStatus.IDLE) {
                    Modifier.clickable(onClick = onRetry)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = statusColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = countText,
                fontSize = 12.sp,
                color = colors.secondaryText,
            )
            if (jar.status == TvBoxJarStatus.LOADING) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    Modifier.height(14.dp).width(14.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            text = jar.spec,
            fontSize = 12.sp,
            color = colors.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (jar.message != null) {
            Text(
                text = jar.message,
                fontSize = 12.sp,
                color = ErrorColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 站点条目: 名称 + 类型/标记 + api, 按压整行切换"已添加"状态 (仅受支持站点可点, 尾部开关只作状态显示)。 */
@Composable
private fun SiteRow(
    site: TvBoxSiteItem,
    onToggle: (Boolean) -> Unit,
    unsupportedText: String,
    searchableText: String,
    filterableText: String,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (site.supported) {
                    Modifier.clickable { onToggle(!site.added) }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = site.name,
                    fontSize = 15.sp,
                    color = if (site.supported) colors.primaryText else colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!site.supported) {
                    Spacer(Modifier.width(6.dp))
                    SiteTag(unsupportedText)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteTag(site.kind.name)
                if (site.searchable) {
                    Spacer(Modifier.width(4.dp))
                    SiteTag(searchableText)
                }
                if (site.filterable) {
                    Spacer(Modifier.width(4.dp))
                    SiteTag(filterableText)
                }
            }
            Text(
                text = site.api,
                fontSize = 12.sp,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (site.supported) {
            // 只读展示: 切换由整行点击驱动 (onCheckedChange = null 不消费点击、视觉仍为正常态)
            AppSwitch(checked = site.added, onCheckedChange = null)
        }
    }
}

/** 小圆角标记 (类型/可搜索/可筛选/不支持)。 */
@Composable
private fun SiteTag(text: String) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .background(TagColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            color = colors.secondaryText,
            fontSize = 10.sp,
        )
    }
}
