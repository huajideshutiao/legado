package io.legado.app.help.config

import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.Md5Digest
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.hexString
import io.legado.app.utils.toHexLower
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer

/**
 * `ReadBookConfig` 的 KMP 下沉版本（安卓/桌面/iOS/鸿蒙共用的唯一实现）。
 *
 * app 端 `io.legado.app.help.config.ReadBookConfig` 已收敛为薄壳，全部转发到本类；
 * 与原 app 实现的差异：
 * 1. 用 `class` + [PreferenceStoreProvider] 注入取代 Android `object` + `appCtx` SharedPreferences 直读；
 *    调用方通过 [ReadBookConfigProviders] / [ReadConfigProviders] /
 *    [io.legado.app.ui.compose.platform.LocalReadConfigProviders] 取得已注入实例。
 * 2. **全局简单值** (autoReadSpeed / readStyleSelect / comicStyleSelect / shareLayout /
 *    textFullJustify / textBottomJustify / hideStatusBar / hideNavigationBar / useZhLayout)
 *    走 [prefs]，key 与 app 端 `PreferKey` 一致；**排版字段全部委托 [config]**
 *    (= shareLayout ? [shareConfig] : [durConfig])，与 app 端委托关系一一对应。
 *    app 端原本把这些值缓存在字段里，这里改为每次直读 prefs（安卓端底层同为
 *    `defaultSharedPreferences` 的内存 Map，且排版热路径均在构造期快照，无性能回归），
 *    好处是设置页直写 pref 后各端都不会读到脏值。
 * 3. **样式主题列表** [configList] 持久化到 `{filesDir}/readConfig.json`
 *    (共享排版为 `shareReadConfig.json`)，用 kotlinx JSON + [BackupFileOps] 文件 IO，
 *    JSON 字段名与 app 端旧 Gson 输出逐字段一致，老配置文件可直接读取、两端备份包可互相恢复。
 * 4. **不包含** Drawable / Bitmap 操作 (curBgDrawable / upBg 等)，由各端 UI 自行渲染
 *    (安卓端见 `ReadBookConfig.kt` 里的 `ReadStyleConfig.curBgDrawable` 扩展)；
 *    clearBgAndCache / [import] / [exportConfigZip] 已下沉 (基于 [AppFilesDirs] + [BackupFileOps])。
 */
@Suppress("MemberVisibilityCanBePrivate")
class ReadBookConfigShared(private val prefs: PreferenceStoreProvider) {

    // -------------------- 全局简单值 (走 prefs，与 app 端 PreferKey 一致) --------------------

    /** 自动阅读速度（原 `AppConfig.autoReadSpeed`，默认 10）。 */
    var autoReadSpeed: Int
        get() = prefs.getInt(PreferKey.autoReadSpeed, 10)
        set(value) = prefs.putInt(PreferKey.autoReadSpeed, value)

    /** 文字阅读样式选择索引（默认 0）。 */
    var readStyleSelect: Int
        get() = prefs.getInt(PreferKey.readStyleSelect, 0)
        set(value) = prefs.putInt(PreferKey.readStyleSelect, value)

    /** 漫画样式选择索引（默认取 [readStyleSelect]，与 app 端一致）。 */
    var comicStyleSelect: Int
        get() = prefs.getInt(PreferKey.comicStyleSelect, readStyleSelect)
        set(value) = prefs.putInt(PreferKey.comicStyleSelect, value)

    /** 当前样式选择索引，读写均按 [isComic] 分派到 comic/read 两个槽位。 */
    var styleSelect: Int
        get() = if (isComic) comicStyleSelect else readStyleSelect
        set(value) {
            if (isComic) {
                comicStyleSelect = value
            } else {
                readStyleSelect = value
            }
        }

    /** 是否共享排版（原 `AppConfig.shareLayout`，默认 false）。 */
    var shareLayout: Boolean
        get() = prefs.getBoolean(PreferKey.shareLayout, false)
        set(value) = prefs.putBoolean(PreferKey.shareLayout, value)

    /** 两端对齐（默认 true）。 */
    val textFullJustify: Boolean
        get() = prefs.getBoolean(PreferKey.textFullJustify, true)

    /** 底部对齐（默认 true）。 */
    val textBottomJustify: Boolean
        get() = prefs.getBoolean(PreferKey.textBottomJustify, true)

    /** 隐藏状态栏（默认 false）。 */
    var hideStatusBar: Boolean
        get() = prefs.getBoolean(PreferKey.hideStatusBar, false)
        set(value) = prefs.putBoolean(PreferKey.hideStatusBar, value)

    /** 隐藏导航栏（默认 false）。 */
    var hideNavigationBar: Boolean
        get() = prefs.getBoolean(PreferKey.hideNavigationBar, false)
        set(value) = prefs.putBoolean(PreferKey.hideNavigationBar, value)

    /** 中文排版模式（默认 false）。 */
    var useZhLayout: Boolean
        get() = prefs.getBoolean(PreferKey.useZhLayout, false)
        set(value) = prefs.putBoolean(PreferKey.useZhLayout, value)

    /**
     * 兼容 app 端 `reloadHideBarPrefs()` 的占位实现。
     * Shared 版 [hideStatusBar] / [hideNavigationBar] 直接读 prefs，无需 reload。
     */
    fun reloadHideBarPrefs() = Unit

    // -------------------- 样式主题列表 (readConfig.json 持久化) --------------------

    /** 当前是否漫画模式（内存字段，与 app 端 `isComic` 一致）。 */
    var isComic: Boolean = false

    private val lock = SynchronizedObject()
    private var saveGeneration = 0L
    private var initialized = false
    private val internalConfigList = mutableListOf<ReadStyleConfig>()
    private var internalShareConfig: ReadStyleConfig? = null

