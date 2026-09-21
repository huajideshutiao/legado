package io.legado.app.help.http

import io.legado.app.help.http.CronetProviders.get
import okhttp3.EventListener
import okhttp3.Interceptor
import kotlin.concurrent.Volatile

/**
 * Cronet 能力注入接口（shared jvmAndAndroidMain）。
 *
 * 原 app 端 [io.legado.app.help.http.HttpHelper.createOkHttpClient] 直接调用
 * `CronetCancelEventListener` / `Cronet.loader?.install()` / `Cronet.interceptor` 与
 * `AppConfig.isCronet`, 这些依赖 Cronet 二进制 (org.chromium.net) 与 Android
 * SharedPreferences, 留 app 端。HttpHelper 主体下沉 shared 后, 通过本接口
 * 反向桥接到 app 端 [io.legado.app.help.http.Cronet] object。
 *
 * - `eventListener`: 对应 `CronetCancelEventListener`, 始终注入到 OkHttpClient
 *   (无 Cronet 请求绑定时为 no-op); 桌面端不注册实现时返回 null, 跳过注入。
 * - `enabled`: 对应 `AppConfig.isCronet`, 控制是否触发 loader.install 与拦截器挂载。
 * - `installLoader()`: 对应 `Cronet.loader?.install()`, 安装 Cronet 引擎二进制,
 *   返回是否安装成功 (loader 为 null 时返回 false)。
 * - `interceptor`: 对应 `Cronet.interceptor`, 把 OkHttp 请求转 Cronet 执行。
 *
 * 模式参考 [OkHttpClientProvider] / [CookieJarBridgeHolder]。
 */
interface CronetProvider {

    /** 对应 `AppConfig.isCronet`。 */
    val enabled: Boolean

    /** 对应 `CronetCancelEventListener`（始终添加到 OkHttpClient.eventListener）。 */
    val eventListener: EventListener?

    /** 对应 `Cronet.loader?.install()`，loader 为 null 时返回 false。 */
    fun installLoader(): Boolean

    /** 对应 `Cronet.interceptor`。 */
    val interceptor: Interceptor?
}

/**
 * [CronetProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内 HttpHelper.createOkHttpClient 经 [get] 取已注册实现; 未注册时返回 null,
 * 视为该平台无 Cronet (跳过 eventListener/loader/interceptor 注入)。
 */
object CronetProviders {
    @Volatile
    private var impl: CronetProvider? = null

    fun register(impl: CronetProvider) {
        this.impl = impl
    }

    fun get(): CronetProvider? = impl
}
