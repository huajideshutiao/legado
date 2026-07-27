package io.legado.app.ui.book.import.remote

import android.content.DialogInterface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

/**
 * 服务器配置
 */
class ServersDialog : BaseComposeDialogFragment() {

    val viewModel by viewModels<ServersViewModel>()

    private val callback get() = (activity as? Callback)

    // 记录初始服务器ID，用于判断是否真正切换
    private val initialServerId: Long = AppConfig.remoteServerId
    private var selectServerId by mutableStateOf(AppConfig.remoteServerId)
    private var servers by mutableStateOf<List<Server>>(emptyList())

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        LaunchedEffect(Unit) {
            appDb.serverDao.observeAll().catch {
                AppLog.put("服务器配置界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                servers = it
            }
        }
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = stringResource(R.string.server_config),
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    IconButton(onClick = { showDialogFragment(ServerConfigDialog()) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.create),
                            tint = colors.primaryText,
                        )
                    }
                },
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                items(servers, key = { it.id }) { item ->
                    ServerItem(item)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(text = stringResource(R.string.text_default)) {
                    AppConfig.remoteServerId = DEFAULT_WEBDAV_ID
                    dismissAllowingStateLoss()
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(text = stringResource(R.string.cancel)) {
                    dismissAllowingStateLoss()
                }
                AppTextButton(text = stringResource(R.string.ok)) {
                    AppConfig.remoteServerId = selectServerId
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    @Composable
    private fun ServerItem(item: Server) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { selectServerId = item.id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppRadioButton(
                    selected = item.id == selectServerId,
                    onClick = { selectServerId = item.id },
                )
                Text(
                    text = item.name,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { showDialogFragment(ServerConfigDialog(item.id)) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = colors.primaryText,
                )
            }
            IconButton(onClick = { deleteServer(item) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_clear_all),
                    contentDescription = stringResource(R.string.delete),
                    tint = colors.primaryText,
                )
            }
        }
    }

    private fun deleteServer(server: Server) {
        alert {
            setTitle(R.string.draw)
            setMessage(R.string.sure_del)
            yesButton {
                viewModel.delete(server)
            }
            noButton()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // 只有当服务器ID真正改变时才触发刷新
        if (selectServerId != initialServerId) {
            callback?.onDialogDismiss("serversDialog")
        }
    }

    interface Callback {

        fun onDialogDismiss(tag: String)

    }

}
