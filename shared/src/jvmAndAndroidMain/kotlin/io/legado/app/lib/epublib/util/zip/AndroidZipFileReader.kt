package io.legado.app.lib.epublib.util.zip

import java.io.IOException
import java.io.InputStream
import java.util.Enumeration

/**
 * PFD 随机访问 zip 的读取面。
 *
 * 原 app 端 [ZipFileWrapper] 直接 `is AndroidZipFile ->` 分派; 下沉后 AndroidZipFile
 * 依赖 `android.os.ParcelFileDescriptor` 只能放 androidMain, jvmAndAndroidMain 不可见,
 * 故抽出本接口做分派锚点, 方法签名与 AndroidZipFile 原有成员逐一对应。
 */
interface AndroidZipFileReader {

    /** zip 文件名 (原 AndroidZipFile.name)。 */
    val name: String?

    fun getEntry(name: String?): AndroidZipEntry?

    fun entries(): Enumeration<AndroidZipEntry?>?

    @Throws(IOException::class)
    fun getInputStream(entry: AndroidZipEntry): InputStream

    @Throws(IOException::class)
    fun close()
}
