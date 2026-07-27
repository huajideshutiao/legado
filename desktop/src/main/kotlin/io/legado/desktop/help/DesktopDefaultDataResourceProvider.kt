package io.legado.desktop.help

import io.legado.app.help.DefaultDataResourceProvider

/**
 * 桌面 JVM 端 [DefaultDataResourceProvider] 实现。
 *
 * # 资源单一数据源
 *
 * 默认规则 JSON 存放在 `shared/src/commonMain/resources/defaultData/`, app/desktop 共用同一份。
 * - **Android 端**: Compose Multiplatform 的 `copyDebugComposeResourcesToAndroidAssets` task
 *   自动把 commonMain/resources/defaultData/ 复制到 app assets/defaultData/, app 端 provider 用
 *   `appCtx.assets.open("defaultData/$name")` 读取 (见 app 端 `registerAndroidJsEngines` 内注册)。
 * - **桌面 JVM 端** (本类): shared commonMain/resources 经 jvm target 编译进入 classpath,
 *   直接用 `getResourceAsStream("/defaultData/$name")` 读取
 *   (参考 [DesktopDirectLinkUpload] 读 `/defaultData/directLinkUpload.json`
 *   与 `io.legado.desktop.ui.about.AboutScreen` 读 `/LICENSE.md` 的模式)。
 *
 * 读取失败 (资源缺失) 抛 [IllegalStateException], 由调用方
 * ([io.legado.app.help.DefaultDataShared] 内 runCatching / getOrThrow) 处理,
 * 与 app 端 `appCtx.assets.open` 失败抛 IOException 的行为一致 (二者均向上抛, 不在此吞异常)。
 *
 * 宿主启动早期由 desktop Main.kt 构造并注册到 [io.legado.app.help.DefaultDataResourceProviders],
 * 供 shared/commonMain 的 [io.legado.app.help.DefaultDataShared] 跨平台访问。
 *
 * 模式参考 [DesktopDirectLinkUpload] (object 实现 DirectLinkUpload*Provider 接口, 同包同路径风格)。
 */
class DesktopDefaultDataResourceProvider : DefaultDataResourceProvider {

    override fun readResource(name: String): String {
        // 从 classpath 读 /defaultData/$name (shared/commonMain/resources/defaultData/ 下)
        val stream = DesktopDefaultDataResourceProvider::class.java
            .getResourceAsStream("/defaultData/$name")
            ?: error("defaultData/$name not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }
}
