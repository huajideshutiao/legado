package io.legado.desktop.help

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.DirectLinkUploadDefaultsProvider
import io.legado.app.help.DirectLinkUploadDefaultsProviders
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.DirectLinkUploadStoreProvider
import io.legado.app.help.DirectLinkUploadStoreProviders
import io.legado.app.help.ruleFileName
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import io.legado.app.utils.toJsonElement
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.util.prefs.Preferences

/**
 * 直链上传 desktop 实现 (与 shared [DirectLinkUploadStoreProvider] / [DirectLinkUploadDefaultsProvider] 配套)。
 *
 * 对照 app 端 [io.legado.app.help.DirectLinkUpload] object, 桌面端裁剪 Android 专属依赖:
 * - [upLoad]: 不依赖 app 端 [io.legado.app.model.analyzeRule.AnalyzeUrl] (app 子类含 getGlideUrl/
 *   getMediaItem 等 android-only 方法), 改用 shared/commonMain 下沉的 [AnalyzeUrlCore] 基类,
 *   其 [AnalyzeUrlCore.upload] 已是纯 JVM 逻辑 (经 OkHttpProxyClientProviders 走 OkHttpClient)。
 *   解析下载链接的 [AnalyzeRuleCore] 同样用 shared 下沉版, 依赖已注册的桌面端 JS 引擎
 *   (registerDesktopJsEngines)。压缩逻辑复用 shared jvmAndAndroidMain 的 [ZipUtils]。
 * - [getConfig] / [putConfig]: 不依赖 Android ACache, 改用 [java.util.prefs.Preferences]
 *   (参考 [io.legado.desktop.config.DesktopPreferenceProvider]), 配置落用户注册表 (Win) /
 *   ~/.java/.userPrefs (Linux/macOS), 重启仍可读。
 * - [defaultRulesCache]: 不依赖 appCtx.assets, 改从 classpath 读取
 *   `shared/src/commonMain/resources/defaultData/directLinkUpload.json`
 *   (参考 [io.legado.desktop.ui.about.AboutScreen] 读 /LICENSE.md 的模式)。
 *
 * 本 object 实现两个 provider 接口, 宿主启动早期通过 [registerDesktopDirectLinkUploadProviders]
 * 注册到 shared, 供 shared 端 (如 BackupShared/RestoreShared) 跨平台访问直链上传配置。
 *
 * # 类型映射 (与 app 端对齐)
 * - app 端 `DirectLinkUpload.Rule` → shared [DirectLinkUploadRule] (直接引用)
 * - app 端 `DirectLinkUpload.ruleFileName` → shared [ruleFileName] (top-level 常量)
 *
 * # 临时文件
 * app 端用 `appCtx.externalCache/upload/` 存压缩中间文件, 桌面端用
 * `System.getProperty("java.io.tmpdir")/legado/upload/` 等价替代, 上传后删除。
 */
object DesktopDirectLinkUpload : DirectLinkUploadStoreProvider, DirectLinkUploadDefaultsProvider {

    // 配置存储: java.util.prefs.Preferences (参考 DesktopPreferenceProvider)
    // 落用户注册表 (Win) / ~/.java/.userPrefs (Linux/macOS), 重启仍可读
    private val prefs: Preferences =
        Preferences.userNodeForPackage(DesktopDirectLinkUpload::class.java)

