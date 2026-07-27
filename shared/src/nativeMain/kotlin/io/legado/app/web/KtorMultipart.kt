package io.legado.app.web

import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.core.readBytes
import io.legado.app.help.file.AppFilesDirs
import kotlin.io.File

/**
 * multipart/form-data 解析结果 (对齐 NanoHTTPD session.parseBody(files) 的语义)。
 *
 * - [formFields]: 表单字段名 -> 值列表 (对齐 NanoHTTPD session.parameters 的 Map<String, List<String>>)
 * - [files]: 文件字段名 -> 临时文件路径 (对齐 NanoHTTPD parseBody 把文件写入 temp file, files[name]=path)
 */
data class MultipartParseResult(
    val formFields: Map<String, List<String>>,
    val files: Map<String, String>,
)

/**
 * Ktor multipart 解析: ApplicationCall -> [MultipartParseResult]。
 *
 * 对齐 NanoHTTPD [io.legado.app.web.HttpServer] 的 `session.parseBody(files)`:
 * - FormItem -> formFields (同 NanoHTTPD session.parameters 中的表单字段)
 * - FileItem -> 写入临时文件 (cacheDir/upload_*.tmp), files[name] = tempFilePath
 *   (对齐 NanoHTTPD parseBody 写 temp file 的行为, BookController.addLocalBook 用
 *   files["fileData"] 取路径调 saveBookFileFromPath)
 *
 * # 与 NanoHTTPD 的差异
 * NanoHTTPD parseBody 把表单字段和文件路径混在同一 files map; 本实现拆分为
 * [formFields] + [files], KtorRouting.toWebApiRequest 把 formFields 合并到 query,
 * files 传给 WebApiRequest.files, 行为等价 (addLocalBook 只读 files["fileData"])。
 */
suspend fun ApplicationCall.parseMultipart(): MultipartParseResult {
    val multipart = receiveMultipart()
    val formFields = mutableMapOf<String, MutableList<String>>()
    val files = mutableMapOf<String, String>()

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> {
                val name = part.name ?: return@forEachPart
                formFields.getOrPut(name) { mutableListOf() }.add(part.value)
            }
            is PartData.FileItem -> {
                val name = part.name ?: return@forEachPart
                val bytes = part.provider().readBytes()
                val tempFile = File(
                    AppFilesDirs.get().cacheDir,
                    "upload_${kotlin.system.getTimeMillis()}_${name}.tmp",
                )
                tempFile.writeBytes(bytes)
                files[name] = tempFile.absolutePath
            }
            else -> {}
        }
        part.dispose()
    }

    return MultipartParseResult(
        formFields = formFields.mapValues { it.value.toList() },
        files = files,
    )
}
