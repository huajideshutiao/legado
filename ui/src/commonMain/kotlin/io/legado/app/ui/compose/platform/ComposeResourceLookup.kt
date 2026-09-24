package io.legado.app.ui.compose.platform

import io.legado.app.help.i18n.registerAppStringProvider
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
 * 注册 composeResources 字符串的两个同步通道, 二者共用同一份按 key 查表的实现:
 * - [syncGetString]: key 为 String, 供 :ui/:core/:data 的同步调用点 (非组合、非挂起上下文)
 * - [appString]: key 为 AppStringKey, 供 :data/:core 的非 UI 层异常与提示文案
 *
 * 取值经 runBlocking 桥接 suspend: CMP 资源读取在三端均为同步文件 IO、无 dispatcher
 * 跳转 (native 端 ResourceReader 直读 NSBundle/本地路径), 主线程调用不会死锁; 首次
 * 读取后走 AsyncCache, 之后零 IO。语言切换按 locale 路径自动取新语言。
 *
 * 四端宿主启动早期各调用一次, 须早于任何取值调用: Android App.onCreate /
 * DesktopCore.registerEarlyProviders (桌面与 headless 共用) / IosProviderRegistry / MainOhos。
 * 未注册时两条通道均返回 key 名。
 */
fun registerComposeStringProviders() {
    registerSyncStringProvider { key, formatArgs ->
        val resource = findStringResource(key) ?: return@registerSyncStringProvider key
        runBlocking {
            if (formatArgs.isEmpty()) getString(resource)
            else getString(resource, *formatArgs.map { it.toString() }.toTypedArray())
        }
    }
    registerAppStringProvider { key, args -> syncGetString(key.name, *args) }
}

