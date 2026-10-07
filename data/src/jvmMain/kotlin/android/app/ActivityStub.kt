// android.app.Activity JVM 空壳 stub: 扩展声明设置页 Activity (keiyoushi 的 ConfigurableSource
// 常在插件里带 Activity 子类) 时保证类解析与 super.onCreate 链路可链接; 桌面端不启动 Activity。
package android.app

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle

open class Activity : ContextWrapper() {

    private var finished: Boolean = false

    protected open fun onCreate(savedInstanceState: Bundle?) {
    }

    protected open fun onStart() {
    }

    protected open fun onResume() {
    }

    protected open fun onPause() {
    }

    protected open fun onStop() {
    }

    protected open fun onDestroy() {
    }

    open fun finish() {
        finished = true
    }

    open fun isFinishing(): Boolean = finished

    open fun getIntent(): Intent = Intent()

    open fun setContentView(layoutResID: Int) {
    }

    open fun runOnUiThread(action: Runnable) {
        action.run()
    }
}
