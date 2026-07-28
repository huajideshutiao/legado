package io.legado.app.lib.cronet

import okhttp3.Call
import okhttp3.EventListener
import org.chromium.net.UrlRequest
import java.util.concurrent.ConcurrentHashMap

/** 将 OkHttp Call 的取消事件主动桥接到当前 Cronet UrlRequest。 */
internal object CronetRequestRegistry {
    private val requests = ConcurrentHashMap<Call, UrlRequest>()

    fun bind(call: Call, request: UrlRequest) {
        requests[call] = request
        if (call.isCanceled()) {
            clear(call, request)
            request.cancel()
        }
    }

    fun clear(call: Call, request: UrlRequest?) {
        if (request == null) {
            requests.remove(call)
        } else {
            requests.remove(call, request)
        }
    }

    fun cancel(call: Call) {
        requests.remove(call)?.cancel()
    }
}

/** OkHttp 5 在 Call.cancel() 时触发，无需定时读取 Call.isCanceled。 */
internal object CronetCancelEventListener : EventListener() {
    override fun canceled(call: Call) {
        CronetRequestRegistry.cancel(call)
    }
}
