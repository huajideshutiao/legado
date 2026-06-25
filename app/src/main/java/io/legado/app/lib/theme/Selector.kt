package io.legado.app.lib.theme

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.annotation.ColorInt
import androidx.annotation.Dimension
import androidx.annotation.IntDef

@Suppress("unused")
object Selector {
    fun shapeBuild(): ShapeSelector {
        return ShapeSelector()
    }

    fun colorBuild(): ColorSelector {
        return ColorSelector()
    }

    /**
     * 形状ShapeSelector
     *
     * @author hjy
     * created at 2017/12/11 22:26
     */
    class ShapeSelector {

        private var mShape: Int = 0               //the shape of background
        private var mDefaultBgColor: Int = 0      //default background color
        private var mDisabledBgColor: Int = 0     //state_enabled = false
        private var mPressedBgColor: Int = 0      //state_pressed = true
        private var mSelectedBgColor: Int = 0     //state_selected = true
        private var mFocusedBgColor: Int = 0      //state_focused = true
        private var mCheckedBgColor: Int = 0      //state_checked = true
        private var mStrokeWidth: Int = 0         //stroke width in pixel
        private var mDefaultStrokeColor: Int = 0  //default stroke color
        private var mDisabledStrokeColor: Int = 0 //state_enabled = false
        private var mPressedStrokeColor: Int = 0  //state_pressed = true
        private var mSelectedStrokeColor: Int = 0 //state_selected = true
        private var mFocusedStrokeColor: Int = 0  //state_focused = true
        private var mCheckedStrokeColor: Int = 0  //state_checked = true
        private var mCornerRadius: Int = 0        //corner radius

        private var hasSetDisabledBgColor = false
        private var hasSetPressedBgColor = false
        private var hasSetSelectedBgColor = false
        private val hasSetFocusedBgColor = false
        private var hasSetCheckedBgColor = false

        private var hasSetDisabledStrokeColor = false
        private var hasSetPressedStrokeColor = false
        private var hasSetSelectedStrokeColor = false
        private var hasSetFocusedStrokeColor = false
        private var hasSetCheckedStrokeColor = false

        @IntDef(
            GradientDrawable.RECTANGLE,
            GradientDrawable.OVAL,
            GradientDrawable.LINE,
            GradientDrawable.RING
        )
        private annotation class Shape

        init {
            //initialize default values
            mShape = GradientDrawable.RECTANGLE
            mDefaultBgColor = Color.TRANSPARENT
            mDisabledBgColor = Color.TRANSPARENT
            mPressedBgColor = Color.TRANSPARENT
            mSelectedBgColor = Color.TRANSPARENT
            mFocusedBgColor = Color.TRANSPARENT
            mStrokeWidth = 0
            mDefaultStrokeColor = Color.TRANSPARENT
            mDisabledStrokeColor = Color.TRANSPARENT
            mPressedStrokeColor = Color.TRANSPARENT
            mSelectedStrokeColor = Color.TRANSPARENT
            mFocusedStrokeColor = Color.TRANSPARENT
            mCornerRadius = 0
        }

        fun setShape(@Shape shape: Int): ShapeSelector {
            mShape = shape
            return this
        }

        fun setDefaultBgColor(@ColorInt color: Int): ShapeSelector {
            mDefaultBgColor = color
            if (!hasSetDisabledBgColor)
                mDisabledBgColor = color
            if (!hasSetPressedBgColor)
                mPressedBgColor = color
            if (!hasSetSelectedBgColor)
                mSelectedBgColor = color
            if (!hasSetFocusedBgColor)
                mFocusedBgColor = color
            return this
        }

        fun setDisabledBgColor(@ColorInt color: Int): ShapeSelector {
            mDisabledBgColor = color
            hasSetDisabledBgColor = true
            return this
        }

        fun setPressedBgColor(@ColorInt color: Int): ShapeSelector {
            mPressedBgColor = color
            hasSetPressedBgColor = true
            return this
        }

