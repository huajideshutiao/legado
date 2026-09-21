package io.legado.app.ui.book.changecover

/**
 * 换封面平台专属依赖聚合接口 (KMP 注入点)。
 *
 * 对照 [io.legado.app.ui.book.changesource.ChangeBookSourcePlatform] 模式, 用聚合接口封装
 * 换封面所需的平台专属依赖。换封面业务相对简单 (无 4 个 changeSource* 开关 / 无评分 /
 * 无 ContentProcessor / 无 BookHelp.getDurChapter), 仅需 threadCount + cleanAuthor 两个注入点。
 *
 * # 各端实现
 *
 * - **Android**: `threadCount` 读 `AppConfig.threadCount`, `cleanAuthor` 用
 *   `AppPattern.authorRegex` (`author.replace(AppPattern.authorRegex, "")`)。
 *   AppPattern 已下沉 commonMain, Android 端可直接复用本接口默认思路。
 * - **桌面**: `threadCount` 读 `PreferenceProviders` (PreferKey.threadCount),
 *   `cleanAuthor` 同样用已下沉的 `AppPattern.authorRegex`
 *   (AppPattern 已下沉 commonMain, 桌面端直接调用)。
 *
 * # 为何不直接在 commonMain 内联 threadCount / cleanAuthor
 *
 * - `AppConfig.threadCount` (Android) 依赖 SharedPreferences + appCtx, 未下沉;
 *   桌面端用 `PreferenceProviders` (DesktopPreferenceProvider), 两端读取路径不同;
 * - `cleanAuthor` 抽象为接口方法供未来扩展 (如 iOS 端用 NSRegularExpression 替代 kotlin.Regex),
 *   且保持与 [io.legado.app.ui.book.changesource.ChangeBookSourcePlatform] 一致的注入风格。
 */
interface ChangeCoverPlatform {

    /**
     * 并发线程数 (对照 `AppConfig.threadCount`)。
     *
     * 用于 [ChangeCoverViewModelShared] 初始化搜索线程池
     * (`Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD))`)。
     */
    val threadCount: Int

    /**
     * 清洗作者字符串 (对照 app 端 `author.replace(AppPattern.authorRegex, "")`)。
     *
     * `AppPattern.authorRegex` 已下沉 commonMain (`io.legado.app.constant.AppPattern`),
     * 各端实现可直接 `author.replace(AppPattern.authorRegex, "")`;
     * 此处抽象为接口方法保持注入风格一致, 供未来扩展 (如 iOS 端用 NSRegularExpression)。
     *
     * @param author 原始作者字符串 (可能含 "作者:" / "某某 著" 等前缀/后缀)
     * @return 清洗后的作者字符串 (仅保留作者名本身)
     */
    fun cleanAuthor(author: String): String
}
