package io.legado.app.help.toast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * [Toaster] 的 Android actual 实现。
 *
 * 委托 [android.widget.Toast.makeText] + 主线程 Handler, 行为对齐 app 端
 * `Context.toastOnUi` (见 `app/src/main/java/io/legado/app/utils/ToastUtils.kt`)。
 *
 * # 设计要点
 * - shared androidMain 不依赖 splitties, 通过构造函数接收 [Context]
 *   (app 端注册时传 `appCtx`, 见 [registerAndroidToaster])
 * - 调用线程不限: 内部用主线程 Handler.post 确保 Toast 在 UI 线程显示
 * - 持有单个 Toast 实例, 显示前 cancel 上一个, 避免排队堆叠 (与 app 端一致)
 * - [toast] 的 resId 参数当字符串显示 (与桌面端行为一致); 如需资源名解析,
 *   调用方应自行 `context.getString(R.string.xxx)` 后传入 [toast] 的 String 重载
 *
 * 模式参考 `registerAndroidMediaNotificationProvider` (app 端 help/media/)。
 */
class AndroidToaster(
    private val context: Context,
) : Toaster {

    /** 主线程 Handler, 用于切到 UI 线程显示 Toast。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前 Toast 实例 (volatile 保证多线程可见性)。 */
    @Volatile
    private var current: Toast? = null

    override fun toast(message: String) {
        showOnUiThread(message, Toast.LENGTH_SHORT)
    }

    override fun toastLong(message: String) {
        showOnUiThread(message, Toast.LENGTH_LONG)
    }

    /** 在 UI 线程显示 Toast, 取消上一个避免堆叠。 */
    private fun showOnUiThread(message: String, duration: Int) {
        mainHandler.post {
            kotlin.runCatching {
                current?.cancel()
                val toast = Toast.makeText(context, message, duration)
                current = toast
                toast.show()
            }
        }
    }
}

/**
 * 安卓宿主启动早期注册 [Toaster] 的 actual 实现。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用 `Toasters.get()` 之前。
 *
 * @param context 任意 Context (推荐传 `appCtx`), 内部只用做 Toast.makeText 的
 *                ApplicationContext, 不持有 Activity 引用
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerAndroidToaster(context: Context) {
    Toasters.register(AndroidToaster(context.applicationContext))
}
