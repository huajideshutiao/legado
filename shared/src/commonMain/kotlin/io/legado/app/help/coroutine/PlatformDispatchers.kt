package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Coroutine 链式协程默认回调调度器的唯一注入点。
 * 安卓 actual = Dispatchers.Main；桌面 JVM actual = Dispatchers.Main（Swing/Default 兜底，见各 actual）。
 */
internal expect val mainDispatcher: CoroutineDispatcher

/**
 * Coroutine 内部错误打印。原 app 侧 `utils.printStackTraceOnDebug` 仅 DEBUG 打栈；
 * 下沉后为避免反向依赖 app, 收为同包 expect, 各平台自定「是否 debug」。
 *
 * P0-0b: 改 public 让 app 端 LogUtils.kt 的 printStackTraceOnDebug 可转发到本 expect/actual,
 * 消除 BuildConfig.DEBUG 检查的重复逻辑。app 端调用方 import 零改动。
 */
expect fun Throwable.printStackTraceOnDebug()
