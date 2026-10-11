package io.legado.app.help

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.DirectLinkUploadStoreProviders.get
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import io.legado.app.utils.toJsonElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * 直链上传的 KMP 共享版 (与 [RestoreShared] / [BackupShared] 配套): 规则数据类、配置
 * Provider 接口与 [upLoadToDirectLink] 编排。
 *
 * # 背景
 * app 端 [io.legado.app.help.DirectLinkUpload] 单例依赖大量 Android-specific 类
 * (ACache 磁盘缓存 / appCtx.assets 读取默认规则 / ZipUtils+FileUtils 压缩 /
 * java.io.File 类型判断 / appCtx.externalCache 临时目录 / AnalyzeUrl app 端子类),
 * 不能整体下沉 shared/commonMain。本 KMP 版把这些平台面全部收敛成注入点:
 * - [DirectLinkUploadRule] 纯数据类 (GSON 序列化, 与 app 端字段完全一致)
 * - [ruleFileName] 常量 (BackupShared/RestoreShared 备份恢复时引用)
 * - 配置读写 + 默认规则的 Provider 接口 (宿主注入实现)
 * - [DirectLinkUploadPrimitives]: 平台文件载荷读取 + 单条目 zip 压缩两个原语 (各端入口注入)
 * - [upLoadToDirectLink] 编排 (四端同一份) / [getRuleShared] / [getSummaryShared] 访问点
 *
 * # 与 app 端 [io.legado.app.help.DirectLinkUpload] 的差异
 * - **平台文件载荷与压缩**: java.io.File / ZipUtils / FileUtils 无法在 commonMain 表达,
 *   通过 [DirectLinkUploadPrimitives] 注入 (Android/桌面用 ZipUtils, iOS/鸿蒙用 NativeZipCodec)
 * - **不下沉 defaultRules 直接读取**: 依赖 appCtx.assets (Android assets), 通过
 *   [DirectLinkUploadDefaultsProvider] 注入
 * - **不下沉 getConfig/putConfig 的 ACache 访问**: ACache 为 Android 文件缓存, 通过
 *   [DirectLinkUploadStoreProvider] 注入
 * - **@Keep 注解移除**: commonMain 无 androidx.annotation, 反射保活由 consumer-rules.pro
 *   的 `-keep class io.legado.app.help.DirectLinkUploadRule { *; }` 覆盖
 *   (照 OldRssSource / AnalyzeByXPath 先例)
 *
 * # 命名后缀 Shared
 * 与 app 端 [io.legado.app.help.DirectLinkUpload] 同包同名会冲突, 故 Rule 类提到顶层
 * 命名 [DirectLinkUploadRule] (app 端用 typealias `DirectLinkUpload.Rule` 保持兼容)。
 *
 * # 模式参考
 * - app 端 [io.legado.app.help.DirectLinkUpload] (业务对照原型)
 * - shared/commonMain [io.legado.app.help.config.PasswordProviders] (Provider 注入先例)
 * - shared/commonMain [io.legado.app.help.ExploreKindsCacheProviders] (ACache 抽象先例)
 */

/**
 * 直链上传规则数据结构。
 *
 * 与 app 端 `DirectLinkUpload.Rule` 字段完全一致 (uploadUrl/downloadUrlRule/summary/compress),
 * GSON 序列化兼容 (kotlinx.serialization @Serializable, 字段名/类型/默认值零变化)。
 *
 * @Keep 注解移除: commonMain 无 androidx.annotation, 由 consumer-rules.pro keep 覆盖。
 */
@Serializable
data class DirectLinkUploadRule(
    var uploadUrl: String, //创建分享链接
    var downloadUrlRule: String, //下载链接规则
    var summary: String, //注释
    var compress: Boolean = false, //是否压缩
) {

    override fun toString(): String {
        return summary
    }
}

/**
 * 直链上传规则文件名常量 (与 app 端 [io.legado.app.help.DirectLinkUpload.ruleFileName] 一致)。
 *
 * BackupShared/RestoreShared 备份恢复时引用本常量, 避免硬编码字符串。
 */
const val ruleFileName = "directLinkUploadRule.json"

/**
 * 直链上传配置 (读写) Provider 接口。
 *
 * app 端 [io.legado.app.help.DirectLinkUpload] 的 getConfig/putConfig 依赖 ACache
 * (Android 文件缓存, 无法下沉 shared)。宿主启动早期通过 [DirectLinkUploadStoreProviders.register]
 * 注入实现, shared 内通过 [DirectLinkUploadStoreProviders.get] 获取。
 *
 * 模式参考 [io.legado.app.help.ExploreKindsCacheProvider] (ACache 抽象先例)。
 */
