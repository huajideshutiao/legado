package io.legado.app.model

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProvider
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toJson
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * BookCover 中可跨平台共享的纯逻辑部分。
 *
 * 仅包含与 Android 无关的枚举数据 ([CoverRatio])、纯数据类 ([DefaultCoverEntry])
 * 以及图集 entries 记忆化解析/选图 (展示路径计算见
 * [io.legado.app.model.defaultCoverDisplayPath], 缓存产物/图集原图自动分流)。
 * Glide/Bitmap/NinePatch 等 Android 专属实现保留在 app 端 [BookCover],不在此下沉。
 *
 * app 端 [BookCover] 通过顶层 typealias 引用本对象中的 [CoverRatio] 与 [DefaultCoverEntry],
 * 并以扩展函数形式将展示路径包装回原 `entry.bakedPath(ratio)` 签名。
 *
 * desktop 端可直接使用本对象,无需重新实现枚举与数据类。
 */
object BookCoverShared {

    /**
     * 封面比例 -- novel 用于普通书籍 (3:4, 居中裁剪);
     * video 用于视频源 (16:9, 顶部裁剪保留封面信息).
     * bakeW/bakeH 是按 480dpi 烘焙落盘的目标像素尺寸。
     *
     * 与原 app 端 [BookCover.CoverRatio] 字段一致, 仅迁移位置, 未改任何值。
     */
    enum class CoverRatio(
        val widthRatio: Int,
        val heightRatio: Int,
        val bakeW: Int,
        val bakeH: Int,
        val fileTag: String,
    ) {
        NOVEL(3, 4, 300, 400, "novel"),
        VIDEO(16, 9, 720, 405, "video"),
    }

    /**
     * 默认封面图集中的一项。
     * - 普通图: 展示路径为缓存烘焙产物 `customImg/covers/<id>_<ratio>.webp`, 原图保留在图集;
     * - .9.png: 不烘焙, 展示路径为图集原图 `customImg/covers/<id>.9.png` (NinePatchDrawable 会按容器自适应)。
     *
     * 展示路径计算见 [defaultCoverDisplayPath] (本文件不持目录状态, 目录由缓充/图集公共函数给出)。
     *
     * @Serializable 供 kotlinx.serialization (desktop 等端 GSON 别名 = KS_JSON) 解析;
     * app 端 Gson 反射不依赖此注解, 两端 JSON 格式 (字段名/默认值) 完全兼容。
     */
    @Serializable
    data class DefaultCoverEntry(
        val id: String,
        val ninePatch: Boolean = false,
    )

    /**
     * 列出某偏好下已选图集 entries 在指定 [ratio] 下的展示路径列表。
     *
     * 仅做路径计算, 不做 Bitmap 解码, 不校验文件存在性 ——
     * 缺文件的 entry 由渲染端回落内置图 (app 端 newDefaultDrawable 的 runCatching /
     * shared 端 loader 加载失败兜底)。
     */
    fun listDefaultCoverPaths(
        entries: List<DefaultCoverEntry>,
        ratio: CoverRatio,
    ): List<String> = entries.map { defaultCoverDisplayPath(it, ratio) }

    /**
     * 图集解析缓存 (日/夜 prefKey 各一条, raw 串即版本号):
     * 同一 raw 只解析一次, 增删封面后 prefs 写入新串自动失效重解析。
     * 封面加载协程在主线程调用, 引用赋值原子; 极端竞争最坏重复解析一次, 无害。
     */
    private var parsedDefaultCover: Pair<String, List<DefaultCoverEntry>>? = null
    private var parsedDefaultCoverDark: Pair<String, List<DefaultCoverEntry>>? = null

