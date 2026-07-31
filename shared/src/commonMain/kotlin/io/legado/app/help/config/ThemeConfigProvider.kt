package io.legado.app.help.config

import io.legado.app.constant.PreferKey
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.concurrent.Volatile

/**
 * 主题配置数据类 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端 `io.legado.app.help.config.ThemeConfig.Config`:
 * - app 端 `ThemeConfig` 单例依赖大量 Android 专属资源 (Context / ThemeStore /
 *   AppCompatDelegate / AppConfig / SharedPreferences / File / Drawable / NinePatch /
 *   BookCover 等), 无法整体下沉 commonMain;
 * - 但其嵌套 `Config` data class 仅含 6 个简单字段 (themeName/isNightTheme/
 *   primaryColor/accentColor/backgroundColor/bottomBackground) + 1 个运行时标记
 *   (isBuiltin), 无 Android 依赖, 可以下沉作为 KMP 共享类型。
 *
 * # 字段对照 (与 app 端 `ThemeConfig.Config` 完全一致)
 *
 * - themeName: 主题名 (作为唯一标识, 覆盖导入时按此匹配)
 * - isNightTheme: 是否夜间主题
 * - primaryColor: 主色 (十六进制颜色字符串, 如 "#FF5722")
 * - accentColor: 强调色
 * - backgroundColor: 背景色
 * - bottomBackground: 底栏背景色
 * - isBuiltin: 是否内置主题 (运行时标记, 不持久化), 对照原 `@Transient var isBuiltin`
 *
 * # 序列化差异
 *
 * - **@Serializable**: 用 `kotlinx.serialization.Serializable` 替代 app 端
 *   `androidx.annotation.Keep` (ProGuard keep 通过 proguard-rules.pro 配置, 不依赖注解)。
 * - **@Transient**: 用 `kotlinx.serialization.Transient` 替代
 *   `com.google.gson.annotations.Transient`, 含义一致 (序列化时跳过 isBuiltin)。
 * - **hashCode**: 保留与 app 端原版完全一致的实现 `GSON.toJson(this).hashCode()`
 *   (commonMain 的 GSON 是 kotlinx.serialization.Json, 行为与 app 端 Gson 等价)。
 * - **equals**: 保留与 app 端原版完全一致的字段逐一比较 (不直接用 data class 自动
 *   生成的 equals, 避免与 hashCode 不一致)。
 *
 * # 跨端桥接
 *
 * app 端 `ThemeConfig.Config` 与本类的字段一一对应, 由 [ThemeConfigProvider] 实现
 * (app 端 `ThemeConfigProviderImpl`) 负责互转: `ThemeConfig.Config` ↔ `ThemeConfigData`。
 * `ImportThemeViewModelShared` 仅持有 [ThemeConfigData] 列表, 不直接引用 app 端
 * `ThemeConfig` 单例, 通过 [ThemeConfigProviders] 注入访问 configList 和 addConfig。
 */
@Serializable
data class ThemeConfigData(
    var themeName: String,
    var isNightTheme: Boolean,
    var primaryColor: String,
    var accentColor: String,
    var backgroundColor: String,
    var bottomBackground: String
) {

    /** 是否内置主题 (运行时标记, 不持久化, 对照 app 端 `@Transient var isBuiltin`)。 */
    @Transient
    var isBuiltin: Boolean = false

    /**
     * 与 app 端原版完全一致: 用 GSON.toJson 序列化后取 hashCode。
     *
     * 注: commonMain 的 GSON (kotlinx.serialization.Json) 序列化结果与 app 端 Gson
     * 略有差异 (字段顺序/格式), 但 hashCode 仅用于集合去重和相等性判断, 不影响业务。
     */
    override fun hashCode(): Int {
        return GSON.toJson(this).hashCode()
    }

    /** 与 app 端原版完全一致: 6 个字段逐一比较。 */
    override fun equals(other: Any?): Boolean {
        other ?: return false
        if (other is ThemeConfigData) {
            return other.themeName == themeName
                    && other.isNightTheme == isNightTheme
                    && other.primaryColor == primaryColor
                    && other.accentColor == accentColor
                    && other.backgroundColor == backgroundColor
                    && other.bottomBackground == bottomBackground
        }
        return false
    }
}

