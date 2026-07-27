package io.legado.app.web.utils

import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Res

/**
 * [WebAssetSource] 的 iOS / 鸿蒙 (nativeMain) 共用 actual 实现。
 *
 * 用 composeResources [Res.readBytes] 读 `commonMain/composeResources/files/web/` 下的静态资源
 * (compose.components.resources 已 KMP 发布, 经 generateComposeResClass task 暴露 Res 类)。
 *
 * # 单一数据源
 * web 资源唯一数据源在 `commonMain/composeResources/files/web/`, Android/桌面 JVM/iOS/鸿蒙
 * 四端均通过 composeResources [Res.readBytes] 读取, 无任何平台端资源副本。
 *
 * # 注意
 * compose.components.resources 不发布 linuxArm64 (ohos) 变体, 鸿蒙 target 默认关闭
 * (-PenableOhosTarget=false); 启用 ohos 时需引入 composeMain 中间源集隔离 Compose 资源依赖
 * (详见 build.gradle 注释), 此为预存架构约束, 非本文件引入。
 */
class NativeWebAssetSource : WebAssetSource {

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun read(path: String): ByteArray =
        Res.readBytes("files/$path")
}

/**
 * iOS / 鸿蒙宿主启动早期注册 [WebAssetSource] 的 actual 实现 (两端共用)。
 *
 * 前置依赖: 无 (composeResources 由 commonMain 依赖图自动解析)。
 *
 * 模式参考 [registerNativeFileCacheProvider] / [io.legado.app.help.service.ServiceLaunchers]。
 */
fun registerNativeWebAssetSource() {
    WebAssetSources.register(NativeWebAssetSource())
}
