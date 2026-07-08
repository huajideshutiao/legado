package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogFormEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.widget.form.FormAdapter
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.ui.widget.text.EditEntity.CodePattern
import io.legado.app.ui.widget.text.EditEntity.ViewType
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HttpTtsEditDialog() : BaseDialogFragment(R.layout.dialog_form_edit),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogFormEditBinding::bind)
    private val viewModel by viewModels<HttpTtsEditViewModel>()
    private val adapter by lazy { FormAdapter() }
    private val editEntities: ArrayList<EditEntity> = ArrayList()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.adapter = adapter
        viewModel.initData(arguments) {
            initView(httpTTS = it)
        }
        initMenu()
    }

    fun initMenu() {
        setupTitleBar(
            menuRes = R.menu.speak_engine_edit,
            onMenuClick = ::onMenuItemClick
        )
    }

    fun initView(httpTTS: HttpTTS) {
        editEntities.clear()
        editEntities.apply {
            // name: 简单文本字段
            add(EditEntity("name", httpTTS.name, R.string.name))
            // url: CodeView + 全部 pattern (legado + json + js)
            add(
                EditEntity(
                    "url",
                    httpTTS.url,
                    "url",
                    ViewType.code,
                    codePatterns = CodePattern.all
                )
            )
            // contentType: 短文本字段（MIME 类型），无需语法高亮
            add(EditEntity("contentType", httpTTS.contentType, "Content-Type"))
            // concurrentRate: 短文本字段（限速值），无需语法高亮
            add(EditEntity("concurrentRate", httpTTS.concurrentRate, R.string.concurrent_rate))
            // loginUrl: CodeView + 全部 pattern
            add(
                EditEntity(
                    "loginUrl",
                    httpTTS.loginUrl,
                    R.string.login_url,
                    ViewType.code,
                    codePatterns = CodePattern.all
                )
            )
            // loginUi: CodeView + json pattern
            add(
                EditEntity(
                    "loginUi",
                    httpTTS.loginUi,
                    R.string.login_ui,
                    ViewType.code,
                    codePatterns = CodePattern.json
                )
            )
            // loginCheckJs: CodeView + js pattern
            add(
                EditEntity(
                    "loginCheckJs",
                    httpTTS.loginCheckJs,
                    R.string.login_check_js,
                    ViewType.code,
                    codePatterns = CodePattern.js
                )
            )
            // header: CodeView + 全部 pattern
            add(
                EditEntity(
                    "header",
                    httpTTS.header,
                    R.string.source_http_header,
                    ViewType.code,
                    codePatterns = CodePattern.all
                )
            )
        }
        adapter.editEntities = editEntities
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> viewModel.save(dataFromView()) {
                toastOnUi("保存成功")
            }

            R.id.menu_login -> dataFromView().let { httpTts ->
                if (httpTts.hasLogin()) {
                    viewModel.save(httpTts) {
                        httpTts.showLoginDialog(activity as AppCompatActivity)
                    }
                } else toastOnUi("没有登陆界面")
            }

            R.id.menu_show_login_header -> alert {
                setTitle(R.string.login_header)
                dataFromView().getLoginHeader()?.let { loginHeader ->
                    setMessage(loginHeader)
                }
            }

            R.id.menu_del_login_header -> dataFromView().removeLoginHeader()
            R.id.menu_copy_source -> dataFromView().let {
                context?.sendToClip(GSON.toJson(it))
            }

            R.id.menu_paste_source -> viewModel.importFromClip {
                initView(it)
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("httpTTSHelp")
        }
        return true
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
