package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.view.animation.Animation
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ConstraintModify
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.modifyBegin

abstract class BaseReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var canShowMenu: Boolean = false
    var isMenuOutAnimating = false

    protected val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    protected val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    protected val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    protected val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }

    protected fun initAnimation(
        menuInListener: Animation.AnimationListener,
        menuOutListener: Animation.AnimationListener
    ) {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    protected fun upBrightnessVwPos(rootView: ConstraintLayout) {
        if (AppConfig.brightnessVwPos) {
            rootView.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.LEFT)
                .rightToRightOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        } else {
            rootView.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.RIGHT)
                .leftToLeftOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        }
    }
}
