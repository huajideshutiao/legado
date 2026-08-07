package io.legado.app.ui.book.read.config

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.viewModels
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.help.i18n.androidAppString
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.ui.widget.text.EditEntity.CodePattern
import io.legado.app.ui.widget.text.EditEntity.ViewType
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toJson
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
        HttpTtsEditDialogContent(
            editEntities = editEntities,
            onBack = { dismissAllowingStateLoss() },
            onSave = {
                viewModel.save(dataFromView()) {
                    toastOnUi("保存成功")
                }
            },
            onLogin = { login() },
            onShowLoginHeader = { showLoginHeader() },
            onDeleteLoginHeader = { dataFromView().removeLoginHeader() },
            onCopySource = {
                context?.sendToClip(GSON.toJson(dataFromView()))
            },
            onPasteSource = {
                viewModel.importFromClip { initView(it) }
            },
            onShowLog = {
                AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("app_log"))
            },
            onShowHelp = { showHelp("httpTTSHelp") },
        )
    }

    private fun login() = dataFromView().let { httpTts ->
        if (httpTts.hasLogin()) {
            viewModel.save(httpTts) {
                httpTts.showLoginDialog()
            }
        } else toastOnUi("没有登陆界面")
    }

    private fun showLoginHeader() = alert {
        setTitle(androidAppString("login_header"))
        dataFromView().getLoginHeader()?.let { loginHeader ->
            setMessage(loginHeader)
        }
    }

    fun initView(httpTTS: HttpTTS) {
        editEntities = listOf(
            // name: 简单文本字段
            EditEntity("name", httpTTS.name, androidAppString("name")),
            // url: 代码字段 + 全部着色 (legado + json + js)
            EditEntity("url", httpTTS.url, "url", ViewType.code, codePatterns = CodePattern.all),
            // contentType: 短文本字段（MIME 类型），无需语法高亮
            EditEntity("contentType", httpTTS.contentType, "Content-Type"),
            // concurrentRate: 短文本字段（限速值），无需语法高亮
            EditEntity("concurrentRate", httpTTS.concurrentRate, androidAppString("concurrent_rate")),
            // loginUrl: 代码字段 + 全部着色
            EditEntity(
                "loginUrl", httpTTS.loginUrl, androidAppString("login_url"),
                ViewType.code, codePatterns = CodePattern.all
            ),
            // loginUi: 代码字段 + json 着色
            EditEntity(
                "loginUi", httpTTS.loginUi, androidAppString("login_ui"),
                ViewType.code, codePatterns = CodePattern.json
            ),
            // loginCheckJs: 代码字段 + js 着色
            EditEntity(
                "loginCheckJs", httpTTS.loginCheckJs, androidAppString("login_check_js"),
                ViewType.code, codePatterns = CodePattern.js
            ),
            // header: 代码字段 + 全部着色
            EditEntity(
                "header", httpTTS.header, androidAppString("source_http_header"),
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
