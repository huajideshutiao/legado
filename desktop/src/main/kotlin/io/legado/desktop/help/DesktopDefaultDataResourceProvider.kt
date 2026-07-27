package io.legado.desktop.help

import io.legado.app.help.DefaultDataResourceProvider

/** composeResources 打进 classpath 的 defaultData 目录前缀 (含模块限定名, 由插件按模块生成)。 */
private const val RESOURCE_PREFIX =
    "composeResources/legado.shared.generated.resources/files/defaultData/"

/**
 * 桌面 JVM 端 [DefaultDataResourceProvider] 实现。
 *
 * # 资源单一数据源
 *
 * 默认规则 JSON 唯一数据源在 `shared/src/commonMain/composeResources/files/defaultData/`,
 * Android/桌面/iOS/鸿蒙四端共用: Android 端由 compose 资源插件打进 assets, 桌面端经 jvm target
 * 打进 classpath (本类用 [ClassLoader.getResourceAsStream] 读), native 端走 `Res.readBytes`
 * (见 `NativeDefaultDataResourceProvider`)。
 *
 * 读取失败 (资源缺失) 抛 [IllegalStateException], 由调用方
 * ([io.legado.app.help.DefaultDataShared] 内 runCatching / getOrThrow) 处理。
 *
 * 宿主启动早期由 desktop Main.kt 构造并注册到 [io.legado.app.help.DefaultDataResourceProviders]。
 */
class DesktopDefaultDataResourceProvider : DefaultDataResourceProvider {

    override fun readResource(name: String): String {
        val classLoader = Thread.currentThread().contextClassLoader
            ?: DesktopDefaultDataResourceProvider::class.java.classLoader
        val stream = classLoader?.getResourceAsStream("$RESOURCE_PREFIX$name")
            ?: error("$RESOURCE_PREFIX$name not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }
}
