package io.legado.app.ui.compose.platform

import kotlinx.coroutines.runBlocking
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.allDrawableResources
import legado.ui.generated.resources.allStringArrayResources
import legado.ui.generated.resources.allStringResources
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * 按 key 查 Compose Resources 生成的运行时映射表 (取代各端手写字符串表 / Android getIdentifier 反射)。
 *
 * 映射表由资源生成器产出且是 internal, app/desktop 等外部模块经这几个 public 函数转发访问。
 * key 缺失返回 null, 由调用方兜底 (不抛异常)。
 */
fun findStringResource(key: String): StringResource? = Res.allStringResources[key]

fun findStringArrayResource(key: String): StringArrayResource? = Res.allStringArrayResources[key]

fun findDrawableResource(key: String): DrawableResource? = Res.allDrawableResources[key]

/**
 * 注册同步字符串 provider: [syncGetString] (foundation 下沉的注册式入口) 的 compose 资源实现。
 * 取值经 runBlocking 桥接 suspend: CMP 资源读取在三端均为同步文件 IO、无 dispatcher
 * 跳转 (native 端 ResourceReader 直读 NSBundle/本地路径), 主线程调用不会死锁; 首次
 * 读取后走 AsyncCache, 之后零 IO。语言切换按 locale 路径自动取新语言。
 */
fun registerComposeSyncStringProvider() {
    registerSyncStringProvider { key, formatArgs ->
        val resource = findStringResource(key) ?: return@registerSyncStringProvider key
        runBlocking {
            if (formatArgs.isEmpty()) getString(resource)
            else getString(resource, *formatArgs.map { it.toString() }.toTypedArray())
        }
    }
}

/**
 * 桌面 JVM / 无头环境非 @Composable 字符串获取入口 (转发 [syncGetString])。
 *
 * 移至本文件以切断与 [ResourceProvider.jvm.kt] 中 @Composable 方法在同一 class 的字节码
 * 符号绑定, 确保 headless 在排除 Compose UI 依赖后调用本函数不触发 NoClassDefFoundError。
 */
fun jvmGetString(key: String, vararg formatArgs: Any?): String = syncGetString(key, *formatArgs)

