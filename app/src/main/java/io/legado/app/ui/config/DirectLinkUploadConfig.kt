package io.legado.app.ui.config

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogFormEditBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.negativeButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.widget.form.FormAdapter
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.ui.widget.text.EditEntity.ViewType
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.init.appCtx

class DirectLinkUploadConfig : BaseDialogFragment(R.layout.dialog_form_edit),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogFormEditBinding::bind)
    private val adapter by lazy { FormAdapter() }
    private val editEntities: ArrayList<EditEntity> = ArrayList()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            menuRes = R.menu.direct_link_upload_config,
            onMenuClick = ::onMenuItemClick
        )
        binding.recyclerView.adapter = adapter
        // 启用底部按钮栏: 左侧测试按钮 + 右侧取消/确定
        binding.bottomLayout.visible()
        binding.tvFooterLeft.apply {
            visible()
            text = getString(R.string.test)
            setOnClickListener { test() }
        }
        binding.tvCancel.apply {
            visible()
            setOnClickListener { dismiss() }
        }
        binding.tvOk.apply {
            visible()
            setOnClickListener {
                getRule()?.let { rule ->
                    DirectLinkUpload.putConfig(rule)
                    dismiss()
                }
            }
        }
        upView(DirectLinkUpload.getRule())
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_import_default -> importDefault()
            R.id.menu_copy_rule -> getRule()?.let { rule ->
                requireContext().sendToClip(GSON.toJson(rule))
            }

            R.id.menu_paste_rule -> runCatching {
                getClipText()!!.let {
                    val rule = GSON.fromJsonObject<DirectLinkUpload.Rule>(it).getOrThrow()
                    upView(rule)
                }
            }.onFailure {
                toastOnUi("剪贴板为空或格式不对")
            }
        }
        return true
    }

    private fun upView(rule: DirectLinkUpload.Rule) {
        editEntities.clear()
        editEntities.apply {
            add(EditEntity("uploadUrl", rule.uploadUrl, R.string.upload_url))
            add(EditEntity("downloadUrlRule", rule.downloadUrlRule, R.string.download_url_rule))
            add(EditEntity("summary", rule.summary, R.string.summary))
            add(
                EditEntity(
                    "compress",
                    rule.compress.toString(),
                    R.string.is_compress,
                    ViewType.checkBox
                )
            )
        }
        adapter.editEntities = editEntities
    }

    private fun getRule(): DirectLinkUpload.Rule? {
        var uploadUrl = ""
        var downloadUrlRule = ""
        var summary = ""
        var compress = false
        editEntities.forEach {
            when (it.key) {
                "uploadUrl" -> uploadUrl = it.text.orEmpty()
                "downloadUrlRule" -> downloadUrlRule = it.text.orEmpty()
                "summary" -> summary = it.text.orEmpty()
                "compress" -> compress = it.boolValue
            }
        }
        if (uploadUrl.isBlank()) {
            toastOnUi("上传Url不能为空")
            return null
        }
        if (downloadUrlRule.isBlank()) {
            toastOnUi("下载Url规则不能为空")
            return null
        }
        if (summary.isBlank()) {
            toastOnUi("注释不能为空")
            return null
        }
        return DirectLinkUpload.Rule(uploadUrl, downloadUrlRule, summary, compress)
    }

    private fun importDefault() {
        requireContext().selector(DirectLinkUpload.defaultRules) { _, rule, _ ->
            upView(rule)
        }
    }

    private fun test() {
        val rule = getRule() ?: return
        execute {
            DirectLinkUpload.upLoad("test.json", "{}", "application/json", rule)
        }.onError {
            alertTestResult(it.localizedMessage ?: "ERROR")
        }.onSuccess { result ->
            alertTestResult(result)
        }
    }

    private fun alertTestResult(result: String) {
        alert {
            setTitle("result")
            setMessage(result)
            okButton()
            negativeButton(R.string.copy_text) {
                appCtx.sendToClip(result)
            }
        }
    }

}
