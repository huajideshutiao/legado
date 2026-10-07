// TVBox 桌面测试运行时: 任何需要真跑 TVBox 取数链路的用例先调 [bootstrap]。
//
// 与 headless 入口同一套启动序列 (数据目录/数据库/JS 引擎/HTTP 栈/平台钩子), 逐项手工注册
// 必漏 (TVBox 取数横跨 files 目录、Room、QuickJS、OkHttp 与平台 provider)。
// provider 注册幂等但不重复执行: booted 门保持单次 (重复注册会互相覆盖实现)。
package io.legado.desktop.help.tvbox

import io.legado.app.help.coroutine.registerJvmDebugState
import io.legado.app.help.tvbox.TvBoxSniffPlatforms
import io.legado.desktop.DesktopCore
import kotlinx.coroutines.runBlocking
import java.io.File

object TvBoxTestRuntime {

    /** 测试数据根 (桌面 build/test-ext 内, 与真实用户数据目录隔离)。 */
    private val dataRoot = File("build/test-ext/tvbox-test-data").absoluteFile

    private var booted = false

    fun bootstrap() {
        if (booted) return
        booted = true
        System.setProperty("java.awt.headless", "true")
        registerJvmDebugState(true)
        DesktopCore.initRuntimeEnvironment(dataRoot, null)
        DesktopCore.registerCoreProviders()
        runBlocking { DesktopCore.registerSecondaryCoreProviders() }
        // TVBox parse=1 网页嗅探 (对照 :desktop Main.kt 的注册点): 播链兜底依赖它
        TvBoxSniffPlatforms.register(DesktopTvBoxSniffer)
    }
}