        fun setSelectedBgColor(@ColorInt color: Int): ShapeSelector {
            mSelectedBgColor = color
            hasSetSelectedBgColor = true
            return this
        }

        fun setFocusedBgColor(@ColorInt color: Int): ShapeSelector {
            mFocusedBgColor = color
            hasSetPressedBgColor = true
            return this
        }

        fun setCheckedBgColor(@ColorInt color: Int): ShapeSelector {
            mCheckedBgColor = color
            hasSetCheckedBgColor = true
            return this
        }

        fun setStrokeWidth(@Dimension width: Int): ShapeSelector {
            mStrokeWidth = width
            return this
        }

        fun setDefaultStrokeColor(@ColorInt color: Int): ShapeSelector {
            mDefaultStrokeColor = color
            if (!hasSetDisabledStrokeColor)
                mDisabledStrokeColor = color
            if (!hasSetPressedStrokeColor)
                mPressedStrokeColor = color
            if (!hasSetSelectedStrokeColor)
                mSelectedStrokeColor = color
            if (!hasSetFocusedStrokeColor)
                mFocusedStrokeColor = color
            return this
        }

        fun setDisabledStrokeColor(@ColorInt color: Int): ShapeSelector {
            mDisabledStrokeColor = color
            hasSetDisabledStrokeColor = true
            return this
        }

        fun setPressedStrokeColor(@ColorInt color: Int): ShapeSelector {
            mPressedStrokeColor = color
            hasSetPressedStrokeColor = true
            return this
        }

        fun setSelectedStrokeColor(@ColorInt color: Int): ShapeSelector {
            mSelectedStrokeColor = color
            hasSetSelectedStrokeColor = true
            return this
        }

        fun setCheckedStrokeColor(@ColorInt color: Int): ShapeSelector {
            mCheckedStrokeColor = color
            hasSetCheckedStrokeColor = true
            return this
        }

        fun setFocusedStrokeColor(@ColorInt color: Int): ShapeSelector {
            mFocusedStrokeColor = color
            hasSetFocusedStrokeColor = true
            return this
        }

        fun setCornerRadius(@Dimension radius: Int): ShapeSelector {
            mCornerRadius = radius
            return this
        }

        fun create(): StateListDrawable {
            val selector = StateListDrawable()

            //enabled = false
            if (hasSetDisabledBgColor || hasSetDisabledStrokeColor) {
                val disabledShape = getItemShape(
                    mShape, mCornerRadius,
                    mDisabledBgColor, mStrokeWidth, mDisabledStrokeColor
                )
                selector.addState(intArrayOf(-android.R.attr.state_enabled), disabledShape)
            }

            //pressed = true
            if (hasSetPressedBgColor || hasSetPressedStrokeColor) {
                val pressedShape = getItemShape(
                    mShape, mCornerRadius,
                    mPressedBgColor, mStrokeWidth, mPressedStrokeColor
                )
                selector.addState(intArrayOf(android.R.attr.state_pressed), pressedShape)
            }

            //selected = true
            if (hasSetSelectedBgColor || hasSetSelectedStrokeColor) {
                val selectedShape = getItemShape(
                    mShape, mCornerRadius,
                    mSelectedBgColor, mStrokeWidth, mSelectedStrokeColor
                )
                selector.addState(intArrayOf(android.R.attr.state_selected), selectedShape)
            }

            //focused = true
            if (hasSetFocusedBgColor || hasSetFocusedStrokeColor) {
                val focusedShape = getItemShape(
                    mShape, mCornerRadius,
                    mFocusedBgColor, mStrokeWidth, mFocusedStrokeColor
                )
                selector.addState(intArrayOf(android.R.attr.state_focused), focusedShape)
            }

            //checked = true
            if (hasSetCheckedBgColor || hasSetCheckedStrokeColor) {
                val checkedShape = getItemShape(
                    mShape, mCornerRadius,
                    mCheckedBgColor, mStrokeWidth, mCheckedStrokeColor
                )
                selector.addState(intArrayOf(android.R.attr.state_checked), checkedShape)
            }

            //default
            val defaultShape = getItemShape(
                mShape, mCornerRadius,
                mDefaultBgColor, mStrokeWidth, mDefaultStrokeColor
            )
            selector.addState(intArrayOf(), defaultShape)

            return selector
        }

