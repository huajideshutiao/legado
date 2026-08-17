package com.script.quickjs

/**
 * 桌面 JVM 端 QuickJsEngine 测试 (薄子类)。
 *
 * 测试逻辑全部在 commonTest 的 [QuickJsEngineTestBase], 此处仅注入 JVM 端
 * 实例字段访问测试用的 Point 类型 (桌面 JVM 没有 android.graphics.Point)。
 */
class QuickJsEngineTest : QuickJsEngineTestBase() {
    override val pointFqcn: String = "java.awt.Point"
}
