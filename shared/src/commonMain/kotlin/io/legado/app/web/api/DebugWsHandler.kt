package io.legado.app.web.api

import io.legado.app.data.AppDatabaseProviders
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.help.coroutine.printOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * web 端书源调试的平台无关处理器 (零 android import)。
 *
 * 出站经注入的 [WsSession]; 平台壳负责帧解码与 30s ping。
 * @param cannotEmptyMsg 由平台壳注入的「不能为空」本地化文案 (原 R.string.cannot_empty)
 *
 * 下沉 commonMain: 依赖 (appDb/Debug/GSON/printOnDebug) 均已下沉, 原 app 端 runOnIO
 * (CoroutineScope 扩展, app 专属) 等价替换为 launch(IO) (本类即 CoroutineScope, 语义一致)。
 */
class DebugWsHandler(
    private val session: WsSession,
    private val cannotEmptyMsg: String,
) : WsHandler, Debug.Callback, CoroutineScope by MainScope() {

    private val notPrintState = arrayOf(10, 20, 30, 40)

    override fun onMessage(text: String) {
        launch(IO) {
            kotlin.runCatching {
                if (!text.isJson()) {
                    session.send("数据必须为Json格式")
                    session.close("调试结束")
                    return@launch
                }
                val debugBean =
                    GSON.fromJsonObject<Map<String, String>>(text).getOrNull()
                if (debugBean != null) {
                    val tag = debugBean["tag"]
                    val key = debugBean["key"]
                    if (tag.isNullOrBlank() || key.isNullOrBlank()) {
                        session.send(cannotEmptyMsg)
                        session.close("调试结束")
                        return@launch
                    }
                    AppDatabaseProviders.get().appDb.bookSourceDao.getBookSource(tag)?.let {
                        Debug.callback = this@DebugWsHandler
                        Debug.startDebug(this, it, key)
                    }
                } else {
                    session.send("数据必须为Json格式")
                    session.close("调试结束")
                    return@launch
                }
            }
        }
    }

    override fun onClose() {
        cancel()
        Debug.cancelDebug(true)
    }

    override fun onException() {
        Debug.cancelDebug(true)
    }

    override fun printLog(state: Int, msg: String) {
        if (state in notPrintState) {
            return
        }
        // 原 app 端 runOnIO { ... } (CoroutineScope 扩展) 等价替换为 launch(IO) { ... }
        launch(IO) {
            runCatching {
                session.send(msg)
                if (state == -1 || state == 1000) {
                    Debug.cancelDebug(true)
                    session.close("调试结束")
                }
            }.onFailure {
                it.printOnDebug()
            }
        }
    }
}
