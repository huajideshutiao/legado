package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 四端统一的封面复用入口 (登记与查询都落在各端现有有界缓存上)。
 *
 * 登记与查询共用 [CoverReuse] 这一份策略: 不同来源/分区是不同真实域, 各自可查,
 * 不为某个域猜别的域。
 */
internal object CoverReuseEntry {

    /**
     * URL 级查询: 调用方不知道来源与分区。
     *
     * 只读明确登记过封面用途的条目并按 URL 取当前可复用的最大面积;
     * 不按裸 URL 去读引擎的普通缓存条目。
     */
    fun peekByUrl(store: CoverReuseStore, url: String): ImageBitmap? =
        CoverReuse.select(store, CoverQuery.ByUrl(url))

    /**
     * 身份级查询: [origin] 由调用方明确给出 (null = 明确没有来源, 不是未知);
     * [persistent] 未知时保持跨分区只读, 不按书籍是否存在猜分区。
     */
    fun peekQualified(
        store: CoverReuseStore,
        url: String,
        origin: String?,
        persistent: Boolean?,
    ): ImageBitmap? = CoverReuse.select(
        store,
        CoverQuery.Qualified(url, originOf(origin), partitionOf(persistent)),
    )

    /**
     * 原 URL 级登记: 来源与分区都没有给出。
     *
     * 按"来源未知 + 分区未知"的真实范围登记, 不冒充已知来源或明确无来源。
     */
    fun recordByUrl(store: CoverReuseStore, url: String, bitmap: ImageBitmap) {
        CoverReuse.record(store, bitmap, url, CoverScope(CoverOrigin.Unknown, CoverPartition.Unknown))
    }

    /**
     * 身份级实际封面登记: 逻辑地址 + 真实来源 (null = 无来源) + 真实分区。
     *
     * 只有真实的封面消费点才调用它; 背景/头像/默认封面等通用解码不标封面用途。
     */
    fun recordQualified(
        store: CoverReuseStore,
        url: String,
        origin: String?,
        persistent: Boolean,
        bitmap: ImageBitmap,
    ) {
        CoverReuse.record(store, bitmap, url, CoverScope(originOf(origin), partitionOf(persistent)))
    }

    /** 只有明确 null 表示"没有来源"; 非 null 的原始字段一律按已知来源登记。 */
    private fun originOf(origin: String?): CoverOrigin =
        if (origin == null) CoverOrigin.None else CoverOrigin.Known(origin)

    private fun partitionOf(persistent: Boolean?): CoverPartition = when (persistent) {
        null -> CoverPartition.Unknown
        true -> CoverPartition.Persistent
        false -> CoverPartition.Ephemeral
    }
}
