package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.BuildConfig
import io.legado.app.constant.PreferKey
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openInputStream
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import splitties.init.appCtx
import java.io.File

/**
 * 恢复 (app 端薄壳)。
 *
 * 读 JSON / 写库 / 配置回写全部在 [RestoreShared], 本 object 只留 Android 专属段:
 * SAF(content://) 解压、旧版 config.xml 解析、主题重载、恢复后 toast/图标/日夜间刷新,
 * 由 [AndroidBackupRestoreHook] 转调, 保证 WebDav 与本地恢复走同一条核心路径。
 */
object Restore {

    /** 从 zip 恢复 (content:// 与本地路径都支持), 与原实现同流程。 */
    @Suppress("UNUSED_PARAMETER")
    suspend fun restore(context: Context, uri: Uri) {
        RestoreShared.restoreFromZip(uri.toString())
    }

    suspend fun restoreLocked(path: String) {
        RestoreShared.restoreLocked(path)
    }

    /**
     * 解压备份 zip: content:// 走 FileDoc 输入流, file:// 取真实路径。
     *
     * @return true 表示已处理, false 交回 shared 按普通文件路径解压
     */
    fun unZipBackup(zipPath: String, destDir: String): Boolean {
        if (zipPath.isContentScheme()) {
            FileDoc.fromUri(zipPath.toUri(), false).openInputStream().getOrThrow().use {
                ZipUtils.unZipToPath(it, destDir)
            }
            return true
        }
        val uri = zipPath.toUri()
        if (uri.scheme == "file") {
            ZipUtils.unZipToPath(File(uri.path!!), destDir)
            return true
        }
        return false
    }

    /** 恢复主题配置文件后重载内存列表。 */
    fun upThemeConfig() {
        ThemeConfig.upConfig()
    }

    /** 恢复完成: 提示 + 切换图标 + 应用日夜间 (与原实现一致)。 */
    suspend fun onRestoreFinished() {
        appCtx.toastOnUi(androidAppString("restore_success"))
        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    /**
     * 兼容旧版本 XML 备份 (config.xml), 用 XmlPullParser 稳健解析。
     *
     * @return 解析出的配置 map, 文件不存在返回 null
     */
    fun readLegacyConfigXml(dirPath: String): Map<String, Any?>? {
        val xmlFile = File(dirPath, "config.xml")
        if (!xmlFile.exists()) return null
        val configMap = mutableMapOf<String, Any?>()
        kotlin.runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xmlFile.inputStream(), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    if (tag != "map" && tag != "xml") {
                        val name = parser.getAttributeValue(null, "name")
                        if (name != null) {
                            when (tag) {
                                "string" -> configMap[name] = parser.nextText()
                                "int" -> configMap[name] =
                                    parser.getAttributeValue(null, "value")?.toInt()

                                "boolean" -> configMap[name] =
                                    parser.getAttributeValue(null, "value")?.toBoolean()

                                "long" -> configMap[name] =
                                    parser.getAttributeValue(null, "value")?.toLong()

                                "float" -> configMap[name] =
                                    parser.getAttributeValue(null, "value")?.toFloat()

                                "set" -> {
                                    val set = mutableSetOf<String>()
                                    val depth = parser.depth
                                    while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") {
                                            set.add(parser.nextText())
                                        }
                                    }
                                    configMap[name] = set
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }.onFailure {
            it.printOnDebug()
        }
        return configMap
    }
}
