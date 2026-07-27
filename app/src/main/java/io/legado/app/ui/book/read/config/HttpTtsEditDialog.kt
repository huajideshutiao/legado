package io.legado.app.ui.book.read.config

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FormEditFields
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.ui.widget.text.EditEntity.CodePattern
import io.legado.app.ui.widget.text.EditEntity.ViewType
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi

class HttpTtsEditDialog() : BaseComposeDialogFragment() {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<HttpTtsEditViewModel>()
    private var editEntities by mutableStateOf<List<EditEntity>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initData(arguments) {
            initView(httpTTS = it)
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = "",
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    IconButton(onClick = {
                        viewModel.save(dataFromView()) {
                            toastOnUi("保存成功")
                        }
                    }) {
                        Icon(
                            painter = rememberPainter("ic_save"),
                            contentDescription = stringResource(R.string.action_save),
                            tint = colors.primaryText,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        @Composable
                        fun item(textRes: Int, onClick: () -> Unit) {
                            DropdownMenuItem(
                                onClick = { dismissMenu(); onClick() },
                            ) { Text(stringResource(textRes), color = colors.primaryText) }
                        }
                        item(R.string.login) { login() }
                        item(R.string.show_login_header) { showLoginHeader() }
                        item(R.string.del_login_header) { dataFromView().removeLoginHeader() }
                        item(R.string.copy_source) {
                            context?.sendToClip(GSON.toJson(dataFromView()))
                        }
                        item(R.string.paste_source) {
                            viewModel.importFromClip { initView(it) }
                        }
                        item(R.string.log) { showDialogFragment<AppLogDialog>() }
                        item(R.string.help) { showHelp("httpTTSHelp") }
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                FormEditFields(editEntities)
            }
        }
    }

    private fun login() = dataFromView().let { httpTts ->
        if (httpTts.hasLogin()) {
            viewModel.save(httpTts) {
                httpTts.showLoginDialog(activity as AppCompatActivity)
            }
        } else toastOnUi("没有登陆界面")
    }

    private fun showLoginHeader() = alert {
        setTitle(R.string.login_header)
        dataFromView().getLoginHeader()?.let { loginHeader ->
            setMessage(loginHeader)
        }
    }

    fun initView(httpTTS: HttpTTS) {
        editEntities = listOf(
            // name: 简单文本字段
            EditEntity("name", httpTTS.name, R.string.name),
            // url: CodeView + 全部 pattern (legado + json + js)
            EditEntity("url", httpTTS.url, "url", ViewType.code, codePatterns = CodePattern.all),
            // contentType: 短文本字段（MIME 类型），无需语法高亮
            EditEntity("contentType", httpTTS.contentType, "Content-Type"),
            // concurrentRate: 短文本字段（限速值），无需语法高亮
            EditEntity("concurrentRate", httpTTS.concurrentRate, R.string.concurrent_rate),
            // loginUrl: CodeView + 全部 pattern
            EditEntity(
                "loginUrl", httpTTS.loginUrl, R.string.login_url,
                ViewType.code, codePatterns = CodePattern.all
            ),
            // loginUi: CodeView + json pattern
            EditEntity(
                "loginUi", httpTTS.loginUi, R.string.login_ui,
                ViewType.code, codePatterns = CodePattern.json
            ),
            // loginCheckJs: CodeView + js pattern
            EditEntity(
                "loginCheckJs", httpTTS.loginCheckJs, R.string.login_check_js,
                ViewType.code, codePatterns = CodePattern.js
            ),
            // header: CodeView + 全部 pattern
            EditEntity(
                "header", httpTTS.header, R.string.source_http_header,
                ViewType.code, codePatterns = CodePattern.all
            ),
        )
    }

    private fun dataFromView(): HttpTTS {
        val httpTTS = HttpTTS(
            id = viewModel.id ?: System.currentTimeMillis()
        )
        editEntities.forEach {
            when (it.key) {
                "name" -> httpTTS.name = it.text.orEmpty()
                "url" -> httpTTS.url = it.text.orEmpty()
                "contentType" -> httpTTS.contentType = it.text
                "concurrentRate" -> httpTTS.concurrentRate = it.text
                "loginUrl" -> httpTTS.loginUrl = it.text
                "loginUi" -> httpTTS.loginUi = it.text
                "loginCheckJs" -> httpTTS.loginCheckJs = it.text
                "header" -> httpTTS.header = it.text
            }
        }
        return httpTTS
    }

}
