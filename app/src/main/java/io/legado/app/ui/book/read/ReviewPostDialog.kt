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
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import io.legado.app.R
import io.legado.app.databinding.DialogReviewPostBinding
import io.legado.app.utils.showSoftInput

/** 段评输入对话框：底部输入条，靠 SOFT_INPUT_ADJUST_RESIZE 让窗口随 IME 收缩，
 *  底部 gravity 的内容自动贴到键盘上方，和 KeyboardToolPop 的占位思路一致。 */
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
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setWindowAnimations(0)
        }
        super.onStart()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val replyPreview = arguments?.getString(KEY_REPLY_PREVIEW)
        if (!replyPreview.isNullOrBlank()) {
            val trimmed = replyPreview.take(15).let {
                if (replyPreview.length > 15) "$it…" else it
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

        var imeSeen = false
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible) {
                imeSeen = true
            } else if (imeSeen && isAdded) {
                dismissAllowingStateLoss()
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
    }
}
