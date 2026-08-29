package io.legado.app.ui.compose.platform

import kotlinx.coroutines.runBlocking
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.allDrawableResources
import legado.shared.generated.resources.allStringArrayResources
import legado.shared.generated.resources.allStringResources
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
 * 同步(非挂起/非 Composable)取 composeResources 字符串。
 *
 * 与 Composable 侧 rememberString / suspend 侧 getString 同源, 供调用点无法改为挂起的场景:
 * - help/i18n AppStringProvider (appString 同步回调, iOS/鸿蒙宿主启动时注册)
 * - 平台 Toaster 按钮文案 (iOS dispatch_async 主线程块内)
 * - FileThemeConfigProvider.getBuiltinConfigs 等同步接口实现
 *
 * 取值经 runBlocking 桥接 suspend: CMP 资源读取在三端均为同步文件 IO、无 dispatcher
 * 跳转 (native 端 ResourceReader 直读 NSBundle/本地路径), 主线程调用不会死锁; 首次
 * 读取后走 AsyncCache, 之后零 IO。语言切换按 locale 路径自动取新语言。
 *
 * key 缺失返回 key 名 (与 rememberString 兜底一致); 占位符只认索引式 %1$s/%1$d
 * (无索引 %s 原样保留, 带参调用的 key 资源必须写成索引式)。
 */
fun syncGetString(key: String, vararg formatArgs: Any?): String {
    val resource = findStringResource(key) ?: return key
    return runBlocking {
        if (formatArgs.isEmpty()) getString(resource)
        else getString(resource, *formatArgs.map { it.toString() }.toTypedArray())
    }
}
