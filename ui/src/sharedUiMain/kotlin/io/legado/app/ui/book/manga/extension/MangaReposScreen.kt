package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.add
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.confirm
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.manga_extension_add_repo
import legado.ui.generated.resources.manga_extension_repo_url
import legado.ui.generated.resources.manga_extension_repos
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 危险动作色 (删除, 与 MangaExtensionScreen NSFW 角标同源 Arco danger)。 */
private val DangerColor = Color(0xFFF53F3F)

/**
 * 漫画插件仓库 ScreenModel: 状态真源与插件管理页同为 [MangaExtensionService.state],
 * 本类只转发增删动作 (首入口 init 幂等)。
 */
class MangaReposScreenModel(
    private val toast: (String) -> Unit,
) : ScreenModel {

    private val service = MangaExtensionServiceProviders.getOrNull()

    private val scope = screenModelScope("漫画插件仓库")

    init {
        service?.init()
    }

    val state: StateFlow<MangaExtensionUiState> =
        service?.state ?: MutableStateFlow(MangaExtensionUiState())

    fun addRepo(url: String) {
        val svc = service ?: return
        scope.launch {
            svc.addRepo(url)
                .onFailure { toast(it.message ?: it.toString()) }
        }
    }

    fun removeRepo(indexUrl: String) {
        val svc = service ?: return
        scope.launch { runCatching { svc.removeRepo(indexUrl) } }
    }
}

/**
 * 漫画插件仓库管理页 (shared): 仓库列表 + 删除确认 + 添加弹窗 (AppTextField 输 url,
 * 名称与签名指纹由仓库索引元数据带回)。
 */
@Composable
fun MangaReposScreen(
    state: MangaExtensionUiState,
    onBack: () -> Unit,
    onAddRepo: (String) -> Unit,
    onRemoveRepo: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val titleRepos = stringResource(Res.string.manga_extension_repos)
    val addText = stringResource(Res.string.add)
    val addRepoText = stringResource(Res.string.manga_extension_add_repo)
    val repoUrlText = stringResource(Res.string.manga_extension_repo_url)
    val deleteText = stringResource(Res.string.delete)
    val confirmText = stringResource(Res.string.confirm)
    val cancelText = stringResource(Res.string.cancel)

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteUrl by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleRepos,
            onBack = onBack,
            actions = {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add),
                        contentDescription = addText,
                        tint = colors.primaryText,
                    )
                }
            },
        )
        if (state.loading || state.refreshing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.repos, key = { it.indexUrl }) { repo ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { pendingDeleteUrl = repo.indexUrl }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = repo.name,
                                fontSize = 15.sp,
                                color = colors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = repo.indexUrl,
                                fontSize = 12.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { pendingDeleteUrl = repo.indexUrl }) {
                            Text(deleteText, color = DangerColor)
                        }
                    }
            }
        }
    }

    if (showAddDialog) {
        var urlText by remember { mutableStateOf("") }
        AppAlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = addRepoText,
            content = {
                AppTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = repoUrlText,
                    placeholder = "https://",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            okButton = AlertButton(
                text = confirmText,
                enabled = urlText.isNotBlank(),
                onClick = {
                    onAddRepo(urlText.trim())
                    showAddDialog = false
                },
            ),
            cancelButton = AlertButton(text = cancelText) { showAddDialog = false },
        )
    }

    pendingDeleteUrl?.let { indexUrl ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteUrl = null },
            title = deleteText,
            message = indexUrl,
            okButton = AlertButton(text = deleteText) {
                onRemoveRepo(indexUrl)
            },
            cancelButton = AlertButton(text = cancelText) { pendingDeleteUrl = null },
        )
    }
}
