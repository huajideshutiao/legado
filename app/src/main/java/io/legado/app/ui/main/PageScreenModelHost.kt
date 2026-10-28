package io.legado.app.ui.main

import androidx.lifecycle.ViewModel
import io.legado.app.ui.root.ScreenModelStore
import java.util.concurrent.atomic.AtomicLong

/**
 * 页面状态与窗口宿主绑定挂真实 ViewModelStore: 配置重建保留同一份 (导航栈按 entry 键取回
 * 同一模型), 界面真正退出时 onCleared 统一清理并取消各页面的请求作用域。
 *
 * [windowId] 是本次窗口宿主的身份: Activity 重建不变, 另开窗口是新身份 —— 请求据此判断
 * 该由哪个窗口的当前界面呈现, 不把结果投到别的窗口。
 *
 * 页面模型的平台绑定不随本类保留: 重建后由各路由组合按当前绑定重绑 (见 ReaderRoute /
 * VideoPlayRoute), 不保整仓旧绑定。
 */
class PageScreenModelHost : ViewModel() {

    val store = ScreenModelStore()

    val windowId: Long = windowSequence.incrementAndGet()

    override fun onCleared() {
        store.clear()
    }

    private companion object {
        val windowSequence = AtomicLong()
    }
}
