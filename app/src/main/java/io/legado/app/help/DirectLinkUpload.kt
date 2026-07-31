package io.legado.app.help

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.DirectLinkUpload.getConfig
import io.legado.app.help.DirectLinkUpload.putConfig
import io.legado.app.help.DirectLinkUpload.upLoad
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import io.legado.app.utils.toJsonElement
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.io.File

/**
 * 直链上传 Android 实现 (与 shared [DirectLinkUploadShared] 配套)。
 *
 * # 下沉说明
 * [DirectLinkUploadRule] data class / [ruleFileName] 常量 / 配置访问接口 已下沉
 * shared/commonMain (见 [DirectLinkUploadShared])。本 object 保留 Android 专属实现:
 * - [upLoad]: 依赖 java.io.File / ZipUtils / FileUtils / appCtx.externalCache / AnalyzeUrl(app 端子类)
 * - [getConfig] / [putConfig]: 依赖 ACache (Android 文件缓存)
 * - [defaultRules]: 依赖 appCtx.assets (composeResources 打进 assets 的 defaultData)
 *
 * 本 object 实现 [DirectLinkUploadStoreProvider] + [DirectLinkUploadDefaultsProvider],
 * 宿主启动早期通过 `registerAndroidDirectLinkUploadProviders()` 注册到 shared,
 * 供 shared 端 (如 BackupShared/RestoreShared) 跨平台访问。
 *
 * # 类型映射
 * 原 `DirectLinkUpload.Rule` → shared [DirectLinkUploadRule] (同包, 直接引用)
 * 原 `DirectLinkUpload.ruleFileName` → shared [ruleFileName] (同包 top-level 常量)
 *
 * # @Keep 注解移除
 * 原 Rule 上的 @Keep (androidx.annotation) 随 Rule 下沉移除,
 * 反射保活由 shared consumer-rules.pro 的 keep 规则覆盖。
 */
object DirectLinkUpload : DirectLinkUploadStoreProvider, DirectLinkUploadDefaultsProvider {

    @Throws(NoStackTraceException::class)
    suspend fun upLoad(
        fileName: String,
        file: Any,
        contentType: String,
        rule: DirectLinkUploadRule = getRule()
    ): String {
        val url = rule.uploadUrl
        if (url.isBlank()) {
            throw NoStackTraceException("上传url未配置")
        }
        val downloadUrlRule = rule.downloadUrlRule
        if (downloadUrlRule.isBlank()) {
            throw NoStackTraceException("下载地址规则未配置")
        }
        var mFileName = fileName
        var mFile = file
        var mContentType = contentType
        if (rule.compress && contentType != "application/zip") {
            mFileName = "$fileName.zip"
            mContentType = "application/zip"
            mFile = when (file) {
                is File -> {
                    val zipFile = File(FileUtils.getPath(appCtx.externalCache, "upload", mFileName))
                    zipFile.createFileReplace()
                    ZipUtils.zipFile(file, zipFile)
                    zipFile
                }

                is ByteArray -> ZipUtils.zipByteArray(file, fileName)
                is String -> ZipUtils.zipByteArray(file.toByteArray(), fileName)
                // Phase D: GSON.toJson(file) 反射序列化 Any → toJsonElement().toString() 处理任意类型
                else -> ZipUtils.zipByteArray(file.toJsonElement().toString().toByteArray(), fileName)
            }
        }
        val analyzeUrl = AnalyzeUrl(url)
        val res = analyzeUrl.upload(mFileName, mFile, mContentType)
        if (mFile is File) {
            mFile.delete()
        }
        val analyzeRule = AnalyzeRule().apply {
            setContent(res.body, res.url)
            coroutineContext = currentCoroutineContext()
        }
        return analyzeRule.use {
            val downloadUrl = it.getString(downloadUrlRule)
            if (downloadUrl.isBlank()) {
                throw NoStackTraceException("上传失败,${res.body}")
            }
            downloadUrl
        }
    }

    // 命名为 defaultRulesCache 避免与 override fun getDefaultRules() 的 JVM 签名 (getDefaultRules()) 冲突
    private val defaultRulesCache: List<DirectLinkUploadRule> by lazy {
        // 单一数据源在 shared/commonMain/composeResources/files/defaultData/ (打进 assets, 前缀含模块限定名)
        val json = String(
            appCtx.assets.open("${DEFAULT_DATA_ASSET_PREFIX}directLinkUpload.json")
                .readBytes()
        )
        GSON.fromJsonArray<DirectLinkUploadRule>(json).getOrThrow()
    }

    fun getRule(): DirectLinkUploadRule {
        return getConfig() ?: defaultRulesCache[0]
    }

    override fun getConfig(): DirectLinkUploadRule? {
        val json = ACache.get(cacheDir = false).getAsString(ruleFileName)
        return GSON.fromJsonObject<DirectLinkUploadRule>(json).getOrNull()
    }

    override fun putConfig(rule: DirectLinkUploadRule) {
        ACache.get(cacheDir = false).put(ruleFileName, GSON.toJson(rule))
    }

    /** 恢复备份时原样写回 JSON, 与原 Restore 的 ACache.put(ruleFileName, json) 一致。 */
    override fun putConfigJson(json: String) {
        ACache.get(cacheDir = false).put(ruleFileName, json)
    }

    override fun getDefaultRules(): List<DirectLinkUploadRule> = defaultRulesCache

    fun getSummary(): String {
        return getRule().summary
    }

}

/**
 * 安卓宿主启动早期注册 [DirectLinkUploadStoreProvider] + [DirectLinkUploadDefaultsProvider]。
 *
 * [DirectLinkUpload] object 已实现这两个接口, 注册后 shared/commonMain 内可通过
 * [DirectLinkUploadStoreProviders.get] / [DirectLinkUploadDefaultsProviders.get]
 * 跨平台访问直链上传配置 (如 BackupShared/RestoreShared 备份恢复 directLinkUploadRule.json)。
 *
 * 调用时机: App.onCreate, 在任何 shared 模块备份/恢复之前。
 *
 * 模式参考 [io.legado.app.help.storage.registerAndroidPasswordProvider] /
 * [io.legado.app.help.ExploreKindsCacheProviders] (JsEnginesAndroid.kt 注册先例)。
 */
fun registerAndroidDirectLinkUploadProviders() {
    DirectLinkUploadStoreProviders.register(DirectLinkUpload)
    DirectLinkUploadDefaultsProviders.register(DirectLinkUpload)
}
