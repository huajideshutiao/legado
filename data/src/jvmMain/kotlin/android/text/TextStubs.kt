// android.text JVM 空壳 stub: 供扩展 dex 解析 Html/Layout/Spanned/StaticLayout/TextPaint 类引用。
package android.text

import android.graphics.Paint

object Html

open class Layout {

    enum class Alignment
}

class Spanned

// StaticLayout extends Layout 对齐 Android 真实类层次: 扩展自带 Cloudflare 拦截器的字节码
// 引用两者赋值关系, JVM 类验证按 stub 层次校验, 层次不符即 VerifyError
class StaticLayout : Layout()

class TextPaint : Paint()
