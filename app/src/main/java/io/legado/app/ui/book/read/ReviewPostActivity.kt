package io.legado.app.ui.book.read

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.animation.AccelerateInterpolator
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import io.legado.app.R
import io.legado.app.databinding.ActivityReviewPostBinding
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat

/** 段评输入面板。独立 Activity 模拟 BottomSheet,避免叠在 ReviewListDialog 上时
 *  双层 BottomSheetDialogFragment 在 IME inset/动画时序上互相干扰。
 *
 *  sheet 的 translationY = exitY + imeY:imeY = -ime.bottom 让 sheet 跟随 IME 顶,
 *  入场即天然 slide-in;exitY 由退场 animator 驱动滑到屏外。
 */
class ReviewPostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewPostBinding
    private var imeWasVisible = false
    private var dismissed = false
    private var exitY = 0f
    private var imeY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        binding = ActivityReviewPostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val replyPreview = intent.getStringExtra(EXTRA_REPLY_PREVIEW)
        if (!replyPreview.isNullOrBlank()) {
            val trimmed = replyPreview.take(15).let {
                if (replyPreview.length > 15) "$it…" else it
            }
            binding.etInput.hint = getString(R.string.reply_review_to, trimmed)
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
        binding.root.setOnClickListener { finish() }
        binding.etInput.requestFocus()

        binding.root.alpha = 0f
        binding.root.animate()
            .alpha(1f)
            .setDuration(ANIM_DURATION)
            .start()

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    if (!dismissed) {
                        imeY = -insets.getInsets(WindowInsetsCompat.Type.ime()).bottom.toFloat()
                        applySheetTranslation()
                    }
                    return insets
                }
            }
        )
        binding.root.setOnApplyWindowInsetsListenerCompat { _, insets ->
            if (!dismissed) {
                imeY = -insets.getInsets(WindowInsetsCompat.Type.ime()).bottom.toFloat()
                applySheetTranslation()
            }
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible) {
                imeWasVisible = true
            } else if (imeWasVisible && !dismissed) {
                // 返回键路径:系统先收起 IME 再触发 finish;dismissed 时跳过,避免 exit 动画里
                // 主动 hideSoftInput 再触发一次。
                finish()
            }
            insets
        }
    }

    private fun applySheetTranslation() {
        binding.sheet.translationY = exitY + imeY
    }

    private fun submit() {
        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        setResult(RESULT_OK, Intent().putExtra(RESULT_CONTENT, text))
        finish()
    }

    /** 所有关闭路径都收敛到这里走退场动画。dismissed 后冻结 imeY,改由 exit animator
     *  单独驱动 sheet 到屏外 (targetExitY 补偿 imeY 让终值 = sheet.height)。 */
    override fun finish() {
        if (dismissed) return
        dismissed = true
        binding.etInput.hideSoftInput()
        binding.scrim.animate()
            .alpha(0f)
            .setDuration(ANIM_DURATION)
            .start()
        val targetExitY = binding.sheet.height.toFloat() - imeY
        ValueAnimator.ofFloat(exitY, targetExitY).apply {
            duration = ANIM_DURATION
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                exitY = it.animatedValue as Float
                applySheetTranslation()
            }
            doOnEnd {
                super@ReviewPostActivity.finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            start()
        }
    }

    companion object {
        const val EXTRA_REPLY_PREVIEW = "replyPreview"
        const val RESULT_CONTENT = "content"
        private const val ANIM_DURATION = 200L
    }
}
