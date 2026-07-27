package io.legado.app.model.fileBook

import java.io.InputStream
import java.util.Enumeration

/** zip 容器统一读取面；工厂 create(book) 在 app 侧 ZipFileWrappers（Book/WebDav 绑定） */
interface ZipFileWrapper {
    fun getEntry(name: String): ZipEntry?
    fun getInputStream(entry: ZipEntry): InputStream?
    fun entries(): Enumeration<out ZipEntry>
    fun close()
}