interface DirectLinkUploadStoreProvider {

    /** 读取已保存的规则, 无配置返回 null (与 app 端 `DirectLinkUpload.getConfig()` 行为一致)。 */
    fun getConfig(): DirectLinkUploadRule?

    /** 保存规则 (与 app 端 `DirectLinkUpload.putConfig(rule)` 行为一致)。 */
    fun putConfig(rule: DirectLinkUploadRule)

    /**
     * 保存规则的原始 JSON (恢复备份用)。
     *
     * 默认先解析再 [putConfig]; app 端覆写为原样写入, 与原 `ACache.put(ruleFileName, json)` 逐字一致。
     */
    fun putConfigJson(json: String) {
        parseDirectLinkUploadRule(json)?.let { putConfig(it) }
    }
}

/**
 * [DirectLinkUploadStoreProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `DirectLinkUploadStoreProviders.get()?.getConfig()` 替代
 * 直接 ACache 调用, 行为完全一致, 仅多一层 provider 间接。
 * 未注册时 [get] 返回 null (等价 app 端 getConfig() 为 null 时跳过的行为)。
 */
object DirectLinkUploadStoreProviders {

    @Volatile
    private var impl: DirectLinkUploadStoreProvider? = null

    /** 宿主启动早期注册一次 (任何 shared 调用之前)。 */
    fun register(impl: DirectLinkUploadStoreProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null (等价 app 端无配置时的 null 行为)。 */
    fun get(): DirectLinkUploadStoreProvider? = impl
}

/**
 * 直链上传默认规则 Provider 接口。
 *
 * app 端 [io.legado.app.help.DirectLinkUpload] 的 defaultRules 依赖 appCtx.assets
 * (Android assets, 无法下沉 shared)。宿主启动早期通过
 * [DirectLinkUploadDefaultsProviders.register] 注入实现。
 *
 * 模式参考 [io.legado.app.help.config.PasswordProvider] (assets/Context 抽象先例)。
 */
interface DirectLinkUploadDefaultsProvider {

    /** 返回内置默认规则列表 (与 app 端 `DirectLinkUpload.defaultRules` 行为一致)。 */
    fun getDefaultRules(): List<DirectLinkUploadRule>
}

/**
 * [DirectLinkUploadDefaultsProvider] 容器。宿主启动早期注册一次。
 */
object DirectLinkUploadDefaultsProviders {

    @Volatile
    private var impl: DirectLinkUploadDefaultsProvider? = null

    /** 宿主启动早期注册一次 (任何 shared 调用之前)。 */
    fun register(impl: DirectLinkUploadDefaultsProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null。 */
    fun get(): DirectLinkUploadDefaultsProvider? = impl
}

/**
 * 直链上传的平台载荷原语 (各端入口注入)。
 *
 * 平台差异只有两处, 都在本接口内:
 * - 平台文件载荷 (Android/桌面 `java.io.File`; iOS/鸿蒙 `io.legado.app.utils.File`) 的字节读取
 * - 单条目 zip 的压缩器 (Android/桌面 `ZipUtils`; iOS/鸿蒙 `NativeZipCodec`)
 */
interface DirectLinkUploadPrimitives {

    /** 平台文件载荷的字节内容; [file] 不是本平台文件类型时返回 null (由编排按通用分支处理)。 */
    fun readPlatformFileOrNull(file: Any): ByteArray?