    /**
     * 首次访问 [configList] / [shareConfig] 时从磁盘加载。
     *
     * app 端在 object `init` 里做，Shared 版推迟到首访：各端 provider 注册顺序不定
     * ([AppFilesDirs] 可能晚于本类构造注册)，构造期读盘会抛异常。
     */
    private fun ensureInit() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            initConfigs()
            initShareConfig()
        }
    }

    /** 样式主题列表（对应 app 端 `configList`，持久化到 readConfig.json）。 */
    val configList: MutableList<ReadStyleConfig>
        get() {
            ensureInit()
            return internalConfigList
        }

    /** 共享排版配置（shareLayout=true 时生效，持久化到 shareReadConfig.json）。 */
    var shareConfig: ReadStyleConfig
        get() {
            ensureInit()
            return internalShareConfig ?: ReadStyleConfig().also { internalShareConfig = it }
        }
        set(value) {
            ensureInit()
            internalShareConfig = value
        }

    /** 当前主题（按 [styleSelect] 取），setter 与 app 端一致：写回 configList 并同步 shareConfig。 */
    var durConfig: ReadStyleConfig
        get() = getConfig(styleSelect)
        set(value) {
            configList[validStyleIndex()] = value
            if (shareLayout) {
                shareConfig = value
            }
        }

    /**
     * 返回合法的当前样式索引；prefs 索引越界时（配置列表被替换/备份恢复后未同步，
     * 如 [deleteDur] 前列表已变小）修正到最后一个合法索引并落盘，
     * 避免 removeAt / 数组下标越界崩溃。
     */
    private fun validStyleIndex(): Int {
        val index = styleSelect
        if (index in 0 until internalConfigList.size) return index
        // 列表为空时 lastIndex 为 -1, 钳到 0 仍会越界 (原版靠 object init 保证列表非空), 先重置
        if (internalConfigList.isEmpty()) resetAll()
        val fixed = internalConfigList.lastIndex.coerceAtLeast(0)
        if (isComic) comicStyleSelect = fixed else readStyleSelect = fixed
        return fixed
    }

    /** 实际生效配置（shareLayout=true 走 [shareConfig]，否则走 [durConfig]）。 */
    val config: ReadStyleConfig
        get() = if (shareLayout) shareConfig else durConfig

    /** 当前文字颜色（对应 app 端 `ReadBookConfig.textColor`）。 */
    val textColor: Int
        get() = durConfig.curTextColor()

    /** readConfig.json 绝对路径（app 端为 `appCtx.filesDir` 下同名文件）。 */
    val configFilePath: String
        get() = AppFilesDirs.get().filesDir + BackupFileOps.separator + configFileName

    /** shareReadConfig.json 绝对路径。 */
    val shareConfigFilePath: String
        get() = AppFilesDirs.get().filesDir + BackupFileOps.separator + shareConfigFileName

    /**
     * 按索引取样式主题（与 app 端 `getConfig` 一致）：
     * 列表不足 5 个说明配置文件损坏/缺失，用内置样式重置后再取。
     */
    fun getConfig(index: Int): ReadStyleConfig {
        ensureInit()
        return synchronized(lock) {
            if (internalConfigList.size < 5) {
                resetAll()
            }
            internalConfigList.getOrNull(index) ?: internalConfigList[0]
        }
    }

    /** 从 readConfig.json 载入 [configList]，读取失败回落到 [ReadConfigDefaults.readConfigs]。 */
    fun initConfigs() {
        initialized = true
        var configs: List<ReadStyleConfig>? = null
        runCatching {
            if (BackupFileOps.exists(configFilePath)) {
                configs = GSON.fromJsonArray<ReadStyleConfig>(
                    BackupFileOps.readText(configFilePath)
                ).getOrThrow()
            }
        }.onFailure {
            AppLog.put("读取排版配置文件出错", it)
        }
        internalConfigList.clear()
        // copy 隔离: 直接复用默认实例会被用户修改写脏 ReadConfigDefaults 进程级默认值
        internalConfigList.addAll((configs ?: ReadConfigDefaults.readConfigs).map { it.copy() })
        // 配置文件可能比 prefs 索引短（备份恢复/文件被替换后未同步），
        // 这里修正越界索引，避免后续 deleteDur / durConfig 越界崩溃。
        val lastIndex = internalConfigList.lastIndex.coerceAtLeast(0)
        if (readStyleSelect > lastIndex) readStyleSelect = lastIndex
        if (comicStyleSelect > lastIndex) comicStyleSelect = lastIndex
    }

    /** 从 shareReadConfig.json 载入 [shareConfig]，缺失时回落到 `configList[5]` 或默认值。 */
    fun initShareConfig() {
        initialized = true
        var c: ReadStyleConfig? = null
        runCatching {
            if (BackupFileOps.exists(shareConfigFilePath)) {
                c = GSON.fromJsonObject<ReadStyleConfig>(
                    BackupFileOps.readText(shareConfigFilePath)
                ).getOrThrow()
            }
        }
        internalShareConfig = c ?: internalConfigList.getOrNull(5) ?: ReadStyleConfig()
    }

    /**
     * 异步持久化 [configList] / [shareConfig]（与 app 端 `save()` 同语义）。
     *
     * 在调用线程先生成不可变 JSON 快照，再交给 IO 协程写盘。这样连续点击背景/颜色
     * 或快速切换样式时，旧的异步任务不会在新的保存之后再次把旧快照写回文件；同时保留
     * 原有非阻塞 UI 语义。
     */
    fun save() {
        // 惰性初始化前调 save 会把空列表与全新默认写盘 (原版 object init 同步加载, 不存在这个窗口)
        ensureInit()
        val snapshot = synchronized(lock) {
            SaveSnapshot(
                generation = ++saveGeneration,
                // ReadStyleConfig 的字段都是值/字符串；copy 后异步任务不再观察可变配置对象。
                configs = internalConfigList.map { it.copy() },
                shareConfig = (internalShareConfig ?: ReadStyleConfig()).copy(),
                configPath = configFilePath,
                shareConfigPath = shareConfigFilePath,
            )
        }
        Coroutine.async {
            // JSON 编码放在 IO 协程，避免配置较多时阻塞点击线程；快照本身已脱离可变列表。
            val configJson = KS_JSON.encodeToString(
                ListSerializer(ReadStyleConfig.serializer()),
                snapshot.configs,
            )
            val shareConfigJson = KS_JSON.encodeToString(
                ReadStyleConfig.serializer(),
                snapshot.shareConfig,
            )
            // 写盘也受同一把锁保护：新保存若在旧任务写入期间到达，会在旧写完后
            // 获得锁并覆盖为更新快照；旧任务若排在后面，则因 generation 过期而跳过。
            synchronized(lock) {
                if (snapshot.generation == saveGeneration) {
                    runCatching {
                        BackupFileOps.writeText(snapshot.configPath, configJson)
                        BackupFileOps.writeText(snapshot.shareConfigPath, shareConfigJson)
                    }.onFailure {
                        AppLog.put("保存排版配置文件出错", it)
                    }
                }
            }
        }
    }

    private data class SaveSnapshot(
        val generation: Long,
        val configs: List<ReadStyleConfig>,
        val shareConfig: ReadStyleConfig,
        val configPath: String,
        val shareConfigPath: String,
    )

    /** 用内置样式重置全部主题并落盘（与 app 端 `resetAll` 一致）。 */
    private fun resetAll() {
        val defaults = ReadConfigDefaults.readConfigs
        internalConfigList.clear()
        // copy 隔离: 同上, 避免写脏内置默认实例 (恢复预设后失真)
        internalConfigList.addAll(defaults.map { it.copy() })
        // 兜底: 内置列表不足 5 条时 (解码失败回退单条) 补齐, 保证 getConfig 的
        // "size < 5 → resetAll" 判定不会每次访问都重置, 否则用户刚改的配置会在
        // 下一次读配置时被清回默认 (表现为"翻页后全部恢复默认")
        while (internalConfigList.size < 5) {
            internalConfigList.add(ReadStyleConfig())
        }
        save()
    }

    /**
     * 删除当前主题。仅当 configList 大于 5 时允许删除（与 app 端一致）。
     * 删除后同步调整 [readStyleSelect] / [comicStyleSelect] 索引。
     */
    fun deleteDur(): Boolean {
        if (configList.size > 5) {
            val removeIndex = validStyleIndex()
            configList.removeAt(removeIndex)
            // 删除选中的首个样式时也会走到 <= 分支，钳制到 0 避免索引落成 -1
            if (removeIndex <= readStyleSelect) {
                readStyleSelect = (readStyleSelect - 1).coerceAtLeast(0)
            }
            if (removeIndex <= comicStyleSelect) {
                comicStyleSelect = (comicStyleSelect - 1).coerceAtLeast(0)
            }
            return true
        }
        return false
    }

    /**
     * 导出当前主题副本（与 app 端 `getExportConfig` 一致）。
     * shareLayout=true 时用 [shareConfig] 覆盖 33 个排版字段，保证导出的是用户实际看到的排版。
     */
    fun getExportConfig(): ReadStyleConfig {
        val exportConfig = durConfig.copy()
        if (shareLayout) {
            exportConfig.textFont = shareConfig.textFont
            exportConfig.textBold = shareConfig.textBold
            exportConfig.textSize = shareConfig.textSize
            exportConfig.letterSpacing = shareConfig.letterSpacing
            exportConfig.lineSpacingExtra = shareConfig.lineSpacingExtra
            exportConfig.paragraphSpacing = shareConfig.paragraphSpacing
            exportConfig.titleMode = shareConfig.titleMode
            exportConfig.titleSize = shareConfig.titleSize
            exportConfig.titleTopSpacing = shareConfig.titleTopSpacing
            exportConfig.titleBottomSpacing = shareConfig.titleBottomSpacing
            exportConfig.paddingBottom = shareConfig.paddingBottom
            exportConfig.paddingLeft = shareConfig.paddingLeft
            exportConfig.paddingRight = shareConfig.paddingRight
            exportConfig.paddingTop = shareConfig.paddingTop
            exportConfig.headerPaddingBottom = shareConfig.headerPaddingBottom
            exportConfig.headerPaddingLeft = shareConfig.headerPaddingLeft
            exportConfig.headerPaddingRight = shareConfig.headerPaddingRight
            exportConfig.headerPaddingTop = shareConfig.headerPaddingTop
            exportConfig.footerPaddingBottom = shareConfig.footerPaddingBottom
            exportConfig.footerPaddingLeft = shareConfig.footerPaddingLeft
            exportConfig.footerPaddingRight = shareConfig.footerPaddingRight
            exportConfig.footerPaddingTop = shareConfig.footerPaddingTop
            exportConfig.showHeaderLine = shareConfig.showHeaderLine
            exportConfig.showFooterLine = shareConfig.showFooterLine
            exportConfig.tipHeaderLeft = shareConfig.tipHeaderLeft
            exportConfig.tipHeaderMiddle = shareConfig.tipHeaderMiddle
            exportConfig.tipHeaderRight = shareConfig.tipHeaderRight
            exportConfig.tipFooterLeft = shareConfig.tipFooterLeft
            exportConfig.tipFooterMiddle = shareConfig.tipFooterMiddle
            exportConfig.tipFooterRight = shareConfig.tipFooterRight
            exportConfig.tipColor = shareConfig.tipColor
            exportConfig.headerMode = shareConfig.headerMode
            exportConfig.footerMode = shareConfig.footerMode
        }
        return exportConfig
    }

    /**
     * 导入 readConfig.zip（与 app 端 `import(ByteArray)` 一致）：
     * 解压到缓存目录 → 解析 readConfig.json → 字体/三套背景图落地到 `{files}/font`、`{files}/bg`。
     */
    fun import(byteArray: ByteArray): ReadStyleConfig {
        val sep = BackupFileOps.separator
        val filesBase = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
        val cacheBase = AppFilesDirs.get().externalCacheDir ?: AppFilesDirs.get().cacheDir

        val configZipPath = cacheBase + sep + "readConfig.zip"
        BackupFileOps.delete(configZipPath)
        check(FileUtilsCommon.writeBytes(configZipPath, byteArray)) { "写入 readConfig.zip 失败" }

        val configDir = cacheBase + sep + "readConfig"
        BackupFileOps.delete(configDir)
        BackupFileOps.createFolderIfNotExist(configDir)
        BackupFileOps.unZipToPath(configZipPath, configDir)

        val config = GSON.fromJsonObject<ReadStyleConfig>(
            BackupFileOps.readText(configDir + sep + configFileName)
        ).getOrThrow()

        if (config.textFont.isNotEmpty()) {
            val fontName = config.textFont
            val fontPath = filesBase + sep + "font" + sep + fontName
            val fontFile = configDir + sep + fontName
            if (BackupFileOps.exists(fontFile)) {
                if (!BackupFileOps.exists(fontPath)) {
                    BackupFileOps.copyFile(fontFile, fontPath)
                }
                config.textFont = fontPath
            } else {
                config.textFont = ""
            }
        }
        config.bgStr = importBg(config.bgType, config.bgStr, configDir, filesBase, sep)
        config.bgStrNight = importBg(config.bgTypeNight, config.bgStrNight, configDir, filesBase, sep)
        config.bgStrEInk = importBg(config.bgTypeEInk, config.bgStrEInk, configDir, filesBase, sep)
        config.curTextColor()
        return config
    }

    /**
     * 从本地路径导入配置 zip (对照 app 端 `BgTextConfigViewModel.importConfig`)。
     *
     * 读取文件字节后委托 [import], 调用方负责选择文件路径 (各端 FilePickerService)。
     */
    fun importFromPath(path: String): ReadStyleConfig {
        val bytes = FileUtilsCommon.readBytes(path)
            ?: error("读取配置文件失败: $path")
        return import(bytes)
    }

    /**
     * 导出当前主题配置为 zip, 返回临时 zip 路径 (对照 app 端 `BgTextConfigViewModel.exportConfig`)。
     *
     * # 行为
     * 1. 在 `{externalCacheDir ?: cacheDir}/readConfig` 创建临时目录
     * 2. 写入 `readConfig.json` (经 [getExportConfig] 处理 shareLayout 后的副本)
     * 3. 字体文件非空时复制到临时目录, config.textFont 改为文件名 (与 app 端一致)
     * 4. 三套背景图 (bgType==2) 复制到临时目录
     * 5. zip 全部文件到 `{externalCacheDir ?: cacheDir}/readConfig.zip`
     * 6. 返回 zip 临时路径, 调用方负责复制到用户选择的目标并清理临时文件
     */
    fun exportConfigZip(): String {
        val sep = BackupFileOps.separator
        val cacheBase = AppFilesDirs.get().externalCacheDir ?: AppFilesDirs.get().cacheDir
        val configDir = cacheBase + sep + "readConfig"
        BackupFileOps.delete(configDir)
        BackupFileOps.createFolderIfNotExist(configDir)

        val exportFiles = mutableListOf<String>()
        val config = getExportConfig()

        // 字体文件: 路径非空时复制到临时目录, config.textFont 改为文件名
        val fontPath = textFont
        if (fontPath.isNotEmpty()) {
            val fontName = fontPath.substringAfterLast(sep).substringAfterLast('/')
            val fontFile = configDir + sep + fontName
            if (BackupFileOps.exists(fontPath) && !BackupFileOps.exists(fontFile)) {
                BackupFileOps.copyFile(fontPath, fontFile)
                config.textFont = fontName
                exportFiles.add(fontFile)
            }
        }

        // readConfig.json
        val configFile = configDir + sep + configFileName
        BackupFileOps.writeText(
            configFile,
            KS_JSON.encodeToString(ReadStyleConfig.serializer(), config)
        )
        exportFiles.add(configFile)

        // 三套背景图 (bgType==2)
        repeat(3) { index ->
            val bgPath = getBgPath(index) ?: return@repeat
            val bgName = bgPath.substringAfterLast(sep).substringAfterLast('/')
            val bgExportFile = configDir + sep + bgName
            if (BackupFileOps.exists(bgPath) && !BackupFileOps.exists(bgExportFile)) {
                BackupFileOps.copyFile(bgPath, bgExportFile)
                exportFiles.add(bgExportFile)
            }
        }

        // zip
        val configZipPath = cacheBase + sep + "readConfig.zip"
        BackupFileOps.delete(configZipPath)
        check(BackupFileOps.zipFiles(exportFiles, configZipPath)) { "打包配置失败" }
        return configZipPath
    }

    /**
     * 取指定索引的背景图片路径 (对照 app 端 `Config.getBgPath`)。
     *
     * @param bgIndex 0:白天 1:夜间 2:E-Ink
     * @return bgType==2 时返回背景路径 (含分隔符视为绝对路径, 否则拼 `{files}/bg/{bgStr}`); 否则 null
     */
    fun getBgPath(bgIndex: Int): String? = durConfig.getBgPath(bgIndex)

    /**
     * 从本地图片路径设置背景图 (对照 app 端 `BgTextConfigViewModel.setBgFromUri`)。
     *
     * # 行为
     * 1. 读取图片字节, 计算 MD5 → fileName = `md5.suffix`
     * 2. 复制到 `{externalFilesDir ?: filesDir}/bg/{fileName}` (已存在则跳过)
     * 3. 调用方负责 `durConfig.setCurBg(2, fileName)` + postConfig(BG)
     *
     * @return 背景图文件名 (md5.suffix), 调用方用于 setCurBg
     */
    fun setBgFromPath(path: String): String {
        val bytes = FileUtilsCommon.readBytes(path)
            ?: error("读取图片失败: $path")
        val suffix = path.substringAfterLast('.', "unknown")
        val digest = Md5Digest()
        digest.update(bytes, 0, bytes.size)
        val fileName = digest.digest().toHexLower() + ".$suffix"

        val filesBase = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
        val sep = BackupFileOps.separator
        val bgDir = filesBase + sep + "bg"
        val bgPath = bgDir + sep + fileName
        if (!BackupFileOps.exists(bgPath)) {
            BackupFileOps.createFolderIfNotExist(bgDir)
            check(FileUtilsCommon.writeBytes(bgPath, bytes)) { "写入背景图失败: $bgPath" }
        }
        return fileName
    }

    /** 导入单套背景：图片类型 (bgType==2) 落地到 `{files}/bg` 并返回新路径，颜色类型仅校验 hex。 */
    private fun importBg(
        bgType: Int,
        bgStr: String,
        configDir: String,
        filesBase: String,
        sep: String
    ): String {
        if (bgType == 0) {
            ColorUtils.parseColor(bgStr)
            return bgStr
        }
        if (bgType != 2) return bgStr
        val bgName = bgStr.substringAfterLast(sep).substringAfterLast('/')
        val bgPath = filesBase + sep + "bg" + sep + bgName
        if (!BackupFileOps.exists(bgPath)) {
            val bgFile = configDir + sep + bgName
            if (BackupFileOps.exists(bgFile)) {
                BackupFileOps.copyFile(bgFile, bgPath)
            }
        }
        return bgPath
    }

    /** 收集所有图片背景资源字符串（bgType==2），供清理缓存用。 */
    fun getAllPicBgStr(): List<String> {
        val list = mutableListOf<String>()
        configList.forEach {
            if (it.bgType == 2) list.add(it.bgStr)
            if (it.bgTypeNight == 2) list.add(it.bgStrNight)
            if (it.bgTypeEInk == 2) list.add(it.bgStrEInk)
        }
        return list
    }

    /**
     * 清理阅读背景缓存图片 + readConfig 临时缓存 (对照 app 端 `ReadBookConfig.clearBgAndCache`)。
     *
     * # 行为
     * 1. 收集 [configList] 中所有 bgType==2 的背景路径 (bgStr/bgStrNight/bgStrEInk),
     *    含分隔符视为绝对路径, 否则拼接为 `{externalFilesDir ?: filesDir}/bg/{bgStr}`
     *    (与 app 端 `Config.getBgPath` 逻辑一致)
     * 2. 删除 `{externalFilesDir ?: filesDir}/bg/` 目录下不在白名单中的文件
     * 3. 删除 `{externalCacheDir ?: cacheDir}/readConfig` 目录 (导入配置临时解压目录)
     * 4. 删除 `{externalCacheDir ?: cacheDir}/readConfig.zip` (导入配置 zip 临时文件)
     *
     * # 平台差异
     * - app 端: `externalFilesDir` / `externalCacheDir` 由 Android Context 提供
     * - 桌面端: 均为 null, 回退到 `filesDir` (~/.legado/files) / `cacheDir` (~/.legado/cache)
     * - 桌面端 BgTextConfigDialog 用户选图直接存绝对路径, bg/ 目录通常不存在, 清理为 no-op
     */
    fun clearBgAndCache() {
        val filesBase = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
        val cacheBase = AppFilesDirs.get().externalCacheDir ?: AppFilesDirs.get().cacheDir
        val sep = BackupFileOps.separator

        // 1. 收集白名单 (bgType==2 的背景路径, 含绝对路径和 bg/ 目录下文件名)
        val bgs = hashSetOf<String>()
        configList.forEach { config ->
            repeat(3) {
                config.getBgPath(it)?.let { path ->
                    bgs.add(path)
                }
            }
        }

        // 2. 删除 bg 目录中不在白名单的文件 (目录不存在 listFiles 返回 null, 跳过)
        val bgDir = filesBase + sep + "bg"
        BackupFileOps.listFiles(bgDir)?.forEach { filePath ->
            if (filePath !in bgs) {
                BackupFileOps.delete(filePath)
            }
        }

        // 3. 删除 readConfig 缓存目录 + zip 临时文件 (与 app 端 externalCache/readConfig 对齐)
        BackupFileOps.delete(cacheBase + sep + "readConfig")
        BackupFileOps.delete(cacheBase + sep + "readConfig.zip")
    }

    // -------------------- 排版字段 (全部委托 config，与 app 端委托关系一一对应) --------------------

    /** 背景透明度。 */
    var bgAlpha: Int
        get() = config.bgAlpha
        set(value) {
            config.bgAlpha = value
        }

    /** 翻页动画（[PageAnim.Anim]，按 E-Ink 模式分派）。 */
    var pageAnim: Int
        get() = config.curPageAnim()
        set(@PageAnim.Anim value) {
            config.setCurPageAnim(value)
        }

    /** 字体名/路径。 */
    var textFont: String
        get() = config.textFont
        set(value) {
            config.textFont = value
        }

    /** 是否粗体字 0:正常 1:粗体 2:细体。 */
    var textBold: Int
        get() = config.textBold
        set(value) {
            config.textBold = value
        }

    /** 文字大小。 */
    var textSize: Int
        get() = config.textSize
        set(value) {
            config.textSize = value
        }

    /** 字间距。 */
    var letterSpacing: Float
        get() = config.letterSpacing
        set(value) {
            config.letterSpacing = value
        }

    /** 行间距。 */
    var lineSpacingExtra: Int
        get() = config.lineSpacingExtra
        set(value) {
            config.lineSpacingExtra = value
        }

    /** 段距。 */
    var paragraphSpacing: Int
        get() = config.paragraphSpacing
        set(value) {
            config.paragraphSpacing = value
        }

    /** 标题位置 0:居左 1:居中 2:隐藏。 */
    var titleMode: Int
        get() = config.titleMode
        set(value) {
            config.titleMode = value
        }

    /** 标题字号。 */
    var titleSize: Int
        get() = config.titleSize
        set(value) {
            config.titleSize = value
        }

    /** 是否标题居中（与 app 端 `isMiddleTitle` 一致）。 */
    val isMiddleTitle: Boolean
        get() = titleMode == 1

    /** 标题顶部间距。 */
    var titleTopSpacing: Int
        get() = config.titleTopSpacing
        set(value) {
            config.titleTopSpacing = value
        }

    /** 标题底部间距。 */
    var titleBottomSpacing: Int
        get() = config.titleBottomSpacing
        set(value) {
            config.titleBottomSpacing = value
        }

    /** 段落缩进。 */
    var paragraphIndent: String
        get() = config.paragraphIndent
        set(value) {
            config.paragraphIndent = value
        }

    /** 下划线。 */
    var underline: Boolean
        get() = config.underline
        set(value) {
            config.underline = value
        }

    /** 正文下边距。 */
    var paddingBottom: Int
        get() = config.paddingBottom
        set(value) {
            config.paddingBottom = value
        }

    /** 正文左边距。 */
    var paddingLeft: Int
        get() = config.paddingLeft
        set(value) {
            config.paddingLeft = value
        }

    /** 正文右边距。 */
    var paddingRight: Int
        get() = config.paddingRight
        set(value) {
            config.paddingRight = value
        }

    /** 正文上边距。 */
    var paddingTop: Int
        get() = config.paddingTop
        set(value) {
            config.paddingTop = value
        }

    /** 页眉下边距。 */
    var headerPaddingBottom: Int
        get() = config.headerPaddingBottom
        set(value) {
            config.headerPaddingBottom = value
        }

    /** 页眉左边距。 */
    var headerPaddingLeft: Int
        get() = config.headerPaddingLeft
        set(value) {
            config.headerPaddingLeft = value
        }

    /** 页眉右边距。 */
    var headerPaddingRight: Int
        get() = config.headerPaddingRight
        set(value) {
            config.headerPaddingRight = value
        }

    /** 页眉上边距。 */
    var headerPaddingTop: Int
        get() = config.headerPaddingTop
        set(value) {
            config.headerPaddingTop = value
        }

    /** 页脚下边距。 */
    var footerPaddingBottom: Int
        get() = config.footerPaddingBottom
        set(value) {
            config.footerPaddingBottom = value
        }

    /** 页脚左边距。 */
    var footerPaddingLeft: Int
        get() = config.footerPaddingLeft
        set(value) {
            config.footerPaddingLeft = value
        }

    /** 页脚右边距。 */
    var footerPaddingRight: Int
        get() = config.footerPaddingRight
        set(value) {
            config.footerPaddingRight = value
        }

    /** 页脚上边距。 */
    var footerPaddingTop: Int
        get() = config.footerPaddingTop
        set(value) {
            config.footerPaddingTop = value
        }

    /** 是否显示页眉分隔线。 */
    var showHeaderLine: Boolean
        get() = config.showHeaderLine
        set(value) {
            config.showHeaderLine = value
        }

    /** 是否显示页脚分隔线。 */
    var showFooterLine: Boolean
        get() = config.showFooterLine
        set(value) {
            config.showFooterLine = value
        }

    /**
     * 页眉/页脚 tip 槽位与模式。
     *
     * app 端由 `ReadTipConfig` 直接读写 `ReadBookConfig.config`，
     * Shared 版 [ReadTipConfigShared] 转发到本类，故这里补上同名委托。
     */
    var tipHeaderLeft: Int
        get() = config.tipHeaderLeft
        set(value) {
            config.tipHeaderLeft = value
        }

    var tipHeaderMiddle: Int
        get() = config.tipHeaderMiddle
        set(value) {
            config.tipHeaderMiddle = value
        }

    var tipHeaderRight: Int
        get() = config.tipHeaderRight
        set(value) {
            config.tipHeaderRight = value
        }

    var tipFooterLeft: Int
        get() = config.tipFooterLeft
        set(value) {
            config.tipFooterLeft = value
        }

    var tipFooterMiddle: Int
        get() = config.tipFooterMiddle
        set(value) {
            config.tipFooterMiddle = value
        }

    var tipFooterRight: Int
        get() = config.tipFooterRight
        set(value) {
            config.tipFooterRight = value
        }

    /** tip 颜色索引。 */
    var tipColor: Int
        get() = config.tipColor
        set(value) {
            config.tipColor = value
        }

    /** tip 分隔线颜色索引（-1 表示不显示）。 */
    var tipDividerColor: Int
        get() = config.tipDividerColor
        set(value) {
            config.tipDividerColor = value
        }

    /** 页眉显示模式（0:状态栏显示时隐藏 1:显示 2:隐藏）。 */
    var headerMode: Int
        get() = config.headerMode
        set(value) {
            config.headerMode = value
        }

    /** 页脚显示模式（0:显示 1:隐藏）。 */
    var footerMode: Int
        get() = config.footerMode
        set(value) {
            config.footerMode = value
        }

    companion object {
        /** 与 app 端 `ReadBookConfig.configFileName` 一致。 */
        const val configFileName = "readConfig.json"

        /** 与 app 端 `ReadBookConfig.shareConfigFileName` 一致。 */
        const val shareConfigFileName = "shareReadConfig.json"
    }
}

