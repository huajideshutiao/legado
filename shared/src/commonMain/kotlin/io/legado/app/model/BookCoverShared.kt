package io.legado.app.model

import io.legado.app.help.config.PreferenceProvider
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toJson
import kotlinx.serialization.Serializable

/**
 * BookCover 中可跨平台共享的纯逻辑部分。
 *
 * 仅包含与 Android 无关的枚举数据 ([CoverRatio])、纯数据类 ([DefaultCoverEntry])
 * 以及路径计算 ([bakedPath])。Glide/Bitmap/NinePatch 等 Android 专属实现保留在
 * app 端 [BookCover],不在此下沉。
 *
 * app 端 [BookCover] 通过顶层 typealias 引用本对象中的 [CoverRatio] 与 [DefaultCoverEntry],
 * 并以扩展函数形式将 [bakedPath] 包装回原 `entry.bakedPath(ratio)` 签名,
 * 内部委托本对象的 [bakedPath] 工具函数,注入 app 端专属的 coversDir。
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
     * - 普通图:bakedPath(ratio) 指向 `covers/default/{id}_{tag}.webp` 的烘焙结果,原图不保留。
     * - .9.png:不烘焙,所有 ratio 都指向 `covers/default/{id}.9.png` (NinePatchDrawable 会按容器自适应)。
     *
     * 仅含纯数据字段 (id, ninePatch), 不含 [bakedPath] 成员方法 ——
     * 路径计算依赖平台专属的 coversDir, 故拆出为 [BookCoverShared.bakedPath] 工具函数,
     * 由各平台端用扩展函数包装注入 coversDir, 保持调用签名 `entry.bakedPath(ratio)` 不变。
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
     * 计算默认封面烘焙后的本地路径 (.9.png 或 webp)。
     *
     * - ninePatch=true: 返回 `{coversDir}/{id}.9.png`
     * - ninePatch=false: 返回 `{coversDir}/{id}_{ratio.fileTag}.webp`
     *
     * 此函数仅做路径计算 (String + [resolveChildAbsolutePath]), 不依赖 Android Bitmap/Glide, 可跨平台共享。
     * coversDir 接收绝对路径字符串 (app/desktop 端传入 `File.absolutePath`),
     * 路径拼接委托 [resolveChildAbsolutePath] (各平台 actual 实现, commonMain 不引用 java.io.File)。
     * 与原 app 端 [DefaultCoverEntry.bakedPath] 实现一致, 仅迁移位置并显式接收 coversDir。
     */
    fun bakedPath(
        coversDir: String,
        id: String,
        ninePatch: Boolean,
        ratio: CoverRatio,
    ): String {
        // 子文件名: id 为 md5/uuid (无分隔符), ratio.fileTag 为 "novel"/"video"
        val child = if (ninePatch) {
            "$id.9.png"
        } else {
            "${id}_${ratio.fileTag}.webp"
        }
        return resolveChildAbsolutePath(coversDir, child)
    }

    /**
     * 计算某 [entry] 在指定 [ratio] 下的烘焙文件路径 (扩展函数形式, 内部委托 [bakedPath])。
     *
     * 供平台端扩展函数包装使用, 避免重复展开 id/ninePatch 字段。
     */
    fun bakedPath(
        coversDir: String,
        entry: DefaultCoverEntry,
        ratio: CoverRatio,
    ): String = bakedPath(coversDir, entry.id, entry.ninePatch, ratio)

    /**
     * 列出某偏好下已选图集 entries 在指定 [ratio] 下的路径列表。
     *
     * 仅做路径计算, 不做 Bitmap 解码, 不校验文件存在性 ——
     * 文件存在性校验由 app 端 [BookCover.loadCovers] 在解析后用 `File(...).exists()` 完成。
     *
     * @param coversDir 烘焙文件所在目录的绝对路径 (平台端提供)
     * @param entries 已解析的 entry 列表 (调用方负责 JSON 解析, 跨序列化框架差异由调用方承接)
     * @param ratio 目标比例
     */
    fun listDefaultCoverPaths(
        coversDir: String,
        entries: List<DefaultCoverEntry>,
        ratio: CoverRatio,
    ): List<String> = entries.map { bakedPath(coversDir, it, ratio) }

    /**
     * 列出某偏好下当前已选的图集 entries (不校验文件存在性)。
     *
     * 纯逻辑: 读 prefs + JSON 解析。等价 app 端 `BookCover.currentEntries` / `listDefaultCovers`,
     * 供各平台端复用, 避免重复实现。
     *
     * @param prefs 平台偏好提供者 (app 端注入 appCtx 偏好, desktop 端注入 PreferenceProviders.get())
     * @param prefKey 偏好 key (PreferKey.defaultCover / PreferKey.defaultCoverDark)
     */
    fun listDefaultCovers(prefs: PreferenceProvider, prefKey: String): List<DefaultCoverEntry> {
        val raw = prefs.getString(prefKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        return GSON.fromJsonArray<DefaultCoverEntry>(raw).getOrNull().orEmpty()
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
}

/**
 * 跨平台路径拼接: 返回 `parent/child` 的绝对路径字符串。
 *
 * 替代 commonMain 不可用的 `java.io.File(parent, child).absolutePath`。
 *
 * - jvmAndAndroidMain actual: `File(parent, child).absolutePath` (与原 app 端实现完全一致)
 * - nativeMain actual: 字符串拼接 (parent 已为绝对路径, child 为纯文件名;
 *   不使用 kotlin.io.File.absolutePath, 见 NativeBookImageStorage.saveImage 注释)
 *
 * 调用方需保证 parent 为绝对路径 (app/desktop 传入 coversDir.absolutePath)。
 */
expect fun resolveChildAbsolutePath(parent: String, child: String): String
