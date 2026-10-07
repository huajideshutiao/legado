// android.widget.Toast JVM 空壳 stub: 扩展在动作型偏好里提示结果
// (invokestatic Toast.makeText + invokevirtual show), 桌面端无 UI 层可提示, show 为空操作。
package android.widget

import android.content.Context

class Toast private constructor() {

    fun show() {
    }

    fun cancel() {
    }

    fun setDuration(duration: Int): Toast = this

    fun setText(text: CharSequence?): Toast = this

    companion object {

        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast = Toast()

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast = Toast()
    }
}
