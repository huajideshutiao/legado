package io.legado.app.utils

/** 桌面端无 assets 目录, 内置背景预览图不可用。 */
internal actual fun readAppAssetBytes(path: String): ByteArray? = null
