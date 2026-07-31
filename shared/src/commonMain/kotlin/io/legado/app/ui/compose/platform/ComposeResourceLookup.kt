package io.legado.app.ui.compose.platform

import legado.shared.generated.resources.Res
import legado.shared.generated.resources.allDrawableResources
import legado.shared.generated.resources.allStringArrayResources
import legado.shared.generated.resources.allStringResources
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

/**
 * 按 key 查 Compose Resources 生成的运行时映射表 (取代各端手写字符串表 / Android getIdentifier 反射)。
 *
 * 映射表由资源生成器产出且是 internal, app/desktop 等外部模块经这几个 public 函数转发访问。
 * key 缺失返回 null, 由调用方兜底 (不抛异常)。
 */
fun findStringResource(key: String): StringResource? = Res.allStringResources[key]

fun findStringArrayResource(key: String): StringArrayResource? = Res.allStringArrayResources[key]

fun findDrawableResource(key: String): DrawableResource? = Res.allDrawableResources[key]