        private fun getItemShape(
            shape: Int, cornerRadius: Int,
            solidColor: Int, strokeWidth: Int, strokeColor: Int
        ): GradientDrawable {
            val drawable = GradientDrawable()
            drawable.shape = shape
            drawable.setStroke(strokeWidth, strokeColor)
            drawable.cornerRadius = cornerRadius.toFloat()
            drawable.setColor(solidColor)
            return drawable
        }
    }

    /**
     * 颜色ColorSelector
     *
     * @author hjy
     * created at 2017/12/11 22:26
     */
    class ColorSelector {

        private var mDefaultColor: Int = 0
        private var mDisabledColor: Int = 0
        private var mPressedColor: Int = 0
        private var mSelectedColor: Int = 0
        private var mFocusedColor: Int = 0
        private var mCheckedColor: Int = 0

        private var hasSetDisabledColor = false
        private var hasSetPressedColor = false
        private var hasSetSelectedColor = false
        private var hasSetFocusedColor = false
        private var hasSetCheckedColor = false

        init {
            mDefaultColor = Color.BLACK
            mDisabledColor = Color.GRAY
            mPressedColor = Color.BLACK
            mSelectedColor = Color.BLACK
            mFocusedColor = Color.BLACK
        }

        fun setDefaultColor(@ColorInt color: Int): ColorSelector {
            mDefaultColor = color
            if (!hasSetDisabledColor)
                mDisabledColor = color
            if (!hasSetPressedColor)
                mPressedColor = color
            if (!hasSetSelectedColor)
                mSelectedColor = color
            if (!hasSetFocusedColor)
                mFocusedColor = color
            return this
        }

        fun setDisabledColor(@ColorInt color: Int): ColorSelector {
            mDisabledColor = color
            hasSetDisabledColor = true
            return this
        }

        fun setPressedColor(@ColorInt color: Int): ColorSelector {
            mPressedColor = color
            hasSetPressedColor = true
            return this
        }

        fun setSelectedColor(@ColorInt color: Int): ColorSelector {
            mSelectedColor = color
            hasSetSelectedColor = true
            return this
        }

        fun setFocusedColor(@ColorInt color: Int): ColorSelector {
            mFocusedColor = color
            hasSetFocusedColor = true
            return this
        }

        fun setCheckedColor(@ColorInt color: Int): ColorSelector {
            mCheckedColor = color
            hasSetCheckedColor = true
            return this
        }

        fun create(): ColorStateList {
            val colors = intArrayOf(
                if (hasSetDisabledColor) mDisabledColor else mDefaultColor,
                if (hasSetPressedColor) mPressedColor else mDefaultColor,
                if (hasSetSelectedColor) mSelectedColor else mDefaultColor,
                if (hasSetFocusedColor) mFocusedColor else mDefaultColor,
                if (hasSetCheckedColor) mCheckedColor else mDefaultColor,
                mDefaultColor
            )
            val states = arrayOfNulls<IntArray>(6)
            states[0] = intArrayOf(-android.R.attr.state_enabled)
            states[1] = intArrayOf(android.R.attr.state_pressed)
            states[2] = intArrayOf(android.R.attr.state_selected)
            states[3] = intArrayOf(android.R.attr.state_focused)
            states[4] = intArrayOf(android.R.attr.state_checked)
            states[5] = intArrayOf()
            return ColorStateList(states, colors)
        }
    }
}
