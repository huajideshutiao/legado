package io.legado.app.ui.login

import android.app.Application
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.GlobalVars
import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.toastOnUi

class SourceLoginViewModel(application: Application) : BaseViewModel(application) {

    var source: BaseSource? = null
    var headerMap: Map<String, String> = emptyMap()

    fun initData(success: (bookSource: BaseSource) -> Unit, error: () -> Unit) {
        execute {
            source = GlobalVars.nowSource
            headerMap = runScriptWithContext {
                source?.getHeaderMap(true) ?: emptyMap()
            }
            source
        }.onSuccess {
            if (it != null) {
                success.invoke(it)
            } else {
                context.toastOnUi("未找到书源")
            }
        }.onError {
            error.invoke()
            AppLog.put("登录 UI 初始化失败\n$it", it, true)
        }
    }

}
