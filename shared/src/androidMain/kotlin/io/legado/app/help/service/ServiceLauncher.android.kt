package io.legado.app.help.service

import android.content.Context
import android.content.Intent
import io.legado.app.constant.IntentAction

/**
 * [ServiceLauncher] 的 Android actual 实现。
 *
 * 通过反射 + Intent 启动 app 端 Service, 行为对齐 app 端
 * `Context.startService<T>(...)` 扩展 (见 `app/src/main/java/io/legado/app/utils/ContextExtensions.kt`)。
 *
 * # 设计要点
 * - shared androidMain 不能直接 import app 模块的 Service 类 (app 依赖 shared,
 *   反向依赖会成环), 用 `Class.forName` 反射拿 Service Class, 运行时由 app 模块
 *   类加载器提供
 * - shared androidMain 不依赖 splitties, 通过构造函数接收 [Context]
 * - 启动参数对齐 app 端 Service 的 IntentAction + extras 约定:
 *   - CacheBookService: action=start, extras bookUrl/start/end (Int)
 *   - UpdateBookService: action=start/stop
 *   - DownloadService: action=start, extras url/fileName
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class AndroidServiceLauncher(
    private val context: Context,
) : ServiceLauncher {

    override fun startCacheBookService(bookUrl: String, start: Int, end: Int) {
        val intent = createServiceIntent(
            "io.legado.app.service.CacheBookService",
            IntentAction.start
        ).apply {
            putExtra("bookUrl", bookUrl)
            putExtra("start", start)
            putExtra("end", end)
        }
        context.startService(intent)
    }

    override fun stopCacheBookService() {
        val intent = createServiceIntent(
            "io.legado.app.service.CacheBookService",
            IntentAction.stop
        )
        context.startService(intent)
    }

    override fun removeCacheBookService(bookUrl: String) {
        val intent = createServiceIntent(
            "io.legado.app.service.CacheBookService",
            IntentAction.remove
        ).apply {
            putExtra("bookUrl", bookUrl)
        }
        context.startService(intent)
    }

    override fun startUpdateBookService() {
        val intent = createServiceIntent(
            "io.legado.app.service.UpdateBookService",
            IntentAction.start
        )
        context.startService(intent)
    }

    override fun stopUpdateBookService() {
        val intent = createServiceIntent(
            "io.legado.app.service.UpdateBookService",
            IntentAction.stop
        )
        context.startService(intent)
    }

    override fun startDownloadService(url: String, fileName: String) {
        val intent = createServiceIntent(
            "io.legado.app.service.DownloadService",
            IntentAction.start
        ).apply {
            putExtra("url", url)
            putExtra("fileName", fileName)
        }
        context.startService(intent)
    }

    /**
     * 反射构造 Service Intent。
     *
     * @param className app 端 Service 全限定名 (如 "io.legado.app.service.CacheBookService")
     * @param action IntentAction 常量 (见 commonMain IntentAction)
     */
    private fun createServiceIntent(className: String, action: String): Intent {
        val clazz = Class.forName(className)
        val intent = Intent(context, clazz)
        intent.action = action
        return intent
    }
}

/**
 * 安卓宿主启动早期注册 [ServiceLauncher] 的 actual 实现。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用 `ServiceLaunchers.get()` 之前。
 *
 * @param context 任意 Context (推荐传 `appCtx`), 用于 startService
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerAndroidServiceLauncher(context: Context) {
    ServiceLaunchers.register(AndroidServiceLauncher(context.applicationContext))
}
