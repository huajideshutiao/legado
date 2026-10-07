// android.os 线程 stub: 扩展在动作型偏好里经 Handler(Looper.getMainLooper()) 派发 UI 更新
// (invokestatic Looper.getMainLooper + invokespecial Handler.<init> + invokevirtual post)。
// 桌面端无主线程 Looper, post/postDelayed 直接在当前线程执行 (丢弃任务会让动作静默不生效)。
package android.os

class Looper private constructor() {

    companion object {

        private val mainLooper = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = mainLooper

        @JvmStatic
        fun myLooper(): Looper = mainLooper

        @JvmStatic
        fun prepare() {
        }

        @JvmStatic
        fun prepareMainLooper() {
        }

        @JvmStatic
        fun loop() {
        }
    }
}

open class Handler {

    constructor()

    constructor(looper: Looper?)

    open fun post(r: Runnable): Boolean {
        r.run()
        return true
    }

    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        r.run()
        return true
    }

    open fun postDelayed(r: Runnable, token: Any?, delayMillis: Long): Boolean {
        r.run()
        return true
    }

    open fun removeCallbacks(r: Runnable) {
    }

    open fun removeCallbacks(r: Runnable, token: Any?) {
    }
}
