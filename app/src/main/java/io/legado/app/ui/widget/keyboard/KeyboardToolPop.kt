package io.legado.app.ui.widget.keyboard

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.PopupKeyboardToolBinding
import io.legado.app.databinding.ViewFindReplaceBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.Debounce
import io.legado.app.utils.activity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.windowSize
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.systemservices.layoutInflater
import splitties.systemservices.windowManager
import kotlin.math.abs

/**
 * 键盘帮助组件
 */
class KeyboardToolPop @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr),
    ViewTreeObserver.OnGlobalLayoutListener {

    private val helpChar = "❓"
    private lateinit var callBack: CallBack

    private val binding = PopupKeyboardToolBinding.inflate(LayoutInflater.from(context), this, true)
    private val findReplaceBinding = ViewFindReplaceBinding.bind(binding.layoutFindReplace.root)
    private val adapter = Adapter(context)
    private var mIsSoftKeyBoardShowing = false
    var initialPadding = 0
    var isAutoPadding = true
    private var dismissRunnable: Runnable? = null
    private var findKeyword: String = ""
    private var useRegex: Boolean = false
    private var matchCase: Boolean = false
    private var matchWholeWord: Boolean = false
    private val findDebounce = Debounce(wait = 200L) {
        callBack.getActiveCodeView()?.find(findKeyword, useRegex, matchCase, matchWholeWord)
    }

    init {
        initRecyclerView()
        isVisible = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    fun setInterface(
        rootView: View,
        callBack: CallBack
    ) {
        this.callBack = callBack
        upAdapterData()
        rootView.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onGlobalLayout() {
        val rect = Rect()
        // 获取当前页面窗口的显示范围
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = windowManager.windowSize.heightPixels
        val keyboardHeight = screenHeight - rect.bottom // 输入法的高度

        if (abs(keyboardHeight) > screenHeight / 5) {
            dismissRunnable?.let { binding.root.removeCallbacks(it) }
            dismissRunnable = null

            mIsSoftKeyBoardShowing = true // 超过屏幕五分之一则表示弹出了输入法
            if (isAutoPadding) {
                val targetPadding = initialPadding
                if (rootView.paddingBottom != targetPadding) {
                    rootView.post {
                        rootView.setPadding(0, 0, 0, targetPadding)
                    }
                }
            }
            if (visibility != VISIBLE) {
                visibility = VISIBLE
            }
        } else {
            if (mIsSoftKeyBoardShowing) {
                mIsSoftKeyBoardShowing = false
                if (isAutoPadding) {
                    if (rootView.paddingBottom != 0) {
                        rootView.setPadding(0, 0, 0, 0)
                    }
                }
                // 如果搜索面板显示，则跳过隐藏搜索面板和底栏的逻辑
                if (!binding.layoutFindReplace.root.isVisible) {
                    dismissRunnable = Runnable {
                        if (!mIsSoftKeyBoardShowing) {
                            isVisible = false
                            findReplaceBinding.tvFind.text?.clear()
                            findReplaceBinding.tvReplace.text?.clear()
                            useRegex = false
                            matchCase = false
                            matchWholeWord = false
                        }
                    }
                    binding.root.post(dismissRunnable)
                }
            }
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemFilletTextBinding.inflate(context.layoutInflater, it, false).apply {
                textView.text = helpChar
                root.setOnClickListener {
                    helpAlert()
                }
                root.setOnLongClickListener {
                    toggleFindReplace()
                    true
                }
            }
        }
        initFindReplace()
    }

    private fun initFindReplace() {
        findReplaceBinding.apply {
            tvNext.setOnClickListener {
                callBack.getActiveCodeView()?.find(
                    tvFind.text.toString(),
                    useRegex,
                    matchCase,
                    matchWholeWord,
                    true
                )
            }
            tvPrev.setOnClickListener {
                callBack.getActiveCodeView()?.find(
                    tvFind.text.toString(),
                    useRegex,
                    matchCase,
                    matchWholeWord
                )
            }
            tvDoReplace.setOnClickListener {
                callBack.getActiveCodeView()?.replace(
                    tvFind.text.toString(),
                    useRegex,
                    matchCase,
                    matchWholeWord,
                    tvReplace.text.toString()
                )
            }
            tvReplaceAll.setOnClickListener {
                callBack.getActiveCodeView()?.replaceAll(
                    tvFind.text.toString(),
                    useRegex,
                    matchCase,
                    matchWholeWord,
                    tvReplace.text.toString()
                )
            }
            ivMore.setOnClickListener { view ->
                PopupMenu(context, view).apply {
                    val regexItem = menu.add("正则表达式").apply {
                        isCheckable = true
                        isChecked = useRegex
                    }
                    val wordItem = menu.add("全词匹配").apply {
                        isCheckable = true
                        isChecked = matchWholeWord
                    }
                    val caseItem = menu.add("区分大小写").apply {
                        isCheckable = true
                        isChecked = matchCase
                    }
                    menu.add("关闭")
                    setOnMenuItemClickListener { item ->
                        when (item) {
                            regexItem -> useRegex = !useRegex
                            wordItem -> matchWholeWord = !matchWholeWord
                            caseItem -> matchCase = !matchCase
                            else -> {
                                binding.layoutFindReplace.root.visibility = GONE
                                return@setOnMenuItemClickListener true
                            }
                        }
                        callBack.getActiveCodeView()?.find(
                            tvFind.text.toString(),
                            useRegex,
                            matchCase,
                            matchWholeWord
                        )
                        true
                    }
                    show()
                }
            }
            tvFind.doAfterTextChanged {
                findKeyword = it.toString()
                findDebounce()
            }
        }
    }

    private fun toggleFindReplace() {
        val tmp = !binding.layoutFindReplace.root.isVisible
        binding.layoutFindReplace.root.isVisible = tmp
        if (tmp) {
            if (!findReplaceBinding.tvFind.text.isNullOrEmpty()) findReplaceBinding.tvFind.setText("")
            binding.layoutFindReplace.root.post {
                callBack.getActiveCodeView()?.clearFocus()
                findReplaceBinding.tvFind.requestFocus()
            }
        }
    }

    fun showFindReplace(keyword: String) {
        binding.layoutFindReplace.root.visibility = VISIBLE
        findKeyword = keyword
        findReplaceBinding.tvFind.requestFocus()
        findReplaceBinding.tvFind.setText(keyword)
        findReplaceBinding.tvFind.setSelection(keyword.length)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun upAdapterData() {
        activity?.lifecycleScope?.launch {
            appDb.keyboardAssistsDao.flowByType(0).catch {
                AppLog.put("键盘帮助组件获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    private fun helpAlert() {
        val ctx = activity ?: context ?: return
        val items = buildList {
            add(SelectItem(ctx.getString(R.string.assists_key_config), "keyConfig"))
            addAll(callBack.helpActions())
        }
        ctx.selector(ctx.getString(R.string.help), items) { _, selectItem, _ ->
            when (selectItem.value) {
                "keyConfig" -> activity?.showDialogFragment<KeyboardAssistsConfig>()
                else -> callBack.onHelpActionSelect(selectItem.value)
            }
        }
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<KeyboardAssist, ItemFilletTextBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemFilletTextBinding {
            return ItemFilletTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemFilletTextBinding,
            item: KeyboardAssist,
            payloads: MutableList<Any>
        ) {
            binding.run {
                textView.text = item.key
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemFilletTextBinding) {
            holder.itemView.apply {
                setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let {
                        callBack.sendText(it.value)
                    }
                }
            }
        }
    }

    interface CallBack {
        fun helpActions(): List<SelectItem<String>> = arrayListOf()
        fun onHelpActionSelect(action: String)
        fun sendText(text: String)
        fun getActiveCodeView(): io.legado.app.ui.widget.code.CodeView?
    }

}