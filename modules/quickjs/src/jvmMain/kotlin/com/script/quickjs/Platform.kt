package com.script.quickjs

import java.io.File

/**
 * 桌面 JVM 平台 actual 实现: 用 System.err 替代 android.util.Log,
 * 用 System.load(absolutePath) 替代 System.loadLibrary。
 *
 * native 库搜索顺序:
 * 1. 系统属性 `legado.quickjs.lib` 指定的绝对路径 (生产部署 / 测试场景手动指定)
 * 2. 环境变量 `LEGADO_QUICKJS_LIB` 指定的绝对路径
 * 3. 当前模块构建产物 `build/libs/jvm/native/{legado_quickjs.dll|liblegado_quickjs.so|legado_quickjs.dylib}`
 * 4. 项目根目录 `legado_quickjs.dll` (兼容本地脚本调试)
 *
 * 任一路径存在则加载, 全部不存在时抛 [UnsatisfiedLinkError] 让调用方感知。
 * 桌面端 native 库构建脚本 (build.gradle `buildJvmNativeLib` task) 负责产出 .dll/.so/.dylib。
 */
actual fun logQuickJsError(tag: String, msg: String, e: Throwable?) {
    System.err.println("[$tag] $msg")
    e?.printStackTrace(System.err)
}

actual fun logQuickJsWarn(tag: String, msg: String, e: Throwable?) {
    System.err.println("[$tag] $msg")
    e?.printStackTrace(System.err)
}

actual fun loadLegadoQuickJsNative() {
    val libName = jvmNativeLibName()

    // 1. 系统属性
    val propPath = System.getProperty("legado.quickjs.lib")
    if (!propPath.isNullOrEmpty() && File(propPath).exists()) {
        System.load(File(propPath).absolutePath)
        return
    }

    // 2. 环境变量
    val envPath = System.getenv("LEGADO_QUICKJS_LIB")
    if (!envPath.isNullOrEmpty() && File(envPath).exists()) {
        System.load(File(envPath).absolutePath)
        return
    }

    // 3. 模块构建产物 + 当前工作目录探测 (开发期最常用路径)
    //    :desktop:run 的工作目录可能是 desktop/ 而非项目根, 故向上递归查找
    val candidates = mutableListOf<File>()
    // 3a. 从当前工作目录向上递归查找 modules/quickjs/build/libs/jvm/native/ (覆盖任意子模块工作目录)
    var dir = File(".").absoluteFile.parentFile
    while (dir != null) {
        candidates.add(File(dir, "modules/quickjs/build/libs/jvm/native/$libName"))
        val parent = dir.parentFile
        if (parent == null || parent == dir) break
        dir = parent
    }
    // 3b. 兜底: 相对当前工作目录的常见路径
    candidates.add(File("modules/quickjs/build/libs/jvm/native", libName))
    candidates.add(File("build/libs/jvm/native", libName))
    candidates.add(File(libName))

    for (candidate in candidates) {
        if (candidate.exists()) {
            System.load(candidate.absolutePath)
            return
        }
    }

    throw UnsatisfiedLinkError(
        "legado_quickjs native library not found: $libName. " +
            "Run `./gradlew :modules:quickjs:buildJvmNativeLib` to build it, " +
            "or set -Dlegado.quickjs.lib=<path> / LEGADO_QUICKJS_LIB env var."
    )
}

/** 主机操作系统对应的 native 库文件名。 */
private fun jvmNativeLibName(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("windows") -> "legado_quickjs.dll"
        osName.contains("mac") || osName.contains("darwin") -> "liblegado_quickjs.dylib"
        else -> "liblegado_quickjs.so"
    }
}