/**
 * 主题自定义编辑数据类 (KMP 版, commonMain)。
 *
 * 对照 app 端 `ThemeConfig.CustomTheme` (accent/background/bottomBackground/bgImage/bgImageBlur),
 * 去掉背景图相关字段 (背景图功能不下沉), 仅保留三色 (ARGB packed Int, 与 ColorPicker 一致)。
 *
 * 供 ThemeCustomizeDialog 下沉后跨平台共享表单状态 (EDIT_PREFS / EDIT_CONFIG / NEW_CONFIG 三模式),
 * 颜色用 Int 而非 Compose Color, 保持 commonMain 无 Compose UI 依赖 (与 [ThemeConfigData] 用 String 同理)。
 */
data class CustomThemeData(
    val accent: Int,
    val bg: Int,
    val bbg: Int,
)

/**
 * ThemeConfig 跨模块访问接口。
 *
 * app 端 `ThemeConfig` 单例依赖 Android 专属资源 (Context / ThemeStore /
 * SharedPreferences / File / Drawable 等), 留 app 端。本接口仅暴露主题导入
 * 业务 (ImportThemeViewModelShared) 用到的 `configList` 读和 `addConfig` 写,
 * 由 app 端 `ThemeConfigProviderImpl` 包装 `ThemeConfig` 实现, 在 App.onCreate
 * 经 [ThemeConfigProviders.register] 注册。
 *
 * 模式参考 [AppConfigAccessor] / [io.legado.app.help.book.ContentProcessorAccessor]。
 *
 * # app 端实现桥接
 *
 * - `getConfigList()`: 返回 `ThemeConfig.configList` 中每个 `ThemeConfig.Config`
 *   转换得到的 [ThemeConfigData] 列表 (字段一一对应);
 * - `addConfig(config)`: 把 [ThemeConfigData] 转回 `ThemeConfig.Config` 后调
 *   `ThemeConfig.addConfig(newConfig)` (app 端原方法内部会做重复名覆盖 + save 持久化)。
 */
interface ThemeConfigProvider {

    /**
     * 返回当前已存在的主题配置列表 (对照 `ThemeConfig.configList: ArrayList<Config>`)。
     *
     * 用于 [ImportThemeViewModelShared.comparisonSource] 比对本地是否已存在同名主题。
     *
     * @return 主题配置列表 (字段一一映射自 app 端 `ThemeConfig.Config`)
     */
    fun getConfigList(): List<ThemeConfigData>

    /**
     * 新增/覆盖一个主题配置 (对照 `ThemeConfig.addConfig(newConfig: Config)`)。
     *
     * app 端实现内部:
     * 1. 把 [config] 转回 `ThemeConfig.Config`;
     * 2. 调 `ThemeConfig.addConfig(newConfig)` (内部按 themeName 去重: 同名覆盖, 否则追加);
     * 3. `ThemeConfig.addConfig` 内部会调 `save()` 持久化到 themeConfig.json。
     *
     * @param config 待新增/覆盖的主题配置 (KMP 版数据类)
     */
    fun addConfig(config: ThemeConfigData)

    /**
     * 删除指定索引的主题配置 (对照 `ThemeConfig.delConfig(index: Int)`)。
     *
     * app 端实现内部: `configList.removeAt(index)` + `save()` + `applyTheme(appCtx)`。
     *
     * @param index 待删除的主题配置索引 (在 configList 中的位置)
     */
    fun delConfig(index: Int)

    /**
     * 应用内置默认主题 (对照 `ThemeConfig.applyBuiltin(context: Context, isNight: Boolean)`)。
     *
     * app 端实现内部: 清 6 个 pref → `AppConfig.isNightTheme = isNight` → `applyDayNight(context)`
     * (含 applyTheme + postEvent(RECREATE))。
     *
     * 桌面/iOS/鸿蒙 actual: 仅清自定义色 pref / no-op (Dialog 下沉后由 [applyConfig] +
     * [io.legado.app.ui.compose.platform.ThemeStoreProvider.applyColors] +
     * [io.legado.app.ui.compose.platform.EventBusProvider.emitRecreate] 组合实现)。
     *
     * @param isNight 是否夜间主题
     */
    fun applyBuiltin(isNight: Boolean)

