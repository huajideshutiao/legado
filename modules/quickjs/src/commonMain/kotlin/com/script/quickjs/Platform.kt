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