    // 临时上传目录: 系统临时目录 + legado/upload (对照 app 端 appCtx.externalCache/upload)
    private val uploadCacheDir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "legado${File.separator}upload").apply {
            mkdirs()
        }
    }

    @Throws(NoStackTraceException::class)
    suspend fun upLoad(
        fileName: String,
        file: Any,
        contentType: String,
        rule: DirectLinkUploadRule = getRule()
    ): String {
        val url = rule.uploadUrl
        if (url.isBlank()) {
            throw NoStackTraceException(jvmGetString("upload_url_not_configured"))
        }
        val downloadUrlRule = rule.downloadUrlRule
        if (downloadUrlRule.isBlank()) {
            throw NoStackTraceException(jvmGetString("download_url_rule_not_configured"))
        }
        var mFileName = fileName
        var mFile = file
        var mContentType = contentType
        if (rule.compress && contentType != "application/zip") {
            mFileName = "$fileName.zip"
            mContentType = "application/zip"
            mFile = when (file) {
                is File -> {
                    // 对照 app 端: externalCache/upload/mFileName + createFileReplace
                    val zipFile = File(uploadCacheDir, mFileName)
                    zipFile.parentFile?.mkdirs()
                    if (zipFile.exists()) zipFile.delete()
                    zipFile.createNewFile()
                    ZipUtils.zipFile(file, zipFile)
                    zipFile
                }

                is ByteArray -> ZipUtils.zipByteArray(file, fileName)
                is String -> ZipUtils.zipByteArray(file.toByteArray(), fileName)
                // 与 app 端一致: 任意类型用 toJsonElement().toString() 序列化后压缩
                else -> ZipUtils.zipByteArray(file.toJsonElement().toString().toByteArray(), fileName)
            }
        }
        // 用 shared 下沉的 AnalyzeUrlCore 基类 (app 端用 AnalyzeUrl 子类, 桌面端无 android-only 方法需求)
        val analyzeUrl = AnalyzeUrlCore(url)
        val res = analyzeUrl.upload(mFileName, mFile, mContentType)
        if (mFile is File) {
            mFile.delete()
        }
        val analyzeRule = AnalyzeRuleCore().apply {
            setContent(res.body, res.url)
            coroutineContext = currentCoroutineContext()
        }
        return analyzeRule.use {
            val downloadUrl = it.getString(downloadUrlRule)
            if (downloadUrl.isBlank()) {
                throw NoStackTraceException(jvmGetString("upload_failed_with_body", res.body))
            }
            downloadUrl
        }
    }

    // 命名为 defaultRulesCache 避免与 override fun getDefaultRules() 的 JVM 签名冲突
    // (与 app 端命名一致)
    private val defaultRulesCache: List<DirectLinkUploadRule> by lazy {
        // 从 classpath 读 shared/commonMain/resources/defaultData/directLinkUpload.json
        // (参考 AboutScreen 读 /LICENSE.md 的模式)
        val json = DesktopDirectLinkUpload::class.java.getResourceAsStream("/defaultData/directLinkUpload.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("default directLinkUpload.json not found on classpath")
        GSON.fromJsonArray<DirectLinkUploadRule>(json).getOrThrow()
    }

    fun getRule(): DirectLinkUploadRule {
        return getConfig() ?: defaultRulesCache[0]
    }

    override fun getConfig(): DirectLinkUploadRule? {
        val json = prefs.get(ruleFileName, null) ?: return null
        return GSON.fromJsonObject<DirectLinkUploadRule>(json).getOrNull()
    }

    override fun putConfig(rule: DirectLinkUploadRule) {
        prefs.put(ruleFileName, GSON.toJson(rule))
    }

    override fun getDefaultRules(): List<DirectLinkUploadRule> = defaultRulesCache

    fun getSummary(): String {
        return getRule().summary
    }

}

/**
 * 桌面端宿主启动早期注册 [DirectLinkUploadStoreProvider] + [DirectLinkUploadDefaultsProvider]。
 *
 * [DesktopDirectLinkUpload] object 已实现这两个接口, 注册后 shared/commonMain 内可通过
 * [DirectLinkUploadStoreProviders.get] / [DirectLinkUploadDefaultsProviders.get]
 * 跨平台访问直链上传配置 (如 BackupShared/RestoreShared 备份恢复 directLinkUploadRule.json)。
 *
 * 调用时机: desktop main 入口早期, 在 [io.legado.desktop.config.registerDesktopConfig]
 * (Preferences 就绪) 之后, 任何 shared 模块备份/恢复之前。
 *
 * 模式参考 app 端 [io.legado.app.help.registerAndroidDirectLinkUploadProviders] /
 * [io.legado.desktop.help.config.registerDesktopPasswordProvider]。
 */
fun registerDesktopDirectLinkUploadProviders() {
    DirectLinkUploadStoreProviders.register(DesktopDirectLinkUpload)
    DirectLinkUploadDefaultsProviders.register(DesktopDirectLinkUpload)
}
