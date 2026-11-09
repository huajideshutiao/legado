// android.text.method JVM 空壳 stub: 扩展在 OnBindEditTextListener 里可能直接
// setTransformationMethod(PasswordTransformationMethod.getInstance()) 等调用需要该类面;
// 接口签名对齐 android-35 真实定义 (getTransformation/onFocusChanged), getInstance 为
// 真实 static (@JvmStatic)。变换的实际呈现由桌面 Compose 的 PasswordVisualTransformation
// 承担, 本 stub 只承载引用与 ABI。仅存在于 :data 的 jvm 变体 (data/src/jvmMain)。
package android.text.method

import android.graphics.Rect
import android.view.View

/**
 * 对齐 android.text.method.TransformationMethod: 输入内容变换面。
 * [getTransformation] 的真实行为 (密码掩码/单行换行处理) 由平台呈现层承担, 本 stub
 * 只保证签名可链接。
 */
interface TransformationMethod {

    fun getTransformation(source: CharSequence?, view: View): CharSequence?

    fun onFocusChanged(
        view: View,
        sourceText: CharSequence?,
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    )
}

/** 对齐 android.text.method.PasswordTransformationMethod (getInstance 为真实 static 单例)。 */
class PasswordTransformationMethod private constructor() : TransformationMethod {

    override fun getTransformation(source: CharSequence?, view: View): CharSequence? = source

    override fun onFocusChanged(
        view: View,
        sourceText: CharSequence?,
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
    }

    companion object {

        private val sInstance: PasswordTransformationMethod = PasswordTransformationMethod()

        @JvmStatic
        fun getInstance(): PasswordTransformationMethod = sInstance
    }
}

/** 对齐 android.text.method.SingleLineTransformationMethod (getInstance 为真实 static 单例)。 */
class SingleLineTransformationMethod private constructor() : TransformationMethod {

    override fun getTransformation(source: CharSequence?, view: View): CharSequence? = source

    override fun onFocusChanged(
        view: View,
        sourceText: CharSequence?,
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
    }

    companion object {

        private val sInstance: SingleLineTransformationMethod = SingleLineTransformationMethod()

        @JvmStatic
        fun getInstance(): SingleLineTransformationMethod = sInstance
    }
}
