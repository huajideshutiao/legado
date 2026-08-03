@file:JvmName("GsonStreamExtensions")

package io.legado.app.utils

import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.Reader
import java.io.Writer

/**
 * Gson 兼容层: java.io 流式重载, 下沉 shared jvmAndAndroidMain。
 *
 * 纯 Kotlin 部分 (GSON/GSONStrict 别名, toJson(obj), fromJsonObject, fromJsonArray, toJsonElement)
 * 在 modules/shared/src/commonMain/kotlin/io/legado/app/utils/GsonExtensions.kt,
 * 调用方 import 路径不变 (io.legado.app.utils.*)。
 *
 * 本文件使用 @file:JvmName("GsonStreamExtensions") 改变生成的 class 文件名,
 * 避免与 shared commonMain 的 GsonExtensionsKt.class 同名冲突导致符号遮蔽。
 */

/**
 * 复刻 Gson.toJson(obj, Writer): 流式写入。
 *
 * kotlinx-serialization 不支持真正的流式写入, 这里先构造完整 JSON 字符串再写入。
 */
fun Json.toJson(obj: Any?, writer: Writer) {
    writer.write(toJson(obj))
}

/**
 * 复刻 Gson.fromJson(Reader, T): 从 Reader 读取 JSON 并解析。
 *
 * kotlinx-serialization 不支持 Reader, 这里先读取为 String 再解析。
 */
inline fun <reified T> Json.fromJson(reader: Reader): T =
    decodeFromString<T>(reader.readText())

/**
 * 复刻原 GSON.writeToOutputStream(stream, obj): 把对象序列化为 JSON 写入 OutputStream。
 *
 * kotlinx-serialization 不支持真正的流式写入, 这里先构造完整 JSON 字符串再写入。
 * 备份/导出场景为低频操作, 可接受一次性构造的内存开销。
 */
fun Json.writeToOutputStream(stream: OutputStream, obj: Any) {
    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(obj.toJsonElement().toString())
    }
}
