package com.github.catvod

import android.content.Context
import java.lang.ref.WeakReference

/**
 * TVBox 壳上下文持有者 (签名对齐 FongMi catvod 模块; 宿主在装载前 Init.set(appContext))。
 *
 * Context 类型是 jar 契约面 (jar 内以 Init.context() 回取并透传): Android 为真应用上下文,
 * 桌面为 :data jvmMain 的 stub 实例。
 */
object Init {

    private var context: WeakReference<Context?>? = null

    @JvmStatic
    fun set(context: Context?) {
        this.context = WeakReference(context)
    }

    @JvmStatic
    fun context(): Context? = this.context?.get()
}