    /**
     * 应用指定主题配置 (对照 `ThemeConfig.applyConfig(context: Context, config: Config)`)。
     *
     * app 端实现内部: `applyConfigToPrefs` + `AppConfig.isNightTheme = config.isNightTheme` +
     * `applyDayNight(context)` (含 applyTheme + postEvent(RECREATE))。
     *
     * 桌面/iOS/鸿蒙 actual: 仅持久化 config (Dialog 下沉后由
     * [io.legado.app.ui.compose.platform.ThemeStoreProvider.applyColors] +
     * [io.legado.app.ui.compose.platform.EventBusProvider.emitRecreate] 组合实现应用+重建)。
     *
     * @param config 待应用的主题配置 (KMP 版数据类)
     */
    fun applyConfig(config: ThemeConfigData)

    /**
     * 返回内置主题配置列表 (对照 `ThemeConfig.getBuiltinConfigs(context: Context): List<Config>`)。
     *
     * app 端与桌面/iOS/鸿蒙 ([FileThemeConfigProvider]) 均返回 2 项
     * (默认日间 + 默认夜间, isBuiltin=true)。
     *
     * @return 内置主题配置列表 (每项 isBuiltin=true)
     */
    fun getBuiltinConfigs(): List<ThemeConfigData>

    /**
     * 持久化 configList 到磁盘 (对照 `ThemeConfig.save()`)。
     *
     * app 端实现内部: `GSON.toJson(configList)` → 写 themeConfig.json;
     * 桌面/iOS/鸿蒙 ([FileThemeConfigProvider]) 同样写 `{filesDir}/themeConfig.json` (格式互通)。
     */
    fun save()

    /**
     * 从磁盘重载 configList (对照 app 端 `ThemeConfig.upConfig()`)。
     *
     * 恢复备份覆盖 themeConfig.json 后调用, 默认无操作 (由实现按需覆写)。
     */
    fun upConfig() {}

    /**
     * 清理主题背景图片缓存 (对照 app 端 `ThemeConfig.clearBg`)。
     *
     * # default 实现行为
     * 1. 读取 [PreferKey.bgImage] / [PreferKey.bgImageN] 当前选中的背景路径 (白名单)
     * 2. 删除 `{externalFilesDir ?: filesDir}/backgroundImage/` 目录下不在白名单中的文件
     * 3. 删除 `{externalFilesDir ?: filesDir}/backgroundImageNight/` 目录下不在白名单中的文件
     *
     * # 平台差异
     * - app 端: `ThemeConfigProviderImpl` 应 override 本方法, 调原 `ThemeConfig.clearBg()`
     *   (含 `bgDrawableCache = null` / `bgCacheKey = null` Drawable 缓存清空);
     *   app 端 `App.kt` 仍调原 `ThemeConfig.clearBg()` (object 方法), 不经此 default 实现
     * - 桌面端: 用 default 实现, 走 [AppFilesDirs] + [BackupFileOps] 跨平台抽象;
     *   桌面端 externalFilesDir 为 null, 回退到 filesDir (~/.legado/files);
     *   无背景图导入 UI 时目录为空, 清理即空转
     */
    fun clearBg() {
        val filesBase = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
        val sep = BackupFileOps.separator
        val prefs = PreferenceProviders.get()

        // 白天背景: 读取当前路径, 删除 backgroundImage 目录中不在白名单的文件
        val bgImagePath = prefs.getString(PreferKey.bgImage)
        val bgImageDir = filesBase + sep + PreferKey.bgImage
        BackupFileOps.listFiles(bgImageDir)?.forEach { filePath ->
            if (filePath != bgImagePath) {
                BackupFileOps.delete(filePath)
            }
        }

        // 夜间背景: 读取当前路径, 删除 backgroundImageNight 目录中不在白名单的文件
        val bgImageNPath = prefs.getString(PreferKey.bgImageN)
        val bgImageNDir = filesBase + sep + PreferKey.bgImageN
        BackupFileOps.listFiles(bgImageNDir)?.forEach { filePath ->
            if (filePath != bgImageNPath) {
                BackupFileOps.delete(filePath)
            }
        }
    }
}

/**
 * ThemeConfig provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用
 * `ThemeConfigProviders.get().getConfigList()` 替代
 * 原 `ThemeConfig.configList`, 行为完全一致, 仅多一层 provider 间接。
 *
 * 模式参考 [AppConfigProviders] / [io.legado.app.help.book.ContentProcessorProviders]。
 */
object ThemeConfigProviders {
    @Volatile
    private var impl: ThemeConfigProvider? = null

    /** 宿主启动早期注册一次 (App.onCreate 内, 任何主题导入之前)。 */
    fun register(impl: ThemeConfigProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ThemeConfigProvider =
        impl ?: error("ThemeConfigProviders not registered")
}