/**
 * 样式主题配置（对应 app 端 `ReadBookConfig.Config`，52 个序列化字段逐一对齐）。
 *
 * JSON 字段名与 app 端完全一致，两端 `readConfig.json` 可互相恢复：
 * 三个颜色字符串在 Kotlin 侧叫 `textColorStr*`（避开与运行时 Int 缓存 [textColor] 重名），
 * 序列化时经 [SerialName] 还原为 app 端的 `textColor` / `textColorNight` / `textColorEInk`。
 *
 * 不包含 Drawable / Bitmap / File 操作，背景图渲染由各平台 UI 自行完成。
 */
@Serializable
data class ReadStyleConfig(
    var name: String = "",
    var bgStr: String = "#EEEEEE",// 白天背景
    var bgStrNight: String = "#000000",// 夜间背景
    var bgStrEInk: String = "#FFFFFF",// EInk 背景
    var bgAlpha: Int = 100,// 背景透明度
    var bgType: Int = 0,// 白天背景类型 0:颜色 1:assets图片 2:其它图片
    var bgTypeNight: Int = 0,
    var bgTypeEInk: Int = 0,
    var darkStatusIcon: Boolean = true,
    var darkStatusIconNight: Boolean = false,
    var darkStatusIconEInk: Boolean = true,
    @SerialName("textColor") var textColorStr: String = "#3E3D3B",
    @SerialName("textColorNight") var textColorStrNight: String = "#ADADAD",
    @SerialName("textColorEInk") var textColorStrEInk: String = "#000000",
    var pageAnim: Int = 0,// 翻页动画
    var pageAnimEInk: Int = PageAnim.noAnim,
    var textFont: String = "",// 字体
    var textBold: Int = 0,// 是否粗体字 0:正常 1:粗体 2:细体
    var textSize: Int = 20,// 文字大小
    var letterSpacing: Float = 0.1f,// 字间距
    var lineSpacingExtra: Int = 12,// 行间距
    var paragraphSpacing: Int = 2,// 段距
    var titleMode: Int = 0,// 标题位置 0:居左 1:居中 2:隐藏
    var titleSize: Int = 0,
    var titleTopSpacing: Int = 0,
    var titleBottomSpacing: Int = 0,
    var paragraphIndent: String = "　　",// 段落缩进
    var underline: Boolean = false,// 下划线
    var paddingBottom: Int = 6,
    var paddingLeft: Int = 16,
    var paddingRight: Int = 16,
    var paddingTop: Int = 6,
    var headerPaddingBottom: Int = 0,
    var headerPaddingLeft: Int = 16,
    var headerPaddingRight: Int = 16,
    var headerPaddingTop: Int = 0,
    var footerPaddingBottom: Int = 6,
    var footerPaddingLeft: Int = 16,
    var footerPaddingRight: Int = 16,
    var footerPaddingTop: Int = 6,
    // 页眉线默认不显示 (对齐原版 ReadBookConfig.Config.showHeaderLine=false);
    // 内置主题"微信读书"在 readConfig.json 中显式配置 true, 不受此默认值影响。
    var showHeaderLine: Boolean = false,
    var showFooterLine: Boolean = true,
    var tipHeaderLeft: Int = ReadTipConfigShared.time,
    var tipHeaderMiddle: Int = ReadTipConfigShared.none,
    var tipHeaderRight: Int = ReadTipConfigShared.battery,
    var tipFooterLeft: Int = ReadTipConfigShared.chapterTitle,
    var tipFooterMiddle: Int = ReadTipConfigShared.none,
    var tipFooterRight: Int = ReadTipConfigShared.pageAndTotal,
    var tipColor: Int = 0,
    var tipDividerColor: Int = -1,
    var headerMode: Int = 0,
    var footerMode: Int = 0,
    /** 白天文字颜色 Int 缓存（对应 app 端 `@Transient textColorInt`，不参与序列化）。 */
    @Transient var textColor: Int = 0,
    /** 背景主色 Int（各端取色后写入，不参与序列化）。 */
    @Transient var bgMeanColor: Int = 0,
) {

    @Transient
    private var textColorIntNight = 0

    @Transient
    private var textColorIntEInk = 0

    @Transient
    private var initColorInt = false

    /** 是否 E-Ink 模式（AppConfigProviders 未注册时按 false 处理，不影响样式读写）。 */
    private val isEInk: Boolean
        get() = runCatching { AppConfigProviders.get().isEInkMode }.getOrDefault(false)

    /** 是否夜间主题（AppConfigProviders 未注册时按 false 处理）。 */
    private val isNight: Boolean
        get() = runCatching { AppConfigProviders.get().isNightTheme }.getOrDefault(false)

    private fun initColorInt() {
        textColorIntEInk = parseColorOr(textColorStrEInk, 0xFF000000.toInt())
        textColorIntNight = parseColorOr(textColorStrNight, 0xFFADADAD.toInt())
        textColor = parseColorOr(textColorStr, 0xFF3E3D3B.toInt())
        initColorInt = true
    }

    private fun parseColorOr(str: String, fallback: Int): Int =
        runCatching { ColorUtils.parseColor(str) }.getOrDefault(fallback)

    /** 当前生效文字颜色 Int（与 app 端 `curTextColor` 一致）。 */
    fun curTextColor(): Int {
        if (!initColorInt) initColorInt()
        return when {
            isEInk -> textColorIntEInk
            isNight -> textColorIntNight
            else -> textColor
        }
    }

    /** 设置当前生效文字颜色（同时更新 hex 字符串与 Int 缓存）。 */
    fun setCurTextColor(color: Int) {
        when {
            isEInk -> {
                textColorStrEInk = "#${color.hexString}"
                textColorIntEInk = color
            }

            isNight -> {
                textColorStrNight = "#${color.hexString}"
                textColorIntNight = color
            }

            else -> {
                textColorStr = "#${color.hexString}"
                textColor = color
            }
        }
    }

    /** 当前是否暗色状态栏图标。 */
    fun curStatusIconDark(): Boolean = when {
        isEInk -> darkStatusIconEInk
        isNight -> darkStatusIconNight
        else -> darkStatusIcon
    }

    fun setCurStatusIconDark(isDark: Boolean) {
        when {
            isEInk -> darkStatusIconEInk = isDark
            isNight -> darkStatusIconNight = isDark
            else -> darkStatusIcon = isDark
        }
    }

    /** 当前翻页动画（E-Ink 模式走 [pageAnimEInk]）。 */
    fun curPageAnim(): Int = if (isEInk) pageAnimEInk else pageAnim

    fun setCurPageAnim(@PageAnim.Anim anim: Int) {
        if (isEInk) pageAnimEInk = anim else pageAnim = anim
    }

    /** 当前背景字符串（hex 或图片名/路径）。 */
    fun curBgStr(): String = when {
        isEInk -> bgStrEInk
        isNight -> bgStrNight
        else -> bgStr
    }

    /** 当前背景类型 0:颜色 1:assets 图片 2:其它图片。 */
    fun curBgType(): Int = when {
        isEInk -> bgTypeEInk
        isNight -> bgTypeNight
        else -> bgType
    }

    /**
     * 当前生效背景图片的加载地址。
     *
     * - 内置图片使用 `bg://` 前缀，由各平台图片加载器负责缓存/下载；
     * - 用户图片沿用配置中的绝对路径或 `{files}/bg/{fileName}`；
     * - 纯色背景返回 null。
     */
    fun curBgImageSource(): String? {
        return when (curBgType()) {
            1 -> curBgStr().takeIf { it.isNotBlank() }?.let { "bg://$it" }
            2 -> {
                val bgIndex = when {
                    isEInk -> 2
                    isNight -> 1
                    else -> 0
                }
                getBgPath(bgIndex)
            }

            else -> null
        }
    }

    /**
     * 当前生效背景色（ARGB，各端 Canvas/Compose 渲染用）。
     * 纯色背景（bgType==0）按 [curBgStr] 解析并按 [bgAlpha] 折算透明度；
     * 图片背景用 [bgMeanColor]（代表色，各端渲染背景图后写入）。
     * 对应 app 端 `ReadBookConfig.upBg()` 产出的 `bgMeanColor` + `bg.alpha`。
     *
     * 非 Android 端没有 Drawable 取色回写时，图片首次绘制前 [bgMeanColor] 可能为 0。
     * 返回一个不透明的日/夜兜底色，避免翻页层在异步图片加载期间再次透出窗口背景。
     */
    fun curBgColor(): Int {
        if (curBgType() != 0) {
            if (bgMeanColor != 0) return bgMeanColor or 0xFF000000.toInt()
            return when {
                isEInk || !isNight -> 0xFFFFFFFF.toInt()
                else -> 0xFF000000.toInt()
            }
        }
        val alpha = (bgAlpha / 100f * 255).toInt().coerceIn(0, 255)
        return runCatching { ColorUtils.parseColor(curBgStr()) }.getOrDefault(0)
            .let { (it and 0x00FFFFFF) or (alpha shl 24) }
    }

    fun setCurBg(bgType: Int, bg: String) {
        when {
            isEInk -> {
                bgTypeEInk = bgType
                bgStrEInk = bg
            }

            isNight -> {
                bgTypeNight = bgType
                bgStrNight = bg
            }

            else -> {
                this.bgType = bgType
                bgStr = bg
            }
        }
    }

    /**
     * 一次性写入白天/夜间背景与文字颜色 (对照原版预设: 每套样式都有
     * bgStr+textColor 与 bgStrNight+textColorNight 两套字段)。
     *
     * 与 [setCurTextColor] / [setCurBg] 只写当前日/夜/EInk 分支不同, 这里两套一起写并同步
     * 各自的 Int 缓存, 保证切换日/夜主题后 [curTextColor] / [curBgColor] 不残留上一套颜色;
     * 同时把日/夜背景类型置为 0 (纯色), 避免残留图片背景类型时 curBgColor 走 bgMeanColor 分支。
     * (阅读配置面板预设切换用, 对照原版 ReadStyleDialog 切换主题整包替换配置)
     */
    fun setPresetColor(
        bgStr: String,
        bgStrNight: String,
        textColorStr: String,
        textColorStrNight: String,
        textColor: Int,
        textColorNight: Int,
        bgMeanColor: Int,
    ) {
        this.bgStr = bgStr
        this.bgStrNight = bgStrNight
        this.textColorStr = textColorStr
        this.textColorStrNight = textColorStrNight
        this.textColor = textColor
        textColorIntNight = textColorNight
        this.bgMeanColor = bgMeanColor
        bgType = 0
        bgTypeNight = 0
    }

    /**
     * 取指定索引的背景图片路径 (对照 app 端 `ReadBookConfig.Config.getBgPath`)。
     *
     * @param bgIndex 0:白天 1:夜间 2:E-Ink
     * @return bgType==2 时返回路径 (含分隔符视为绝对路径, 否则拼 `{files}/bg/{bgStr}`); 否则 null
     */
    fun getBgPath(bgIndex: Int): String? {
        val bgType = when (bgIndex) {
            0 -> bgType
            1 -> bgTypeNight
            2 -> bgTypeEInk
            else -> error("unknown bgIndex: $bgIndex")
        }
        if (bgType != 2) {
            return null
        }
        val bgStr = when (bgIndex) {
            0 -> bgStr
            1 -> bgStrNight
            2 -> bgStrEInk
            else -> error("unknown bgIndex: $bgIndex")
        }
        val sep = BackupFileOps.separator
        val filesBase = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
        // 配置可能来自另一平台，Windows 路径和 URI 也可能使用与当前平台不同的分隔符。
        return if (bgStr.contains(sep) || bgStr.contains('/') || bgStr.contains('\\')) {
            bgStr
        } else {
            filesBase + sep + "bg" + sep + bgStr
        }
    }
}
