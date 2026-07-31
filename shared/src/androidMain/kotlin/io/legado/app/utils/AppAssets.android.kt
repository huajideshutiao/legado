package io.legado.app.utils

import io.legado.app.ui.platform.sharedAppContext

/** 对照原 app 端 `appCtx.assets.open(path).use { it.readBytes() }`, 失败返回 null。 */
internal actual fun readAppAssetBytes(path: String): ByteArray? {
    return try {
        sharedAppContext?.assets?.open(path)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
}
