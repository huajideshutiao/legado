package io.legado.app.help.image

/**
 * 本端封面复用落在现解码主表 (真封面经 ImageBitmapLoader 已写入该表)。
 *
 * 读只取已建立的主表, 不为冷查询初始化预算或配置; 登记走主表正常建立路径。
 */
internal actual fun currentCoverReuseStore(): CoverReuseStore? = DecodedBitmapCache.existingDecodeTable()

internal actual fun coverReuseStoreForWrite(): CoverReuseStore = DecodedBitmapCache.decodeTableForWrite()
