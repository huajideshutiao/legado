package com.script.quickjs

/**
 * 跨平台日志与 native 库加载抽象。
 *
 * 桌面 JVM target 复用自研 JNI 桥, 但需要把 Android 专属 API 抽离:
 * - `android.util.Log` → 桌面端用 `System.err`
 * - `System.loadLibrary` → 桌面端用 `System.load(absolutePath)` 从构建产物加载
 *
 * 不引入 quickjs-kt 外部库, JNI 桥 C++ 代码跨平台共享。
 */

// ============ 日志抽象 ============

/** 错误日志: Android 走 Log.e, 桌面 JVM 走 System.err。 */
expect fun logQuickJsError(tag: String, msg: String, e: Throwable? = null)

/** 警告日志: Android 走 Log.w, 桌面 JVM 走 System.err。 */
expect fun logQuickJsWarn(tag: String, msg: String, e: Throwable? = null)

// ============ native 库加载抽象 ============

/**
 * 加载 legado_quickjs native 库。
 *
 * - Android: `System.loadLibrary("legado_quickjs")` 从 APK lib/ 目录读取 .so
 * - 桌面 JVM: 从构建产物 (`build/libs/jvm/native/`) 或系统属性指定路径加载 .dll/.so/.dylib
 *
 * 桌面端 native 库不可用时抛 [UnsatisfiedLinkError], 调用方需捕获。
 */
expect fun loadLegadoQuickJsNative()

// ============ 计时器平台唤醒 (JS 线程泵送协约) ============

/**
 * 注册一个平台唤醒回调, 在 deadline 到达时调用 [JsTimerManager.signalWake]。
 *
 * 设计协约: 计时器回调本身只在 JS 线程由 [QuickJsAsync.settle] 触发 (quickjs ctx 单线程),
 * 平台层只负责"唤醒等待中的 JS 线程":
 * - Android: 经主线程 Handler.postDelayed 调度唤醒 (getMainLooper 不可用时降级 no-op)
 * - 桌面 JVM: no-op, 等待由 [JsTimerManager.awaitUntilDeadline] 的 awaitNanos 超时自醒
 */
expect fun platformScheduleTimerWake(tm: JsTimerManager, deadlineMs: Long)

/** 取消全部未触发的平台唤醒回调 (ctx close 时)。 */
expect fun platformCancelAllTimerWakes(tm: JsTimerManager)
