package io.legado.app.ui.main

import androidx.lifecycle.ViewModel
import io.legado.app.ui.root.ScreenModelStore

/**
 * 页面状态挂真实 ViewModelStore: 配置重建保留同一份 (导航栈按 entry 键取回同一模型),
 * 界面真正退出时 onCleared 统一清理并取消各页面的请求作用域。
 *
 * 页面模型的平台绑定不随本类保留: 重建后由各路由组合按当前绑定重绑 (见 ReaderRoute /
 * VideoPlayRoute), 不保整仓旧绑定。
 */
class PageScreenModelHost : ViewModel() {

    val store = ScreenModelStore()

    override fun onCleared() {
        store.clear()
    }
}
