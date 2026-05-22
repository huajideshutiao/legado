package io.legado.app.ui.book.read.config

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.readBytes
import java.io.File
import java.io.FileOutputStream

class BgTextConfigViewModel(app: Application) : BaseViewModel(app) {

    private val configFileName = "readConfig.zip"

    fun exportConfig(uri: Uri, onSuccess: (String) -> Unit, onError: (Throwable) -> Unit) {
        val exportFileName = if (ReadBookConfig.config.name.isBlank()) {
            configFileName
        } else {
            "${ReadBookConfig.config.name}.zip"
        }
        execute {
            val exportFiles = arrayListOf<File>()
            val configDir = context.externalCache.getFile("readConfig")
            configDir.createFolderReplace()
            val configFile = configDir.getFile("readConfig.json")
            configFile.createFileReplace()
            val config = ReadBookConfig.getExportConfig()
            val fontPath = ReadBookConfig.textFont
            if (fontPath.isNotEmpty()) {
                val fontDoc = FileDoc.fromFile(fontPath)
                val fontName = fontDoc.name
                val fontInputStream = fontDoc.openInputStream().getOrNull()
                fontInputStream?.use {
                    val fontExportFile = FileUtils.createFileIfNotExist(configDir, fontName)
                    fontExportFile.outputStream().use { out ->
                        it.copyTo(out)
                    }
                    config.textFont = fontName
                    exportFiles.add(fontExportFile)
                }
            }
            configFile.writeText(GSON.toJson(config))
            exportFiles.add(configFile)
            repeat(3) {
                val path = ReadBookConfig.durConfig.getBgPath(it) ?: return@repeat
                val bgExportFile = copyBgImage(path, configDir) ?: return@repeat
                exportFiles.add(bgExportFile)
            }
            val configZipPath = FileUtils.getPath(context.externalCache, configFileName)
            if (ZipUtils.zipFiles(exportFiles, File(configZipPath))) {
                val exportDir = FileDoc.fromDir(uri)
                exportDir.find(exportFileName)?.delete()
                val exportFileDoc = exportDir.createFileIfNotExist(exportFileName)
                exportFileDoc.openOutputStream().getOrThrow().use { out ->
                    File(configZipPath).inputStream().use {
                        it.copyTo(out)
                    }
                }
            }
        }.onSuccess {
            onSuccess(exportFileName)
        }.onError {
            onError(it)
        }
    }

    // 复制背景图片到导出目录
    private fun copyBgImage(path: String, configDir: File): File? {
        val bgName = FileUtils.getName(path)
        val bgFile = File(path)
        if (bgFile.exists()) {
            val bgExportFile = File(FileUtils.getPath(configDir, bgName))
            if (!bgExportFile.exists()) {
                bgFile.copyTo(bgExportFile)
                return bgExportFile
            }
        }
        return null
    }

    // 网络导入配置
    fun importNetConfig(url: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        execute {
            val bytes = okHttpClient.newCallResponseBody {
                url(url)
            }.bytes()
            ReadBookConfig.import(bytes)
        }.onSuccess {
            ReadBookConfig.durConfig = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            onSuccess()
        }.onError {
            onError(it)
        }
    }

    // 从URI导入配置
    fun importConfig(uri: Uri, onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        execute {
            val byteArray = uri.readBytes(context)
            ReadBookConfig.import(byteArray)
        }.onSuccess {
            ReadBookConfig.durConfig = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            onSuccess()
        }.onError {
            onError(it)
        }
    }

    // 从URI设置背景图片
    fun setBgFromUri(uri: Uri, onSuccess: () -> Unit, onError: (String?) -> Unit) {
        execute {
            val docName = if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)?.name
            } else {
                File(uri.path!!).name
            }
            val suffix = (docName ?: "unknown").substringAfterLast(".")
            val fileName = uri.inputStream(context).getOrThrow().use {
                MD5Utils.md5Encode(it) + ".$suffix"
            }
            var file = context.externalFiles
            file = FileUtils.createFileIfNotExist(file, "bg", fileName)
            uri.inputStream(context).getOrThrow().use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            ReadBookConfig.durConfig.setCurBg(2, fileName)
            postEvent(EventBus.UP_CONFIG, arrayListOf(1))
        }.onSuccess {
            onSuccess()
        }.onError {
            onError(it.localizedMessage)
        }
    }
}
