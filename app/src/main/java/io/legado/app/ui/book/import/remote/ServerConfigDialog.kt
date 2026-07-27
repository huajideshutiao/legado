package io.legado.app.ui.book.import.remote

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.getConfigJsonObject
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson

class ServerConfigDialog() : BaseComposeDialogFragment() {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<ServerConfigViewModel>()

    private var name by mutableStateOf("")
    private var url by mutableStateOf("")
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // 重建时回填输入中内容(对齐原 EditText 状态恢复)
            name = savedInstanceState.getString("name") ?: ""
            url = savedInstanceState.getString("url") ?: ""
            username = savedInstanceState.getString("username") ?: ""
            password = savedInstanceState.getString("password") ?: ""
            viewModel.init(arguments?.getLong("id")) {}
        } else {
            viewModel.init(arguments?.getLong("id")) {
                upConfigView(viewModel.mServer)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("name", name)
        outState.putString("url", url)
        outState.putString("username", username)
        outState.putString("password", password)
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = "",
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_save),
                            contentDescription = stringResource(R.string.action_save),
                            tint = colors.primaryText,
                        )
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                AppOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 原 TYPE spinner 仅 WEBDAV 单项，退化为固定展示
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("TYPE", color = colors.accent, modifier = Modifier.padding(8.dp))
                    Text("WEBDAV", color = colors.primaryText)
                }
                AppOutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "url",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                AppOutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "username",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                AppOutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "password",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }

    private fun save() {
        viewModel.save(getServer()) {
            dismissAllowingStateLoss()
        }
    }

    private fun upConfigView(server: Server?) {
        name = server?.name ?: ""
        val config = server?.getConfigJsonObject()
        url = config?.optString("url") ?: ""
        username = config?.optString("username") ?: ""
        password = config?.optString("password") ?: ""
    }

    private fun getServer(): Server {
        val server = viewModel.mServer?.copy() ?: Server()
        server.name = name
        server.type = Server.TYPE.WEBDAV
        server.config = GSON.toJson(getWebDavConfig())
        return server
    }

    private fun getWebDavConfig(): HashMap<String, String> {
        val data = hashMapOf<String, String>()
        data["url"] = url
        data["username"] = username
        data["password"] = password
        return data
    }

}