    /**
     * 列出某偏好下当前已选的图集 entries (不校验文件存在性)。
     *
     * 纯逻辑: 读 prefs + JSON 解析 (结果按 raw 串记忆化)。app 端
     * `BookCover.listDefaultCovers` 等也委托本函数, 各端共用同一份缓存与选图。
     *
     * @param prefs 平台偏好提供者 (app 端注入 appCtx 偏好, desktop 端注入 PreferenceProviders.get())
     * @param prefKey 偏好 key (PreferKey.defaultCover / PreferKey.defaultCoverDark)
     */
    fun listDefaultCovers(prefs: PreferenceProvider, prefKey: String): List<DefaultCoverEntry> {
        val raw = prefs.getString(prefKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        val isDark = prefKey == PreferKey.defaultCoverDark
        val cached = if (isDark) parsedDefaultCoverDark else parsedDefaultCover
        if (cached?.first == raw) return cached.second
        val entries = GSON.fromJsonArray<DefaultCoverEntry>(raw).getOrNull().orEmpty()
        val parsed = raw to entries
        if (isDark) parsedDefaultCoverDark = parsed else parsedDefaultCover = parsed
        return entries
    }

    /**
     * 添加一个 entry 到 prefs (相同 id 忽略, 避免重复)。
     *
     * 仅更新 prefs, 不处理文件烘焙/拷贝 —— 烘焙是平台专属行为
     * (app 端 Bitmap/Glide, desktop 端 BufferedImage/ImageIO), 由调用方在调用本函数前完成。
     *
     * @return 是否实际新增 (false 表示 id 已存在, 忽略)
     */
    fun addDefaultCoverEntry(prefs: PreferenceProvider, prefKey: String, entry: DefaultCoverEntry): Boolean {
        val existing = listDefaultCovers(prefs, prefKey).toMutableList()
        if (existing.any { it.id == entry.id }) return false
        existing.add(entry)
        prefs.putString(prefKey, GSON.toJson(existing))
        return true
    }

    /**
     * 从 prefs 移除指定 id 的 entry, 返回被移除的 entry (供调用方删文件)。
     *
     * 仅更新 prefs, 不删文件 —— 文件删除由调用方在调用本函数后执行 (平台专属行为)。
     *
     * @return 被移除的 entry, null 表示未找到
     */
    fun removeDefaultCoverEntry(prefs: PreferenceProvider, prefKey: String, id: String): DefaultCoverEntry? {
        val existing = listDefaultCovers(prefs, prefKey).toMutableList()
        val target = existing.firstOrNull { it.id == id } ?: return null
        existing.remove(target)
        prefs.putString(prefKey, GSON.toJson(existing))
        return target
    }

    /**
     * 清空某偏好下的所有 entries, 返回被清空的 entries (供调用方删文件)。
     *
     * 仅更新 prefs, 不删文件 —— 文件删除由调用方在调用本函数后执行 (平台专属行为)。
     */
    fun clearDefaultCoverEntries(prefs: PreferenceProvider, prefKey: String): List<DefaultCoverEntry> {
        val existing = listDefaultCovers(prefs, prefKey)
        if (existing.isEmpty()) return emptyList()
        prefs.putString(prefKey, "")
        return existing
    }

    /**
     * 按 seed 稳定挑选默认封面下标 (1:1 下沉 app 端 BookCover.newDefaultDrawable 的选图逻辑)。
     *
     * seed 一般是书名, 保证同一本书每次拿到同一张; 空则随机。
     * 返回 -1 表示图集为空, 调用方应回落到内置 image_cover_default。
     */
    fun pickDefaultCoverIndex(size: Int, seed: String?): Int {
        if (size <= 0) return -1
        if (seed.isNullOrBlank()) return Random.nextInt(size)
        return (seed.hashCode().rem(size) + size).rem(size)
    }

    /**
     * 取当前昼夜对应的图集 (对照 app 端 BookCover.currentCovers)。
     */
    fun currentDefaultCovers(
        prefs: PreferenceProvider,
        isNightTheme: Boolean,
    ): List<DefaultCoverEntry> = listDefaultCovers(
        prefs,
        if (isNightTheme) PreferKey.defaultCoverDark else PreferKey.defaultCover,
    )
}

