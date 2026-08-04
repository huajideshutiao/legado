package io.legado.app.utils

import io.legado.app.ui.platform.sharedAppContext

/** composeResources 打进 assets 的目录前缀 (含模块限定名, 由插件按模块生成)。 */
private const val ASSET_PREFIX = "composeResources/legado.shared.generated.resources/files/"

/** 对照原 app 端 `appCtx.assets.open(path).use { it.readBytes() }`, 失败返回 null。 */
internal actual fun readSharedResourceBytes(path: String): ByteArray? {
    return try {
        sharedAppContext?.assets?.open(ASSET_PREFIX + path)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
}
