package io.legado.app.lib.permission

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.Stack

internal object RequestManager : OnPermissionsResultCallback {

    private var requests: Stack<Request>? = null
    private var request: Request? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 启动一个已分配的请求: 已取消且未起跑的立即让出并推进, 已起跑才等真实回传。 */
    private fun postStart(assigned: Request) {
        handler.post {
            if (request !== assigned) return@post
            if (assigned.isCancelled) {
                request = null
                assigned.clear()
                startNextRequest()
                return@post
            }
            assigned.markStarted()
            assigned.start()
        }
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
                val assigned = it.pop()
                this.request = assigned
                postStart(assigned)
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
                if (request.isStarted) {
                    // 已起跑: 只弃消费回调, 通道占用保留到真实回传
                    request.clear()
                } else {
                    // 未起跑: 不会再有系统回传, 立即让出当前位并推进
                    this.request = null
                    request.clear()
                    startNextRequest()
                }
            }
        }
    }

    private fun startNextRequest() {
        request?.clear()
        request = null

        requests?.let {
            val next = if (it.empty()) null else it.pop()
            request = next
            next?.let(::postStart)
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
