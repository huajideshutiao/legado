package io.legado.app.lib.permission

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.Stack

internal object RequestManager : OnPermissionsResultCallback {

    private var requests: Stack<Request>? = null
    private var request: Request? = null

    private val handler = Handler(Looper.getMainLooper())

    private val requestRunnable = Runnable {
        request?.start()
    }

    init {
        RequestPlugins.setOnPermissionsResultCallback(this)
    }

    fun pushRequest(request: Request?) {
        if (request == null) return

        if (requests == null) {
            requests = Stack()
        }

        requests?.let {
            val index = it.indexOf(request)
            if (index >= 0) {
                val to = it.size - 1
                if (index != to) {
                    Collections.swap(it, index, to)
                }
            } else {
                it.push(request)
            }

            // 上一条请求真实回传 (granted/denied/error) 清场后才轮到下一条
            if (!it.empty() && this.request == null) {
                this.request = it.pop()
                handler.post(requestRunnable)
            }
        }
    }

    /**
     * 撤销一条权限请求 (主线程): 未启动的排队项移除; 已进入权限页面的保持回传占用至真实返回,
     * 仅弃其 granted/denied/error 回调。
     */
    fun cancelRequest(request: Request?) {
        if (request == null) return
        handler.post {
            requests?.remove(request)
            if (this.request === request) {
                this.request?.clear()
            }
        }
    }

    private fun startNextRequest() {
        request?.clear()
        request = null

        requests?.let {
            request = if (it.empty()) null else it.pop()
            request?.let { handler.post(requestRunnable) }
        }
    }

    override fun onPermissionsGranted() {
        startNextRequest()
    }

    override fun onPermissionsDenied(deniedPermissions: Array<String>?) {
        startNextRequest()
    }

    override fun onError(e: Exception) {
        startNextRequest()
    }

}
