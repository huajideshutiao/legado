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
import io.legado.app.utils.activity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.windowSize
import kotlinx.coroutines.CoroutineScope
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

    private lateinit var scope: CoroutineScope
    private lateinit var rootView: View
    private lateinit var callBack: CallBack

    private val binding = PopupKeyboardToolBinding.inflate(LayoutInflater.from(context), this, true)
    private val findReplaceBinding = ViewFindReplaceBinding.bind(binding.layoutFindReplace.root)
    private val adapter = Adapter(context)
    private var mIsSoftKeyBoardShowing = false
    var initialPadding = 0
    var isAutoPadding = true
    private var dismissRunnable: Runnable? = null

    init {
        initRecyclerView()
        isVisible = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    fun setInterface(
        scope: CoroutineScope,
        rootView: View,
        callBack: CallBack
    ) {
        this.scope = scope
        this.rootView = rootView
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
                dismissRunnable = Runnable {
                    if (!mIsSoftKeyBoardShowing) {
                        isVisible = false
                    }
                }
                binding.root.postDelayed(dismissRunnable, 200)
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
                if (::callBack.isInitialized) {
                    callBack.getActiveCodeView()?.find(
                        tvFind.editText?.text.toString(),
                        callBack.useRegex,
                        callBack.matchCase,
                        callBack.matchWholeWord,
                        true
                    )
                }
            }
            tvPrev.setOnClickListener {
                if (::callBack.isInitialized) {
                    callBack.getActiveCodeView()?.find(
                        tvFind.editText?.text.toString(),
                        callBack.useRegex,
                        callBack.matchCase,
                        callBack.matchWholeWord,
                        false
                    )
                }
            }
            tvDoReplace.setOnClickListener {
                if (::callBack.isInitialized) {
                    callBack.getActiveCodeView()?.replace(
                        tvFind.editText?.text.toString(),
                        callBack.useRegex,
                        callBack.matchCase,
                        callBack.matchWholeWord,
                        tvReplace.editText?.text.toString()
                    )
                }
            }
            tvReplaceAll.setOnClickListener {
                if (::callBack.isInitialized) {
                    callBack.getActiveCodeView()?.replaceAll(
                        tvFind.editText?.text.toString(),
                        callBack.useRegex,
                        callBack.matchCase,
                        callBack.matchWholeWord,
                        tvReplace.editText?.text.toString()
                    )
                }
            }
            ivMore.setOnClickListener { view ->
                if (::callBack.isInitialized) {
                    val popup = PopupMenu(activity ?: context, view)
                    val regexItem = popup.menu.add("正则表达式").apply { isCheckable = true; isChecked = callBack.useRegex }
                    val wordItem = popup.menu.add("全词匹配").apply { isCheckable = true; isChecked = callBack.matchWholeWord }
                    val caseItem = popup.menu.add("区分大小写").apply { isCheckable = true; isChecked = callBack.matchCase }
                    popup.menu.add("关闭")

            popup.setOnMenuItemClickListener { item ->
                when (item) {
                    regexItem -> {
                        callBack.useRegex = !callBack.useRegex
                        true
                    }
                    wordItem -> {
                        callBack.matchWholeWord = !callBack.matchWholeWord
                        true
                    }
                    caseItem -> {
                        callBack.matchCase = !callBack.matchCase
                        true
                    }
                    else -> {
                        binding.layoutFindReplace.root.visibility = GONE
                        true
                    }
                }
            }
                    popup.show()
                }
            }
        }
    }

    private fun toggleFindReplace() {
        if (binding.layoutFindReplace.root.isVisible) {
            binding.layoutFindReplace.root.visibility = GONE
        } else {
            binding.layoutFindReplace.root.visibility = VISIBLE
            binding.layoutFindReplace.root.post {
                findReplaceBinding.tvFind.requestFocus()
                if (::callBack.isInitialized) {
                    callBack.getActiveCodeView()?.clearFocus()
                }
            }
        }
    }

    fun showFindReplace(keyword: String) {
        binding.layoutFindReplace.root.visibility = VISIBLE
        findReplaceBinding.tvFind.editText?.setText(keyword)
        binding.layoutFindReplace.root.post {
            findReplaceBinding.tvFind.requestFocus()
            if (::callBack.isInitialized) {
                callBack.getActiveCodeView()?.clearFocus()
            }
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun upAdapterData() {
        if (!::scope.isInitialized) return
        scope.launch {
            appDb.keyboardAssistsDao.flowByType(0).catch {
                AppLog.put("键盘帮助组件获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    private fun helpAlert() {
        if (!::callBack.isInitialized) return
        val alertContext = activity ?: context
        val items = arrayListOf(
            SelectItem(alertContext.getString(R.string.assists_key_config), "keyConfig")
        )
        items.addAll(callBack.helpActions())
        alertContext.selector(alertContext.getString(R.string.help), items) { _, selectItem, _ ->
            when (selectItem.value) {
                "keyConfig" -> config()
                else -> callBack.onHelpActionSelect(selectItem.value)
            }
        }
    }

    private fun config() {
        activity?.showDialogFragment<KeyboardAssistsConfig>()
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
                    if (::callBack.isInitialized) {
                        getItemByLayoutPosition(holder.layoutPosition)?.let {
                            callBack.sendText(it.value)
                        }
                    }
                }
            }
        }
    }

    interface CallBack {

        fun helpActions(): List<SelectItem<String>> = arrayListOf()

        fun onHelpActionSelect(action: String)

        fun sendText(text: String)

        var useRegex: Boolean
        var matchCase: Boolean
        var matchWholeWord: Boolean

        fun getActiveCodeView(): io.legado.app.ui.widget.code.CodeView?

    }

}