    /**
     * 单条目 zip: 条目名 [entryName], 内容 [data]。
     * 失败必须抛异常 (静默返回会导致把坏包当上传内容发出)。
     */
    fun zipBytes(data: ByteArray, entryName: String): ByteArray
}

/**
 * 直链上传编排 (四端同一份): 校验规则 → 按需压缩打包 → 上传 → 按下载规则解析直链。
 *
 * 压缩判定与 app 端一致 (`rule.compress && contentType != "application/zip"`): 载荷压成单条目
 * zip (条目名 = `fileName`), 上传文件名加 `.zip` 后缀, contentType 换成 `application/zip`;
 * 平台文件载荷经 [DirectLinkUploadPrimitives.readPlatformFileOrNull] 读成字节后同路压缩,
 * ByteArray/String/其他对象分别取原字节/UTF-8 字节/JSON 文本。不压缩时载荷原样上传。
 *
 * 上传走 [AnalyzeUrlFactories] (各端注册的平台子类: url 内 `,{json}` 选项与 js 表达式命中各端
 * 注册的 JS 引擎), 直链解析走 [AnalyzeRuleFactories]; 解析结果为空时抛错并带上响应体。
 *
 * [rule] 默认取当前生效规则; 直链上传配置页的“测试”按钮传入表单里尚未保存的规则。
 */
suspend fun upLoadToDirectLink(
    primitives: DirectLinkUploadPrimitives,
    fileName: String,
    file: Any,
    contentType: String,
    rule: DirectLinkUploadRule = getRuleShared(),
): String {
    val url = rule.uploadUrl
    if (url.isBlank()) {
        throw NoStackTraceException(appString(AppStringKey.upload_url_not_configured))
    }
    val downloadUrlRule = rule.downloadUrlRule
    if (downloadUrlRule.isBlank()) {
        throw NoStackTraceException(appString(AppStringKey.download_url_rule_not_configured))
    }
    var mFileName = fileName
    var mFile = file
    var mContentType = contentType
    if (rule.compress && contentType != "application/zip") {
        mFileName = "$fileName.zip"
        mContentType = "application/zip"
        val raw = primitives.readPlatformFileOrNull(file)
            ?: when (file) {
                is ByteArray -> file
                is String -> file.encodeToByteArray()
                // 任意对象按 JSON 文本压缩 (GSON 反射序列化)
                else -> file.toJsonElement().toString().encodeToByteArray()
            }
        mFile = primitives.zipBytes(raw, fileName)
    }
    val analyzeUrl = AnalyzeUrlFactories.create(rawUrl = url)
    val res = analyzeUrl.upload(mFileName, mFile, mContentType)
    val analyzeRule = AnalyzeRuleFactories.create().apply {
        setContent(res.body, res.url)
        coroutineContext = currentCoroutineContext()
    }
    // commonMain 的 Closeable 是自定 expect 接口, 没有 kotlin.io 的 use 扩展, 手写 close
    return try {
        val downloadUrl = analyzeRule.getString(downloadUrlRule)
        if (downloadUrl.isBlank()) {
            throw NoStackTraceException(appString(AppStringKey.upload_failed_with_body, res.body))
        }
        downloadUrl
    } finally {
        analyzeRule.close()
    }
}

/**
 * 获取当前生效的直链上传规则 (跨平台访问点)。
 *
 * 与 app 端 [io.legado.app.help.DirectLinkUpload.getRule] 同语义:
 * - 优先返回已保存配置 [DirectLinkUploadStoreProvider.getConfig]
 * - 无配置时返回默认规则列表的第一条 [DirectLinkUploadDefaultsProvider.getDefaultRules]
 * - 均不可用时抛 [IllegalStateException]
 *
 * @throws IllegalStateException StoreProvider 和 DefaultsProvider 均未注册, 或默认规则列表为空
 */
fun getRuleShared(): DirectLinkUploadRule {
    DirectLinkUploadStoreProviders.get()?.getConfig()?.let { return it }
    val defaults = DirectLinkUploadDefaultsProviders.get()?.getDefaultRules()
        ?: error("DirectLinkUploadDefaultsProvider not registered")
    if (defaults.isEmpty()) error("default rules is empty")
    return defaults[0]
}

/**
 * 获取当前规则的摘要文本 (跨平台访问点)。
 *
 * 与 app 端 [io.legado.app.help.DirectLinkUpload.getSummary] 同语义:
 * `getSummary() = getRule().summary`。
 */
fun getSummaryShared(): String = getRuleShared().summary

/**
 * 把规则序列化为 JSON 字符串 (跨平台工具, 复用 shared GSON 兼容层)。
 *
 * 与 app 端 `GSON.toJson(rule)` 行为一致, 供备份/剪贴板复制等场景用。
 */
fun DirectLinkUploadRule.toJsonString(): String = GSON.toJson(this)

/**
 * 从 JSON 字符串反序列化为规则 (跨平台工具, 复用 shared GSON 兼容层)。
 *
 * 与 app 端 `GSON.fromJsonObject<Rule>(json)` 行为一致。
 * 解析失败返回 null (与 app 端 getOrNull() 用法对齐)。
 */
fun parseDirectLinkUploadRule(json: String?): DirectLinkUploadRule? =
    GSON.fromJsonObject<DirectLinkUploadRule>(json).getOrNull()
