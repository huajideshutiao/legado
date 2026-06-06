package io.legado.app.ui.book.read

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import io.legado.app.R
import io.legado.app.databinding.DialogReviewPostBinding
import io.legado.app.utils.showSoftInput

/** 段评输入对话框：底部输入条，模仿 KeyboardToolPop 的 IME 占位机制。 */
class ReviewPostDialog() : DialogFragment() {

    constructor(replyPreview: String? = null) : this() {
        arguments = Bundle().apply {
            replyPreview?.let { putString(KEY_REPLY_PREVIEW, it) }
        }
    }

    private var _binding: DialogReviewPostBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogReviewPostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.32f)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setWindowAnimations(0)
        }
        super.onStart()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reserveImeSpace(view)
        val replyPreview = arguments?.getString(KEY_REPLY_PREVIEW)
        if (!replyPreview.isNullOrBlank()) {
            val trimmed = replyPreview.take(20).let {
                if (replyPreview.length > 20) "$it…" else it
            }
            binding.etInput.hint = getString(R.string.reply_review) + ": " + trimmed
        }

        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.btnPost.isEnabled = !s.isNullOrBlank()
            }
        })
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(); true
            } else false
        }
        binding.btnPost.setOnClickListener { submit() }

        binding.etInput.requestFocus()
        view.postDelayed({
            if (isAdded) binding.etInput.showSoftInput()
        }, IME_DELAY_MS)
    }

    // 对齐 KeyboardToolPop：监听 IME insets 拿 imeHeight 当底部占位（margin 不拉 bg）。
    // 没缓存时先用屏高 35% 兜底，避免首次开窗 IME 起来时撞透；之后 snap 到精确值。
    private fun reserveImeSpace(view: View) {
        val initial = cachedImeHeightPx.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels * 35 / 100)
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = initial }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime > 0 && ime != cachedImeHeightPx) {
                cachedImeHeightPx = ime
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = ime }
            }
            insets
        }
    }

    private fun submit() {
        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_CONTENT to text))
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY = "reviewPost"
        const val KEY_CONTENT = "content"
        private const val KEY_REPLY_PREVIEW = "replyPreview"
        private const val IME_DELAY_MS = 120L
        private var cachedImeHeightPx: Int = 0
    }
}
