package io.legado.app.ui.login

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.DialogLoginBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.help.IntentData
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.positiveButton
import io.legado.app.lib.theme.applyThemeToChildren
import io.legado.app.lib.theme.space
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnUserCheckedChangeListener
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.views.onClick


class SourceLoginDialog : BaseDialogFragment(R.layout.dialog_login) {

    private val binding by viewBinding(DialogLoginBinding::bind)
    private val source by lazy { (IntentData.source as? BaseSource) }
    private val book by lazy { IntentData.book }
    private val chapter by lazy { IntentData.chapter }
    private var loginUi: List<RowUi>? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = source ?: return
        buildLoginUi(source)
        setupTitleBar(
            title = getString(R.string.login_source, source.getTag()),
            menuRes = R.menu.source_login
        ) { item ->
            when (item?.itemId) {
                R.id.menu_ok -> login(source, getLoginData())

                R.id.menu_show_login_header -> source.getLoginHeader()?.let { loginHeader ->
                    alert {
                        setTitle(R.string.login_header)
                        setMessage(loginHeader)
                        positiveButton(R.string.copy_text) {
                            appCtx.sendToClip(loginHeader)
                        }
                    }
                } ?: toastOnUi("没有请求头！")

                R.id.menu_del_login_header -> source.removeLoginHeader()
                R.id.menu_log -> showDialogFragment<AppLogDialog>()
            }
            return@setupTitleBar true
        }
        observeEvent<Boolean>(EventBus.REFRESH_LOGIN_UI) {
            this.source?.let { buildLoginUi(it) }
        }
    }

    private fun buildLoginUi(source: BaseSource) {
        binding.flexbox.removeAllViews()
        val loginInfo = source.getLoginInfoMap()
        try {
            loginUi = source.loginUi()
            loginUi?.forEachIndexed { index, rowUi ->
                val defaultStyle =
                    if (rowUi.type == RowUi.Type.text || rowUi.type == RowUi.Type.password) {
                        FlexChildStyle(cols = 1)
                    } else {
                        FlexChildStyle.defaultStyle2
                    }
                val rowStyle = rowUi.style(defaultStyle)
                val view = when (rowUi.type) {
                    RowUi.Type.text -> createSourceEditView(
                        binding.flexbox, rowUi.name, loginInfo?.get(rowUi.name)
                    ) { editText ->
                        editText.setAutofillHints("username")
                    }

                    RowUi.Type.password -> createSourceEditView(
                        binding.flexbox, rowUi.name, loginInfo?.get(rowUi.name)
                    ) { editText ->
                        editText.inputType =
                            InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                        editText.setAutofillHints("password")
                    }

                    RowUi.Type.select -> {
                        val ctx = requireContext()
                        val padding =
                            ctx.space.default
                        val root = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(padding)
                            layoutParams = GridLayout.LayoutParams().apply {
                                width = GridLayout.LayoutParams.MATCH_PARENT
                                height = GridLayout.LayoutParams.WRAP_CONTENT
                            }
                        }
                        val tv = TextView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            setTextColor(ctx.getColor(R.color.primaryText))
                            text = rowUi.name
                        }
                        val spinner = AppCompatSpinner(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                            )
                        }
                        val chars = rowUi.chars ?: emptyList()
                        val adapter = ArrayAdapter(
                            ctx, android.R.layout.simple_spinner_item, chars
                        ).apply {
                            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                        spinner.adapter = adapter
                        var selectedPosition =
                            chars.indexOf(loginInfo?.get(rowUi.name)).coerceAtLeast(0)
                        spinner.setSelection(selectedPosition)
                        spinner.onItemSelectedListener =
                            object : AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                                ) {
                                    if (position == selectedPosition) return
                                    selectedPosition = position
                                    handleButtonClick(source, rowUi)
                                }

                                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                            }
                        root.addView(tv)
                        root.addView(spinner)
                        root.tag = spinner
                        root
                    }

                    RowUi.Type.toggle -> {
                        val ctx = requireContext()
                        val swt = SwitchCompat(ctx).apply {
                            layoutParams = GridLayout.LayoutParams().apply {
                                width = GridLayout.LayoutParams.MATCH_PARENT
                                height = GridLayout.LayoutParams.MATCH_PARENT
                            }
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(ctx.space.default)
                            text = rowUi.name
                            isChecked = loginInfo?.get(rowUi.name) == "true"
                            setOnUserCheckedChangeListener {
                                handleButtonClick(source, rowUi)
                            }
                        }
                        swt
                    }

                    else -> ItemFilletTextBinding.inflate(
                        layoutInflater, binding.flexbox, false
                    ).apply {
                        textView.text = rowUi.name
                        textView.setPadding(textView.context.space.lg)
                        root.onClick {
                            handleButtonClick(source, rowUi)
                        }
                    }.root
                }
                // 统一在此处应用列宽样式: 所有分支的 view 的 layoutParams 均为 GridLayout.LayoutParams
                // (text/password 由 createSourceEditView 设置, select/toggle 由各自分支设置,
                //  button 由 inflate(parent=GridLayout) 自动生成), apply 不会因类型不匹配而静默失败
                rowStyle.apply(view)
                view.id = index + 1000
                view.minimumHeight = 60.dpToPx() * rowStyle.rows.coerceAtLeast(1)
                binding.flexbox.addView(view)
            }
            // 动态构造的登录行 (TextInputLayout/CodeView/TextView/SwitchCompat 等) 不走 Factory2,
            // 在所有行 addView 完成后对 flexbox 子节点统一兜底着色。
            // inflate 的 button 分支已通过 Factory2 着色并标记, applyThemeToChildren 遍历时零成本跳过。
            // 注意: buildLoginUi 会被 REFRESH_LOGIN_UI 事件多次触发, removeAllViews 后重新 addView,
            // 每次都需重新着色 (新构造的 View 未标记)。
            binding.flexbox.applyThemeToChildren()
        } catch (e: Exception) {
            AppLog.put("登录UI 构建失败", e, true)
        }
    }

    private fun createSourceEditView(
        parent: ViewGroup,
        hint: String,
        text: String?,
        configure: (CodeView) -> Unit = {}
    ): View {
        val ctx = parent.context
        val root = com.google.android.material.textfield.TextInputLayout(ctx).apply {
            // 父容器是 GridLayout(columnCount=12),必须用 GridLayout.LayoutParams,
            // 否则 FlexChildStyle.apply 的 as? GridLayout.LayoutParams 会返回 null 导致列宽失效
            layoutParams = GridLayout.LayoutParams().apply {
                width = GridLayout.LayoutParams.MATCH_PARENT
                height = GridLayout.LayoutParams.WRAP_CONTENT
            }
            setPadding(0, ctx.space.xs, 0, 0)
        }
        val editText = CodeView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(editText)
        root.hint = hint
        editText.setText(text)
        configure(editText)
        root.tag = editText
        return root
    }

    private fun handleButtonClick(source: BaseSource, rowUi: RowUi) {
        lifecycleScope.launch(IO) {
            if (rowUi.action.isAbsUrl()) {
                context?.openUrl(rowUi.action!!)
            } else if (rowUi.action != null) {
                val buttonFunctionJS = rowUi.action!!
                val loginJS = source.getLoginJs() ?: ""
                kotlin.runCatching {
                    runScriptWithContext {
                        source.evalJS("$loginJS\n$buttonFunctionJS") {
                            put("result", { getLoginData() })
                            put("book", book)
                            put("chapter", chapter)
                        }
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("LoginUI Button ${rowUi.name} JavaScript error", e, true)
                }
            }
        }
    }

    private fun getLoginData(): HashMap<String, String> {
        val loginData = hashMapOf<String, String>()
        loginUi?.forEachIndexed { index, rowUi ->
            when (rowUi.type) {
                RowUi.Type.text, RowUi.Type.password -> {
                    val rowView = binding.root.findViewById<View>(index + 1000)
                    (rowView.tag as? CodeView)?.text?.let {
                        loginData[rowUi.name] = it.toString()
                    }
                }

                RowUi.Type.select -> {
                    val rowView = binding.root.findViewById<View>(index + 1000)
                    (rowView.tag as? Spinner)?.selectedItem?.let {
                        loginData[rowUi.name] = it.toString()
                    }
                }

                RowUi.Type.toggle -> {
                    val rowView = binding.root.findViewById<View>(index + 1000)
                    loginData[rowUi.name] =
                        (rowView as? SwitchCompat)?.isChecked.toString()
                }

                else -> {}
            }
        }
        return loginData
    }

    private fun login(
        source: BaseSource, loginData: HashMap<String, String>
    ) {
        lifecycleScope.launch(IO) {
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) {
                    dismiss()
                }
            } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                try {
                    runScriptWithContext {
                        source.login()
                    }
                    context?.toastOnUi(R.string.success)
                    withContext(Main) {
                        dismiss()
                    }
                } catch (e: Exception) {
                    AppLog.put("登录出错\n${e.localizedMessage}", e)
                    context?.toastOnUi("登录出错\n${e.localizedMessage}")
                    e.printOnDebug()
                }
            }
        }
    }

}
