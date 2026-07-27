package io.legado.app.base

import android.graphics.Color
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig

/**
 * 阅读页配置弹窗基类(纯 Compose 宿主)：透明 Window + E-Ink 无动画无遮罩 + 0.9 屏宽兜底，
 * 子类多为底部弹窗，onStart 里再用 setupAsBottomDialog 覆盖为 MATCH_PARENT+底部。
 */
abstract class BasePrefDialogFragment : DialogFragment() {

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.decorView.setPadding(0, 0, 0, 0)
            it.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (view != null) {
                it.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            }
            val attr = it.attributes
            if (AppConfig.isEInkMode) {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
            } else {
                attr.windowAnimations = R.style.Animation_Dialog
            }
            it.attributes = attr
            val dm = resources.displayMetrics
            val width = (dm.widthPixels * 0.9).toInt().coerceAtMost((800 * dm.density).toInt())
            it.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            //在每个add事务前增加一个remove事务，防止连续的add
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }
}
