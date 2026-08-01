package io.legado.app.help.http

import io.legado.app.help.config.AppConfig
import io.legado.app.lib.cronet.CronetCancelEventListener
import okhttp3.EventListener
import okhttp3.Interceptor

/**
 * Android 端 [CronetProvider] 实现: 桥接 app 端 [Cronet] object 与 [AppConfig.isCronet]。
 *
 * HttpHelper 主体下沉 shared 后, shared 不能直接引用 [Cronet] (依赖 cronet 二进制)
 * 与 [AppConfig] (依赖 SharedPreferences + appCtx), 通过 [CronetProviders] 注入本实现。
 *
 * - [enabled] 读 `AppConfig.isCronet` (cachedPref, 用户切换后立即生效)。
 * - [eventListener] 始终返回 [CronetCancelEventListener], 与原版 HttpHelper 行为一致
 *   (无 Cronet 请求绑定时为 no-op); 即便 [enabled] 为 false 也注入。
 * - [installLoader] 委托 `Cronet.loader?.install()`, loader 为 null 时返回 false。
 * - [interceptor] 委托 `Cronet.interceptor` (lazy 内部已封装 cookieJar)。
 */
object AndroidCronetProvider : CronetProvider {

    override val enabled: Boolean
        get() = AppConfig.isCronet

    override val eventListener: EventListener?
        get() = CronetCancelEventListener

    override fun installLoader(): Boolean = Cronet.loader?.install() == true

    override val interceptor: Interceptor?
        get() = Cronet.interceptor
}

/**
 * 注册 Android 端 Cronet provider。
 *
 * 时机: App.onCreate 早期 (任何 OkHttpClient 创建之前)。原 HttpHelper.okHttpClient
 * 首次访问触发 lazy createOkHttpClient 时, 已通过 [CronetProviders.get] 取得本实现。
 */
fun registerAndroidCronetProvider() {
    CronetProviders.register(AndroidCronetProvider)
}
