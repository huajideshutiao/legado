package io.legado.app.ui.widget.recycler

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorRes
import io.legado.app.R
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.neutralButton
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

class LoadMoreView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val binding = ViewLoadMoreBinding.inflate(LayoutInflater.from(context), this)
    private var errorMsg = ""

    private var onClickListener: OnClickListener? = null

    var isLoading = false
        private set

    var hasMore = true
        private set

    init {
        super.setOnClickListener {
            if (!showErrorDialog(it)) {
                onClickListener?.onClick(it)
            }
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        this.onClickListener = l
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        layoutParams.width = LayoutParams.MATCH_PARENT
    }

    fun startLoad() {
        isLoading = true
        binding.tvText.invisible()
        // CircularProgressIndicator 需用 show() 启动 indeterminate 动画，
        // 直接 setVisibility(VISIBLE) 控件可见但不转
        binding.rotateLoading.show()
    }

    fun stopLoad() {
        isLoading = false
        // CircularProgressIndicator 需用 hide() 停止 indeterminate 动画；
        // tv_text 始终 invisible 保留布局空间，hide() 设 GONE 不影响高度
        binding.rotateLoading.hide()
    }

    fun hasMore() {
        errorMsg = ""
        hasMore = true
        startLoad()
    }

    fun noMore(msg: String? = null) {
        stopLoad()
        errorMsg = ""
        hasMore = false
        if (msg != null) {
            binding.tvText.text = msg
        } else {
            binding.tvText.setText(R.string.bottom_line)
        }
        binding.tvText.visible()
    }

    fun error(msg: String?, text: String = "") {
        stopLoad()
        hasMore = false
        errorMsg = msg ?: ""
        binding.tvText.text =
            text.ifEmpty { context.getString(R.string.error_load_msg, "点击查看详情") }
        binding.tvText.visible()
    }

    fun setLoadingColor(@ColorRes color: Int) {
        // Material CircularProgressIndicator 用 setIndicatorColor 替代原 loadingColor 属性
        binding.rotateLoading.setIndicatorColor(context.getCompatColor(color))
    }

    fun setLoadingTextColor(@ColorRes color: Int) {
        binding.tvText.setTextColor(context.getCompatColor(color))
    }

    private fun showErrorDialog(view: View): Boolean {
        if (errorMsg.isBlank()) {
            return false
        }
        context.alert(R.string.error) {
            setMessage(errorMsg)
            if (onClickListener != null) {
                neutralButton(R.string.retry) {
                    onClickListener?.onClick(view)
                }
            }
        }
        return true
    }

}
