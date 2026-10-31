package io.legado.app.lib.permission

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.Stack

internal object RequestManager : OnPermissionsResultCallback {

    private var requests: Stack<Request>? = null
    private var request: Request? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 启动一个已分配的请求: 未实际发出调用的早退 (准入前已撤销) 按本请求身份解除当前分配并推进;
     * 真正发出调用才保真实回传占用, 立即终态交付的分支已在调用内推进队列。
     */
    private fun postStart(assigned: Request) {
        handler.post {
            if (request !== assigned) return@post
            if (assigned.start()) return@post
            // 准入前已撤销: 未发出的调用等不到回执, 让出当前位并推进, 不堵死队列
            request = null
            startNextRequest()
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
     * 撤销一条权限请求: 取消当下同步撤资格 (未起跑的不再拉权限页, 早于清理的真实回传不再投递);
     * 队列结构 (出队/让位/推进) 仍在主线程。已起跑的保持回传占用至真实返回,
     * 仅弃其 granted/denied/error 回调。
     */
    fun cancelRequest(request: Request?) {
        if (request == null) return
        request.clear()
        handler.post {
            requests?.remove(request)
            if (this.request === request && !request.isStarted) {
                // 未起跑: 不会再有系统回传, 立即让出当前位并推进
                this.request = null
                startNextRequest()
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
