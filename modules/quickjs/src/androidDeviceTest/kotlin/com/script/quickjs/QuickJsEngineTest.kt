package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Android 设备端 QuickJsEngine 测试 (薄子类)。
 *
 * 测试逻辑全部在 commonTest 的 [QuickJsEngineTestBase], 此处仅注入 Android 端
 * 实例字段访问测试用的 Point 类型 + AndroidJUnit4 runner。
 * 注: 本源集 (androidDeviceTest) 需在 build.gradle 启用 withDeviceTest 后才参与编译,
 * 启用时需将 commonTest 接入本源集并补 junit/androidx.test 依赖 (见 build.gradle 注释)。
 */
@RunWith(AndroidJUnit4::class)
class QuickJsEngineTest : QuickJsEngineTestBase() {
    override val pointFqcn: String = "android.graphics.Point"
}
